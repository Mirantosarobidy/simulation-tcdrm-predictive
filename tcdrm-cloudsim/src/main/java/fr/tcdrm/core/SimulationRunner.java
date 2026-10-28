package fr.tcdrm.core;

import fr.tcdrm.cloud.CloudFactory;
import fr.tcdrm.cloud.MultiCloud;
import fr.tcdrm.config.SimConfig;
import fr.tcdrm.placement.PlacementStrategy;
import fr.tcdrm.workload.Workloads.Workload;
import org.cloudbus.cloudsim.Datacenter;
import org.cloudbus.cloudsim.Log;
import org.cloudbus.cloudsim.Vm;
import org.cloudbus.cloudsim.core.CloudSim;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/** Lance une simulation CloudSim complète pour une stratégie et une charge données. */
public final class SimulationRunner {

    private static boolean traced = false;

    public static final class Options {
        public double psla, delPsla;
        public Integer deltaT;
        public Double budgetPerDay;
        public Map<String, double[]> sla;
        public int priceHour = -1;
        public Consumer<MultiCloud> priceChange;
    }

    public static final class Result {
        public final String label;
        public final ReplicaManager mgr;
        public final List<Object[]> rows;       // triées par qid
        public final Map<String, Object> summary;

        Result(String label, ReplicaManager mgr, List<Object[]> rows, Map<String, Object> summary) {
            this.label = label;
            this.mgr = mgr;
            this.rows = rows;
            this.summary = summary;
        }
    }

    public static final String[] COLS = {"qid", "t", "kind", "origin", "rt", "cpu", "bw", "sync", "gb_local",
            "gb_region", "gb_provider", "gb_sync", "viol_T", "viol_C", "n_replicas", "n_new", "ic_cost", "relations"};

    private SimulationRunner() { }

    public static Result run(SimConfig c, Workload w, PlacementStrategy placement, String label, Options o) {
        try {
            boolean trace = traceThisRun(c, label);
            if (trace) {
                Log.setOutput(new java.io.FileOutputStream(c.str("cloudsim.log.file")));
                Log.enable();
            } else {
                Log.disable();
            }
            CloudSim.init(1, Calendar.getInstance(), false, 1e-6);
            CloudSim.init(1, Calendar.getInstance(), false, 1e-6);
            MultiCloud cloud = new MultiCloud(c);
            Datacenter[] dcs = CloudFactory.createDatacenters(c, cloud);
            ReplicaManager mgr = new ReplicaManager(c, cloud, w.catalog, placement, label, w.nPeriods,
                    o.psla, o.delPsla, o.deltaT, o.budgetPerDay, o.sla);
            mgr.setTimeScale(w.periodSeconds, w.cycle);
            Map<Integer, Integer> target = new HashMap<>();
            Map<String, List<Vm>> byRegion = new HashMap<>();
            List<Vm> vms = new ArrayList<>();
            TcdrmBroker broker = new TcdrmBroker("Broker-" + label.replaceAll("[^A-Za-z0-9+_-]", "_"), mgr, w.queries, target, byRegion,
                    o.priceHour, o.priceChange);
            int vmId = 0;
            for (String r : c.regions) {
                int site = cloud.execOf(r);
                byRegion.put(r, new ArrayList<>());
                for (int k = 0; k < c.integer("vms.perRegion"); k++) {
                    Vm vm = CloudFactory.createVm(c, vmId, broker.getId());
                    vms.add(vm);
                    byRegion.get(r).add(vm);
                    target.put(vmId, dcs[site].getId());
                    vmId++;
                }
            }
            broker.submitGuestList(vms);
            CloudSim.startSimulation();
            CloudSim.stopSimulation();
            if (trace) { Log.getOutput().flush(); Log.disable(); }
            List<Object[]> rows = new ArrayList<>(mgr.rows);
            rows.sort(Comparator.comparingInt(a -> (Integer) a[0]));
            if (rows.size() != w.queries.size()) {
                throw new IllegalStateException(label + " : " + rows.size() + " requêtes terminées sur " + w.queries.size());
            }
            return new Result(label, mgr, rows, summarize(label, mgr, rows));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    static double quantile(double[] v, double q) {
        double[] s = v.clone();
        Arrays.sort(s);
        double pos = q * (s.length - 1);
        int lo = (int) Math.floor(pos), hi = (int) Math.ceil(pos);
        return s[lo] + (s[hi] - s[lo]) * (pos - lo);
    }

    static Map<String, Object> summarize(String label, ReplicaManager m, List<Object[]> rows) {
        double cpu = 0, bw = 0, sync = 0, gbP = 0, gbR = 0, gbL = 0, gbS = 0, vT = 0, vC = 0, ic = 0;
        List<Double> rts = new ArrayList<>();
        for (Object[] r : rows) {
            cpu += (Double) r[5]; bw += (Double) r[6]; sync += (Double) r[7];
            gbL += (Double) r[8]; gbR += (Double) r[9]; gbP += (Double) r[10]; gbS += (Double) r[11];
            if ((Boolean) r[13]) vC++;
            ic += (Double) r[16];
            if (!"write".equals(r[2])) {
                rts.add((Double) r[4]);
                if ((Boolean) r[12]) vT++;
            }
        }
        double[] rt = rts.stream().mapToDouble(Double::doubleValue).toArray();
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("strategy", label);
        s.put("queries", rows.size());
        s.put("rt_mean", Arrays.stream(rt).average().orElse(0));
        s.put("rt_p95", quantile(rt, 0.95));
        s.put("rt_p99", quantile(rt, 0.99));
        s.put("cost_cpu", cpu);
        s.put("cost_bw", bw);
        s.put("cost_sync", sync);
        s.put("cost_replica_create", m.createTotal);
        s.put("cost_storage", m.storageTotal);
        s.put("cost_total", cpu + bw + sync + m.createTotal + m.storageTotal);
        s.put("cost_placement", bw + sync + m.createTotal + m.storageTotal);
        s.put("gb_provider", gbP);
        s.put("gb_region", gbR);
        s.put("gb_local", gbL);
        s.put("gb_sync", gbS);
        s.put("gb_replication", m.gbReplication);
        // bande passante inter-cloud : volume et coût (lectures/écritures distantes + synchro + copies)
        s.put("gb_intercloud", gbP + gbR + gbS + m.gbReplication);
        s.put("cost_intercloud", ic + sync + m.createInterCloud);
        s.put("sla_T_violation_pct", 100.0 * vT / Math.max(1, rt.length));
        s.put("sla_C_violation_pct", 100.0 * vC / rows.size());
        s.put("replicas_created", m.nCreated);
        s.put("replicas_deleted", m.nDeleted);
        s.put("replicas_final", m.nReplicas());
        s.put("thrash", m.thrash);
        s.put("thrash_rate_pct", 100.0 * m.thrash / Math.max(1, m.nCreated));
        // respect du budget (période = jour)
        int over = 0;
        double overspend = 0, maxRatio = 0;
        if (m.budgetPerDay != null) {
            for (double d : m.dayLog) {
                if (d > m.budgetPerDay) { over++; overspend += d - m.budgetPerDay; }
                maxRatio = Math.max(maxRatio, 100 * d / m.budgetPerDay);
            }
        }
        s.put("days_over_budget", m.budgetPerDay == null ? "" : over);
        s.put("budget_days", m.budgetPerDay == null ? "" : m.dayLog.size());
        s.put("budget_violation_pct", m.budgetPerDay == null ? "" : 100.0 * over / Math.max(1, m.dayLog.size()));
        s.put("overspend_usd", m.budgetPerDay == null ? "" : overspend);
        s.put("max_day_pct_of_budget", m.budgetPerDay == null ? "" : maxRatio);
        // facteur de réplication
        s.put("replicas_active_mean", m.activeLog.isEmpty() ? (double) m.nReplicas()
                : m.activeLog.stream().mapToInt(Integer::intValue).average().orElse(0));
        // amortissement économique (par réplica)
        List<Double> pay = new ArrayList<>();
        double net = 0;
        for (double[] o : m.replicaOutcomes) {
            net += o[3];
            if (!Double.isNaN(o[4])) pay.add(o[4]);
        }
        int nOut = m.replicaOutcomes.size();
        double[] pv = pay.stream().mapToDouble(Double::doubleValue).toArray();
        s.put("replicas_paid_back_pct", nOut == 0 ? "" : 100.0 * pv.length / nOut);
        s.put("payback_median_h", pv.length == 0 ? "" : quantile(pv, 0.5));
        s.put("payback_p90_h", pv.length == 0 ? "" : quantile(pv, 0.9));
        s.put("replica_net_gain_usd", nOut == 0 ? "" : net);
        // audit des garanties (lectures servies par des réplicas placés sous contraintes)
        s.put("audit_reads", m.auditReads);
        s.put("audit_TSLA_share_violation_pct", m.auditReads == 0 ? "" : 100.0 * m.auditTViol / m.auditReads);
        s.put("audit_CSLA_share_violation_pct", m.auditReads == 0 ? "" : 100.0 * m.auditCViol / m.auditReads);
        Integer first = null;
        for (Object[] r : rows) if ((Integer) r[14] > 0) { first = (Integer) r[0] + 1; break; }
        s.put("first_replica_query", first == null ? "" : first);
        return s;
    }

    private static boolean traceThisRun(SimConfig c, String label) {
        if (traced || !c.has("cloudsim.log") || !Boolean.parseBoolean(c.str("cloudsim.log"))) return false;
        if (c.has("cloudsim.log.run") && !label.contains(c.str("cloudsim.log.run"))) return false;
        traced = true;
        return true;
    }
}
