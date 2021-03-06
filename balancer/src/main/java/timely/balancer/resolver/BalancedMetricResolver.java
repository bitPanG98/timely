package timely.balancer.resolver;

import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.Charset;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Random;
import java.util.Timer;
import java.util.TimerTask;
import java.util.TreeMap;

import com.csvreader.CsvReader;
import com.csvreader.CsvWriter;
import org.apache.commons.lang.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import timely.balancer.ArrivalRate;
import timely.balancer.BalancerConfiguration;
import timely.balancer.connection.TimelyBalancedHost;
import timely.balancer.healthcheck.HealthChecker;

public class BalancedMetricResolver implements MetricResolver {

    private static final Logger LOG = LoggerFactory.getLogger(BalancedMetricResolver.class);

    private Map<String, TimelyBalancedHost> metricToHostMap = Collections.synchronizedMap(new TreeMap<>());
    private Map<String, ArrivalRate> metricMap = Collections.synchronizedMap(new HashMap<>());
    private Map<Integer, TimelyBalancedHost> serverMap = new HashMap<>();
    private Random r = new Random();
    final private HealthChecker healthChecker;
    private Timer timer = new Timer("RebalanceTimer", true);
    private long balanceUntil = System.currentTimeMillis() + 1800000l;

    public BalancedMetricResolver(BalancerConfiguration config, HealthChecker healthChecker) {
        int n = 0;
        synchronized (this) {
            for (TimelyBalancedHost h : config.getTimelyHosts()) {
                h.setConfig(config);
                serverMap.put(n++, h);
            }
        }
        synchronized (metricToHostMap) {
            metricToHostMap.putAll(readAssignments(config.getAssignmentFile()));
        }

        this.healthChecker = healthChecker;

        timer.schedule(new TimerTask() {

            @Override
            public void run() {
                try {
                    rebalanceAllMetrics();
                } catch (Exception e) {
                    LOG.error(e.getMessage(), e);
                }
            }
        }, 300000);

        timer.schedule(new TimerTask() {

            @Override
            public void run() {
                if (System.currentTimeMillis() < balanceUntil) {
                    try {
                        balance();
                    } catch (Exception e) {
                        LOG.error(e.getMessage(), e);
                    }
                }
            }
        }, 600000, 120000);

        timer.schedule(new TimerTask() {

            @Override
            public void run() {
                try {
                    writeAssigments(config.getAssignmentFile());
                } catch (Exception e) {
                    LOG.error(e.getMessage(), e);
                }
            }
        }, 600000, 3600000);
    }

    private TimelyBalancedHost getLeastUsedHost() {

        Map<Double, TimelyBalancedHost> rateSortedHosts = new TreeMap<>();
        for (Map.Entry<Integer, TimelyBalancedHost> e : serverMap.entrySet()) {
            rateSortedHosts.put(e.getValue().getArrivalRate(), e.getValue());
        }

        Iterator<Map.Entry<Double, TimelyBalancedHost>> itr = rateSortedHosts.entrySet().iterator();

        TimelyBalancedHost tbh = null;

        while (itr.hasNext() && tbh == null) {
            TimelyBalancedHost currentTBH = itr.next().getValue();
            if (currentTBH.isUp()) {
                tbh = currentTBH;
            }
        }
        return tbh;
    }

    private TimelyBalancedHost getRandomHost(TimelyBalancedHost notThisOne) {

        TimelyBalancedHost tbh = null;
        for (int x = 0; tbh == null && x < serverMap.size(); x++) {
            tbh = serverMap.get(Math.abs(r.nextInt() & Integer.MAX_VALUE) % serverMap.size());
            if (!tbh.isUp()) {
                tbh = null;
            } else if (notThisOne != null && tbh.equals(notThisOne)) {
                tbh = null;
            }
        }
        return tbh;
    }

    private TimelyBalancedHost getRoundRobinHost() {
        TimelyBalancedHost tbh;
        synchronized (metricToHostMap) {
            int x = metricToHostMap.size() % serverMap.size();
            tbh = serverMap.get(x);
        }
        if (tbh.isUp()) {
            return tbh;
        } else {
            return getRandomHost(null);
        }
    }

    public void rebalanceAllMetrics() {

        Map<Double, String> rateSortedMetrics = new TreeMap<>();
        synchronized (metricMap) {
            for (Map.Entry<String, ArrivalRate> e : metricMap.entrySet()) {
                rateSortedMetrics.put(e.getValue().getRate(), e.getKey());
            }
        }
        synchronized (metricToHostMap) {
            metricToHostMap.clear();
            for (String m : rateSortedMetrics.values()) {
                metricToHostMap.put(m, getRoundRobinHost());
            }
        }
    }

    public void balance() {
        LOG.info("rebalancing begin");
        Map<Double, TimelyBalancedHost> ratedSortedHosts = new TreeMap<>();
        double totalArrivalRate = 0;
        for (Map.Entry<Integer, TimelyBalancedHost> e : serverMap.entrySet()) {
            ratedSortedHosts.put(e.getValue().getArrivalRate(), e.getValue());
            totalArrivalRate += e.getValue().getArrivalRate();
        }

        Iterator<Map.Entry<Double, TimelyBalancedHost>> itr = ratedSortedHosts.entrySet().iterator();
        TimelyBalancedHost mostUsed = null;
        TimelyBalancedHost leastUsed = null;

        while (itr.hasNext()) {
            TimelyBalancedHost currentTBH = itr.next().getValue();
            if (currentTBH.isUp()) {
                if (leastUsed == null) {
                    leastUsed = currentTBH;
                    mostUsed = currentTBH;
                } else {
                    // should end up with the last server that is up
                    mostUsed = currentTBH;
                }
            }
        }

        double averageArrivalRate = totalArrivalRate / serverMap.size();
        double highestArrivalRate = mostUsed.getArrivalRate();
        double lowestArrivalRate = leastUsed.getArrivalRate();

        LOG.info("rebalancing high:{} avg:{} low:{}", highestArrivalRate, averageArrivalRate, lowestArrivalRate);

        // 5% over average
        int numReassigned = 0;
        if (highestArrivalRate > averageArrivalRate * 1.05) {
            synchronized (metricMap) {
                synchronized (metricToHostMap) {
                    LOG.info("rebalancing: high > 5% higher than average");
                    // sort metrics by rate
                    Map<Double, String> rateSortedMetrics = new TreeMap<>();
                    for (Map.Entry<String, ArrivalRate> e : metricMap.entrySet()) {
                        rateSortedMetrics.put(e.getValue().getRate(), e.getKey());
                    }

                    double desiredDeltaHighest = (highestArrivalRate - averageArrivalRate) * 0.1;
                    double desiredDeltaLowest = (averageArrivalRate - lowestArrivalRate) * 0.1;
                    Iterator<Map.Entry<Double, String>> metricItr = rateSortedMetrics.entrySet().iterator();
                    boolean done = false;
                    LOG.info("rebalancing: desiredDeltaHighest:{} desiredDeltaLowest:{} rateSortedMetrics.size():{}",
                            desiredDeltaHighest, desiredDeltaLowest, rateSortedMetrics.size());
                    // advance to halfway
                    for (int numMetric = 0; metricItr.hasNext()
                            && numMetric <= rateSortedMetrics.size() / 2; numMetric++) {
                        metricItr.next();
                    }
                    long maxToReassign = Math.round(((double) metricMap.size() / serverMap.size()) * 0.20);
                    while (!done && metricItr.hasNext() && numReassigned < maxToReassign) {
                        Map.Entry<Double, String> current = metricItr.next();
                        Double currentRate = current.getKey();
                        String currentMetric = current.getValue();

                        if (desiredDeltaHighest > 0) {
                            if (metricToHostMap.get(currentMetric).equals(mostUsed)) {
                                LOG.debug("rebalancing: trying to reassign metric {} from server {}:{}", currentMetric,
                                        mostUsed.getHost(), mostUsed.getTcpPort());
                                if (desiredDeltaHighest > 0) {
                                    numReassigned++;
                                    metricToHostMap.put(currentMetric, leastUsed);
                                    LOG.debug("rebalancing: reassigning metric {} from server {}:{} to {}:{}",
                                            currentMetric, mostUsed.getHost(), mostUsed.getTcpPort(),
                                            leastUsed.getHost(), leastUsed.getTcpPort());
                                    desiredDeltaLowest -= currentRate;
                                    desiredDeltaHighest -= currentRate;
                                } else {
                                    TimelyBalancedHost tbh = getRandomHost(mostUsed);
                                    if (tbh != null) {
                                        numReassigned++;
                                        metricToHostMap.put(currentMetric, tbh);
                                        LOG.info("rebalancing: reassigning metric {} from server {}:{} to {}:{}",
                                                currentMetric, mostUsed.getHost(), mostUsed.getTcpPort(), tbh.getHost(),
                                                tbh.getTcpPort());
                                        desiredDeltaHighest -= currentRate;
                                    } else {
                                        LOG.debug(
                                                "rebalancing: unable to reassign metric {} from server {}:{} - getRandomHost returned null",
                                                currentMetric, mostUsed.getHost(), mostUsed.getTcpPort());
                                    }
                                }
                            }
                        } else {
                            done = true;
                        }
                    }
                }
            }
        }
        LOG.info("rebalancing end - reassigned {}", numReassigned);
    }

    @Override
    public TimelyBalancedHost getHostPortKeyIngest(String metric) {
        if (StringUtils.isNotBlank(metric)) {
            ArrivalRate rate;
            synchronized (metricMap) {
                rate = metricMap.get(metric);
                if (rate == null) {
                    rate = new ArrivalRate();
                    metricMap.put(metric, rate);
                }
            }
            rate.arrived();
        }

        TimelyBalancedHost tbh;
        if (StringUtils.isBlank(metric)) {
            tbh = getRandomHost(null);
        } else {
            synchronized (metricToHostMap) {
                tbh = metricToHostMap.get(metric);
                if (tbh == null) {
                    tbh = getRoundRobinHost();
                    metricToHostMap.put(metric, tbh);
                } else if (!tbh.isUp()) {
                    TimelyBalancedHost oldTbh = tbh;
                    tbh = getLeastUsedHost();
                    LOG.debug("rebalancing from host that is down: reassigning metric {} from server {}:{} to {}:{}",
                            metric, oldTbh.getHost(), oldTbh.getTcpPort(), tbh.getHost(), tbh.getTcpPort());
                    metricToHostMap.put(metric, tbh);
                }
            }
        }

        // if all else fails
        if (tbh == null || !tbh.isUp()) {
            for (TimelyBalancedHost h : serverMap.values()) {
                if (h.isUp()) {
                    tbh = h;
                    break;
                }
            }
            if (tbh != null && StringUtils.isNotBlank(metric)) {
                synchronized (metricToHostMap) {
                    metricToHostMap.put(metric, tbh);
                }
            }
        }
        if (tbh != null) {
            tbh.arrived();
            tbh.calculateRate();
        }
        return tbh;
    }

    @Override
    public TimelyBalancedHost getHostPortKey(String metric) {
        TimelyBalancedHost tbh = null;
        if (StringUtils.isNotBlank(metric)) {
            synchronized (metricToHostMap) {
                tbh = metricToHostMap.get(metric);
            }
        }

        if (tbh == null || !tbh.isUp()) {
            for (int x = 0; tbh == null && x < serverMap.size(); x++) {
                tbh = serverMap.get(Math.abs(r.nextInt() & Integer.MAX_VALUE) % serverMap.size());
                if (!tbh.isUp()) {
                    tbh = null;
                }
            }
        }

        // if all else fails
        if (tbh == null || !tbh.isUp()) {
            for (TimelyBalancedHost h : serverMap.values()) {
                if (h.isUp()) {
                    tbh = h;
                    break;
                }
            }
            if (tbh != null && StringUtils.isNotBlank(metric)) {
                synchronized (metricToHostMap) {
                    metricToHostMap.put(metric, tbh);
                }
            }
        }
        return tbh;
    }

    private TimelyBalancedHost findHost(String host, int tcpPort) {
        TimelyBalancedHost tbh = null;
        for (TimelyBalancedHost h : serverMap.values()) {
            if (h.getHost().equals(host) && h.getTcpPort() == tcpPort) {
                tbh = h;
                break;
            }
        }
        return tbh;
    }

    private Map<String, TimelyBalancedHost> readAssignments(String path) {

        Map<String, TimelyBalancedHost> assignedMetricToHostMap = new TreeMap<>();
        CsvReader reader = null;
        try {
            reader = new CsvReader(new FileInputStream(path), ',', Charset.forName("UTF-8"));
            // reader.setSkipEmptyRecords(true);
            reader.setUseTextQualifier(false);

            // skip the headers
            boolean success = true;
            success = reader.readHeaders();

            while (success) {
                success = reader.readRecord();
                String[] nextLine = reader.getValues();
                if (nextLine.length > 3) {
                    String metric = nextLine[0];
                    String host = nextLine[1];
                    int tcpPort = Integer.parseInt(nextLine[2]);
                    TimelyBalancedHost tbh = findHost(host, tcpPort);
                    if (tbh == null) {
                        tbh = getRoundRobinHost();
                    } else {
                        LOG.trace("Found assigment: {} to {}:{}", metric, host, tcpPort);
                    }
                    assignedMetricToHostMap.put(metric, tbh);
                }
            }
        } catch (IOException e) {
            LOG.error(e.getMessage(), e);
        }
        return assignedMetricToHostMap;
    }

    private void writeAssigments(String path) {

        CsvWriter writer = null;
        try {
            writer = new CsvWriter(new FileOutputStream(path), ',', Charset.forName("UTF-8"));
            writer.setUseTextQualifier(false);

            writer.write("metric");
            writer.write("host");
            writer.write("tcpPort");
            writer.write("rate");
            writer.endRecord();

            synchronized (metricMap) {
                synchronized (metricToHostMap) {

                    for (Map.Entry<String, TimelyBalancedHost> e : metricToHostMap.entrySet()) {
                        writer.write(e.getKey());
                        writer.write(e.getValue().getHost());
                        writer.write(Integer.toString(e.getValue().getTcpPort()));
                        writer.write(Double.toString(metricMap.get(e.getKey()).getRate()));
                        writer.endRecord();
                        LOG.trace("Saving assigment: {} to {}:{}", e.getKey(), e.getValue().getHost(),
                                e.getValue().getTcpPort());
                    }
                }
            }
        } catch (IOException e) {
            LOG.error(e.getMessage(), e);
        } finally {
            if (writer != null) {
                writer.close();
            }
        }
    }
}
