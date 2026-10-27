package fr.tcdrm.exp;

import fr.tcdrm.cloud.MultiCloud;
import fr.tcdrm.config.SimConfig;
import fr.tcdrm.core.SimulationRunner;
import fr.tcdrm.core.SimulationRunner.Options;
import fr.tcdrm.core.SimulationRunner.Result;
import fr.tcdrm.forecast.Lstm;
import fr.tcdrm.forecast.PopularityForecaster;
import fr.tcdrm.placement.PlacementStrategy;
import fr.tcdrm.placement.PredictivePlacement;
import fr.tcdrm.placement.ReplicateEverywhere;
import fr.tcdrm.placement.TcdrmPlacement;
import fr.tcdrm.workload.Workloads;
import fr.tcdrm.workload.Workloads.S3Params;
import fr.tcdrm.workload.Workloads.Workload;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/** Définition des scénarios S1, S2, S3 et des variantes comparées. */
public final class Experiments {

    public final SimConfig c;

    public Experiments(SimConfig c) { this.c = c; }

    /** Stratégie null = NoRepLc. */
    public record Variant(String name, Supplier<PlacementStrategy> make) { }

    // S1 / S2 : scénario de l'article
    public List<Variant> articleVariants() {
        return List.of(
                new Variant("NoRepLc", () -> null),
                new Variant("TCDRM", TcdrmPlacement::new),
                new Variant("TCDRM-Predictive", () -> new PredictivePlacement(c, "TCDRM-Predictive",
                        new PopularityForecaster(c, null, "auto"), true)),
                new Variant("Predictive sans contraintes", () -> new PredictivePlacement(c,
                        "Predictive sans contraintes", new PopularityForecaster(c, null, "auto"), true, false)),
                new Variant("Replicate-All", ReplicateEverywhere::new));
    }

    /** Premier instant à partir duquel le coût cumulé reste inférieur à celui de NoRepLc : {heures, requête} ou null. */
    public static double[] breakEven(Result strat, Result base) {
        double cs = 0, cb = 0;
        int lastNonPositive = -1, n = strat.rows.size();
        double[] diff = new double[n];
        for (int i = 0; i < n; i++) {
            Object[] a = strat.rows.get(i), b = base.rows.get(i);
            cs += (Double) a[5] + (Double) a[6] + (Double) a[7] + strat.mgr.extraCost.getOrDefault((Integer) a[0], 0.0);
            cb += (Double) b[5] + (Double) b[6] + (Double) b[7] + base.mgr.extraCost.getOrDefault((Integer) b[0], 0.0);
            diff[i] = cb - cs;
            if (diff[i] <= 1e-12) lastNonPositive = i;
        }
        if (lastNonPositive == n - 1) return null;
        int k = lastNonPositive + 1;
        return new double[]{(Double) strat.rows.get(k)[1], k + 1};
    }

    static void putStats(Result r, PlacementStrategy p) {
        if (p instanceof PredictivePlacement pp) pp.stats.forEach((k, v) -> r.summary.put("p2_" + k, v));
    }

    static void addBreakEven(List<Result> rs) {
        Result base = null;
        for (Result r : rs) if (r.label.equals("NoRepLc")) base = r;
        if (base == null) return;
        for (Result r : rs) {
            if (r == base) continue;
            double[] be = breakEven(r, base);
            r.summary.put("break_even_h", be == null ? "" : be[0]);
            r.summary.put("break_even_query", be == null ? "" : (int) be[1]);
        }
    }

    public List<Result> runArticle(String kind, int seed) {
        List<Result> out = new ArrayList<>();
        for (Variant v : articleVariants()) {
            Workload w = Workloads.article(c, kind, seed);
            Options o = new Options();
            o.psla = c.dbl("psla");
            o.delPsla = o.psla;
            o.sla = c.sla;
            PlacementStrategy p = v.make().get();
            Result r = SimulationRunner.run(c, w, p, v.name(), o);
            putStats(r, p);
            r.summary.put("scenario", kind);
            r.summary.put("seed", seed);
            out.add(r);
        }
        addBreakEven(out);
        return out;
    }

    // S3 : scénario dynamique
    /** Variation des prix (période s3.priceChangePeriod) : Azure double sa sortie inter-région, Google la divise
     *  par deux, AWS baisse sa sortie inter-fournisseur. */
    public static void priceChange(MultiCloud cl) {
        cl.setInterRegion("Azure", 0.016);
        cl.setInterRegion("Google", 0.004);
        cl.setInterProvider("AWS", 0.008);
    }

    /** Budget par cycle = facteur × coût moyen par cycle de NoRepLc (les SLA restent ceux de la Table 1). */
    public double calibrateBudget(Workload w) {
        Options o = new Options();
        o.psla = c.dbl("s3.psla");
        o.delPsla = c.dbl("s3.delPsla");
        o.sla = c.sla;
        o.priceHour = c.integer("s3.priceChangePeriod");
        o.priceChange = Experiments::priceChange;
        Result r = SimulationRunner.run(c, w, null, "calibration", o);
        double total = 0;
        for (Object[] row : r.rows) total += (Double) row[5] + (Double) row[6] + (Double) row[7];
        double cycles = w.nPeriods / (double) w.cycle;
        return c.dbl("s3.budgetFactor") * total / cycles;
    }

    static double median(List<Double> v) {
        double[] a = v.stream().mapToDouble(Double::doubleValue).sorted().toArray();
        int n = a.length;
        return n % 2 == 1 ? a[n / 2] : (a[n / 2 - 1] + a[n / 2]) / 2;
    }

    public List<Variant> dynamicVariants(Lstm lstm) {
        return List.of(
                new Variant("NoRepLc", () -> null),
                new Variant("TCDRM", TcdrmPlacement::new),
                new Variant("Replicate-All", ReplicateEverywhere::new),
                new Variant("P2", () -> new PredictivePlacement(c, "P2", null, false)),
                new Variant("P1+P2", () -> new PredictivePlacement(c, "P1+P2",
                        new PopularityForecaster(c, lstm, "auto"), false)),
                new Variant("P3+P2", () -> new PredictivePlacement(c, "P3+P2", null, true)),
                new Variant("TCDRM-Predictive", () -> new PredictivePlacement(c, "TCDRM-Predictive",
                        new PopularityForecaster(c, lstm, "auto"), true)),
                new Variant("Predictive sans contraintes", () -> new PredictivePlacement(c,
                        "Predictive sans contraintes", new PopularityForecaster(c, lstm, "auto"), true, false)));
    }

    public static final class DynamicRun {
        public final List<Result> results = new ArrayList<>();
        public Map<String, double[]> sla;
        public double budget;
    }

    public DynamicRun runDynamic(int seed, S3Params sp, List<Variant> variants) {
        Workload w = Workloads.dynamic(c, seed, sp);
        DynamicRun dr = new DynamicRun();
        dr.sla = c.sla;
        dr.budget = calibrateBudget(w);
        for (Variant v : variants) {
            Options o = new Options();
            o.psla = c.dbl("s3.psla");
            o.delPsla = c.dbl("s3.delPsla");
            o.deltaT = c.integer("s3.deltaT");
            o.budgetPerDay = dr.budget;
            o.sla = dr.sla;
            o.priceHour = c.integer("s3.priceChangePeriod");
            o.priceChange = Experiments::priceChange;
            PlacementStrategy p = v.make().get();
            Result r = SimulationRunner.run(c, w, p, v.name(), o);
            r.summary.put("seed", seed);
            r.summary.put("write_prob", sp.writeProb);
            r.summary.put("budget_day", dr.budget);
            if (p instanceof PredictivePlacement pp && pp.forecaster() != null) {
                r.summary.put("p1_methods", pp.forecaster().used.toString());
            }
            putStats(r, p);
            dr.results.add(r);
        }
        addBreakEven(dr.results);
        return dr;
    }

    public static String[] arr(String... s) { return Arrays.copyOf(s, s.length); }
}
