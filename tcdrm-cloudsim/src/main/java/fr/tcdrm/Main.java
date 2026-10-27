package fr.tcdrm;

import fr.tcdrm.charts.Charts;
import fr.tcdrm.config.SimConfig;
import fr.tcdrm.core.SimulationRunner;
import fr.tcdrm.core.SimulationRunner.Result;
import fr.tcdrm.exp.Experiments;
import fr.tcdrm.exp.Experiments.DynamicRun;
import fr.tcdrm.exp.Experiments.Variant;
import fr.tcdrm.exp.ForecastValidation;
import fr.tcdrm.forecast.Lstm;
import fr.tcdrm.util.Csv;
import fr.tcdrm.workload.Workloads.S3Params;

import java.io.ObjectOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Lance S1, S2 et S3 puis écrit results/*.csv et figures/*.png. */
public final class Main {

    public static void main(String[] args) throws Exception {
        System.setOut(new PrintStream(System.out, true, StandardCharsets.UTF_8));
        System.setProperty("java.awt.headless", "true");
        Path out = Path.of(args.length > 0 ? args[0] : ".");
        Path res = out.resolve("results"), fig = out.resolve("figures");
        Files.createDirectories(res);
        SimConfig c = SimConfig.load();
        Experiments ex = new Experiments(c);
        Charts charts = new Charts(fig);
        int s3Periods = (int) Math.ceil(c.integer("s3.queries") / c.dbl("s3.periodQueries"));
        charts.setTimeScale(s3Periods, c.integer("s3.cycle"), c.integer("s3.periodQueries"));
        int seeds = c.integer("seeds"), sensSeeds = c.integer("sensitivity.seeds");
        long t0 = System.currentTimeMillis();

        // S1 + S2
        System.out.println("S1/S2 : scénario de l'article (" + seeds + " graines)");
        List<Map<String, Object>> s12 = new ArrayList<>();
        Map<String, List<Result>> seed0 = new HashMap<>();
        List<Object[]> q0 = new ArrayList<>();
        for (String kind : new String[]{"simple", "complex"}) {
            for (int seed = 0; seed < seeds; seed++) {
                List<Result> rs = ex.runArticle(kind, seed);
                for (Result r : rs) s12.add(r.summary);
                if (seed == 0) {
                    seed0.put(kind, rs);
                    for (Result r : rs) for (Object[] row : r.rows) q0.add(concat(row, kind, r.label,
                            r.mgr.extraCost.getOrDefault((Integer) row[0], 0.0)));
                }
            }
        }
        Csv.writeMaps(res.resolve("s1s2_summary_all_seeds.csv"), s12);
        Csv.writeRows(res.resolve("s1s2_queries_seed0.csv"), header("scenario", "strategy", "replica"), q0);
        charts.articleFigures(seed0, "s1_", new String[]{"NoRepLc", "TCDRM"});
        charts.articleFigures(seed0, "s2_", new String[]{"NoRepLc", "TCDRM", "TCDRM-Predictive"});
        printTable(s12, new String[]{"rt_mean", "cost_bw", "cost_total", "gb_provider", "gb_region", "replicas_final"},
                "scenario");

        // P1
        System.out.println("P1 : protocole de validation (entraînement / validation / test sur des charges distinctes)");
        ForecastValidation fv = new ForecastValidation(c);
        fv.run();
        Lstm lstm = fv.best;
        try (ObjectOutputStream o = new ObjectOutputStream(Files.newOutputStream(res.resolve("lstm_p1.ser")))) {
            o.writeObject(lstm);
        }
        Csv.writeMaps(res.resolve("p1_lstm_training.csv"), fv.training);
        Csv.writeMaps(res.resolve("p1_lstm_inits.csv"), fv.inits);
        Csv.writeMaps(res.resolve("p1_validation_metrics.csv"), fv.metrics);
        Csv.writeMaps(res.resolve("p1_validation_bootstrap.csv"), fv.bootstrap);
        Csv.writeMaps(res.resolve("p1_validation_info.csv"), List.of(fv.info));
        List<Object[]> ur = new ArrayList<>();
        fv.usage.forEach((k, v) -> ur.add(new Object[]{k, v}));
        Csv.writeRows(res.resolve("p1_method_usage.csv"), new String[]{"method", "count"}, ur);
        charts.forecastValidation(fv);
        for (Map<String, Object> m : fv.metrics) System.out.printf("  %-24s MAE = %.3f  RMSE = %.3f%n", m.get("method"), m.get("mae"), m.get("rmse"));
        System.out.printf("  LSTM sur %d initialisations : MAE %.3f ± %.3f ; EMA α réglé = %.2f%n", fv.inits.size(),
                fv.initMaeMean, fv.initMaeSd, fv.bestAlpha);

        // S3
        System.out.println("S3 : scénario dynamique (" + seeds + " graines)");
        List<Variant> variants = ex.dynamicVariants(lstm);
        List<Map<String, Object>> s3 = new ArrayList<>();
        List<Map<String, Object>> daily = new ArrayList<>();
        List<Map<String, Object>> outcomes = new ArrayList<>();
        List<Result> s3seed0 = null;
        for (int seed = 0; seed < seeds; seed++) {
            DynamicRun dr = ex.runDynamic(seed, S3Params.base(c), variants);
            for (Result r : dr.results) {
                s3.add(r.summary);
                for (int d = 0; d < r.mgr.dayLog.size(); d++) {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("seed", seed); m.put("strategy", r.label); m.put("day", d + 1);
                    m.put("spend_usd", r.mgr.dayLog.get(d)); m.put("budget_usd", dr.budget);
                    m.put("over_budget", r.mgr.dayLog.get(d) > dr.budget);
                    daily.add(m);
                }
                for (double[] o : r.mgr.replicaOutcomes) {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("seed", seed); m.put("strategy", r.label); m.put("created_h", o[0]); m.put("end_h", o[1]);
                    m.put("create_cost", o[2]); m.put("net_gain", o[3]);
                    m.put("payback_h", Double.isNaN(o[4]) ? "" : o[4]); m.put("deleted", (int) o[5]);
                    outcomes.add(m);
                }
            }
            if (seed == 0) {
                s3seed0 = dr.results;
                List<Map<String, Object>> sl = new ArrayList<>();
                dr.sla.forEach((k, v) -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("kind", k); m.put("TSLA_ms", v[0]); m.put("CSLA_usd", v[1]); m.put("budget_day", dr.budget);
                    sl.add(m);
                });
                Csv.writeMaps(res.resolve("s3_sla_seed0.csv"), sl);
            }
            System.out.printf("  graine %d terminée (%.0f s)%n", seed, (System.currentTimeMillis() - t0) / 1000.0);
        }
        Csv.writeMaps(res.resolve("s3_summary_all_seeds.csv"), s3);
        Csv.writeMaps(res.resolve("s3_budget_daily.csv"), daily);
        Csv.writeMaps(res.resolve("s3_replica_outcomes.csv"), outcomes);
        List<Object[]> q3 = new ArrayList<>();
        List<Map<String, Object>> ev = new ArrayList<>();
        for (Result r : s3seed0) {
            for (Object[] row : r.rows) q3.add(concat(row, "S3", r.label, r.mgr.extraCost.getOrDefault((Integer) row[0], 0.0)));
            for (Map<String, Object> e : r.mgr.events) {
                Map<String, Object> m = new LinkedHashMap<>(e);
                m.put("strategy", r.label);
                ev.add(m);
            }
        }
        Csv.writeRows(res.resolve("s3_queries_seed0.csv"), header("scenario", "strategy", "replica"), q3);
        Csv.writeMaps(res.resolve("s3_replica_events_seed0.csv"), ev);
        charts.s3Timeseries(s3seed0, c.integer("s3.priceChangePeriod"));
        charts.s3ArticleFigures(s3seed0, c.integer("s3.priceChangePeriod") * c.integer("s3.periodQueries"));
        List<String> order = new ArrayList<>();
        for (Variant v : variants) order.add(v.name());
        charts.s3MultiSeed(s3, order);
        charts.validation(s3, daily, outcomes, s3seed0, order);
        printTable(s3, new String[]{"rt_mean", "rt_p95", "cost_placement", "cost_sync", "cost_replica_create",
                "replicas_created", "thrash", "sla_T_violation_pct"}, null);
        printTable(s3, new String[]{"budget_violation_pct", "overspend_usd", "cost_intercloud", "gb_intercloud",
                "replicas_active_mean", "thrash_rate_pct", "replicas_paid_back_pct", "replica_net_gain_usd"}, null);

        // sensibilités
        System.out.println("S3 : sensibilité au taux d'écriture (" + sensSeeds + " graines)");
        List<Variant> light = new ArrayList<>();
        for (Variant v : variants) {
            if (List.of("NoRepLc", "TCDRM", "Replicate-All", "Predictive sans contraintes", "TCDRM-Predictive").contains(v.name())) light.add(v);
        }
        List<Map<String, Object>> sens = new ArrayList<>();
        for (double wp : new double[]{0.0, 0.15, 0.30, 0.45}) {
            for (int seed = 0; seed < sensSeeds; seed++) {
                for (Result r : ex.runDynamic(seed, new S3Params(wp), light).results) {
                    r.summary.put("sweep", "write");
                    sens.add(r.summary);
                }
            }
        }
        Csv.writeMaps(res.resolve("s3_sensitivity.csv"), sens);
        charts.s3Sensitivity(sens);
        System.out.printf("Terminé en %.0f s — résultats dans %s, figures dans %s%n",
                (System.currentTimeMillis() - t0) / 1000.0, res.toAbsolutePath(), fig.toAbsolutePath());
    }

    static String[] header(String... extra) {
        String[] h = new String[SimulationRunner.COLS.length + extra.length];
        System.arraycopy(SimulationRunner.COLS, 0, h, 0, SimulationRunner.COLS.length);
        System.arraycopy(extra, 0, h, SimulationRunner.COLS.length, extra.length);
        return h;
    }

    static Object[] concat(Object[] row, Object... extra) {
        Object[] o = new Object[row.length + extra.length];
        System.arraycopy(row, 0, o, 0, row.length);
        System.arraycopy(extra, 0, o, row.length, extra.length);
        return o;
    }

    static void printTable(List<Map<String, Object>> rows, String[] metrics, String groupKey) {
        Map<String, Map<String, double[]>> agg = new LinkedHashMap<>();
        for (Map<String, Object> r : rows) {
            String g = (groupKey == null ? "" : r.get(groupKey) + " ") + r.get("strategy");
            Map<String, double[]> m = agg.computeIfAbsent(g, k -> new LinkedHashMap<>());
            for (String k : metrics) {
                double[] a = m.computeIfAbsent(k, kk -> new double[2]);
                if (r.get(k) instanceof Number num) {
                    a[0] += num.doubleValue();
                    a[1]++;
                }
            }
        }
        StringBuilder h = new StringBuilder(String.format("  %-26s", ""));
        for (String k : metrics) h.append(String.format("%14s", k));
        System.out.println(h);
        agg.forEach((g, m) -> {
            StringBuilder sb = new StringBuilder(String.format("  %-26s", g));
            for (String k : metrics) sb.append(String.format("%14.2f", m.get(k)[0] / m.get(k)[1]));
            System.out.println(sb);
        });
    }
}
