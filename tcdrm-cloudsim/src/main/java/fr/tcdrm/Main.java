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
import fr.tcdrm.workload.Workloads;
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

/** Lance S1 à S5 puis écrit results/*.csv et figures/*.png. */
public final class Main {

    public static void main(String[] args) throws Exception {
        System.setOut(new PrintStream(System.out, true, StandardCharsets.UTF_8));
        System.setProperty("java.awt.headless", "true");
        Path out = Path.of(args.length > 0 ? args[0] : ".");
        Path res = out.resolve("results"), fig = out.resolve("figures");
        Files.createDirectories(res);
        SimConfig c = SimConfig.load();
        Experiments ex = new Experiments(c);
        // S3 : scénario de l'article aux prix publics des fournisseurs (clés real.*) à la place de la Table 1
        SimConfig c3 = c.withPrefix("real.");
        Experiments ex3 = new Experiments(c3);
        // S4 : prix publics + requêtes aléatoires (et réglages s4.* de P1 / P2 pour 20 périodes)
        SimConfig c4 = c3.withPrefix("s4.");
        Experiments ex4 = new Experiments(c4);
        Charts charts = new Charts(fig);
        int s4Periods = (int) Math.ceil(c.integer("s4.queries") / c.dbl("s4.periodQueries"));
        charts.setTimeScale(s4Periods, c.integer("s4.cycle"), c.integer("s4.periodQueries"));
        int seeds = c.integer("seeds"), sensSeeds = c.integer("sensitivity.seeds");
        long t0 = System.currentTimeMillis();

        // S1 + S2
        System.out.println("S1/S2 : scénario de l'article (" + seeds + " graines)");
        List<Map<String, Object>> s12 = new ArrayList<>();
        Map<String, List<Result>> seed0 = new HashMap<>();
        List<Object[]> q0 = new ArrayList<>();
        // réplicas créés par site, stratégie et type de requête (somme sur les graines)
        Map<String, Integer> sites = new LinkedHashMap<>();
        for (String kind : new String[]{"simple", "complex"}) {
            for (int seed = 0; seed < seeds; seed++) {
                List<Result> rs = ex.runArticle(kind, seed);
                for (Result r : rs) s12.add(r.summary);
                for (Result r : rs) {
                    for (Map<String, Object> e : r.mgr.events) {
                        if ("create".equals(e.get("type"))) sites.merge(kind + "|" + r.label + "|" + e.get("site"), 1, Integer::sum);
                    }
                }
                if (seed == 0) {
                    seed0.put(kind, rs);
                    for (Result r : rs) for (Object[] row : r.rows) q0.add(concat(row, kind, r.label,
                            r.mgr.extraCost.getOrDefault((Integer) row[0], 0.0)));
                }
            }
        }
        Csv.writeMaps(res.resolve("s1s2_summary_all_seeds.csv"), s12);
        List<Map<String, Object>> siteRows = new ArrayList<>();
        sites.forEach((k, v) -> {
            String[] p = k.split("\\|");
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("scenario", p[0]); m.put("strategy", p[1]); m.put("site", p[2]);
            m.put("replicas_mean_per_seed", v / (double) seeds);
            siteRows.add(m);
        });
        Csv.writeMaps(res.resolve("s2_replica_sites.csv"), siteRows);
        charts.s2ReplicaSites(siteRows, new java.util.ArrayList<>(new fr.tcdrm.cloud.MultiCloud(c).siteNames()));
        Csv.writeRows(res.resolve("s1s2_queries_seed0.csv"), header("scenario", "strategy", "replica"), q0);
        charts.articleFigures(seed0, "s1_", new String[]{"NoRepLc", "TCDRM"});
        charts.articleFigures(seed0, "s2_", new String[]{"NoRepLc", "TCDRM", "TCDRM-Predictive"});
        charts.s2MultiSeed(s12);
        // sensibilité de S2 au seuil de popularité PSLA (paramètre clé de l'article)
        System.out.println("S2 : sensibilité à PSLA (" + sensSeeds + " graines)");
        List<Map<String, Object>> s2sens = new ArrayList<>();
        for (int psla : new int[]{50, 100, 200, 400}) {
            Experiments e2 = new Experiments(c.with(Map.of("psla", String.valueOf(psla))));
            for (String kind : new String[]{"simple", "complex"}) {
                for (int seed = 0; seed < sensSeeds; seed++) {
                    for (Result r : e2.runArticle(kind, seed)) {
                        r.summary.put("psla", psla);
                        s2sens.add(r.summary);
                    }
                }
            }
        }
        Csv.writeMaps(res.resolve("s2_sensitivity_psla.csv"), s2sens);
        charts.s2Sensitivity(s2sens);
        printTable(s12, new String[]{"rt_mean", "cost_bw", "cost_total", "gb_provider", "gb_region", "replicas_final"},
                "scenario");

        // S3 : scénario de l'article (S2) aux prix publics ; comparaison avec la Table 1 et le CSLA de la Table 1
        System.out.println("S3 : scénario de l'article aux prix publics (" + seeds + " graines)");
        Csv.writeMaps(res.resolve("s3_prices.csv"), priceTable(c, c3));
        List<String> siteOrder = new ArrayList<>(new fr.tcdrm.cloud.MultiCloud(c3).siteNames());
        Map<String, String> t1sla = new LinkedHashMap<>();
        for (String k : new String[]{"simple", "complex", "write"}) t1sla.put("sla." + k + ".CSLA", c.str("sla." + k + ".CSLA"));
        Object[][] priceCfgs = {{Charts.PRICE_CFG[1], new Experiments(c3.with(t1sla))}, {Charts.PRICE_CFG[2], ex3}};
        List<Map<String, Object>> s3 = new ArrayList<>(), cmp = new ArrayList<>();
        Map<String, List<Result>> s3seed0 = new HashMap<>();
        Map<String, Integer> s3sites = new LinkedHashMap<>();
        for (Map<String, Object> r : s12) cmp.add(withPct(r, s12, Charts.PRICE_CFG[0]));
        for (Object[] pc : priceCfgs) {
            String name = (String) pc[0];
            Experiments e = (Experiments) pc[1];
            List<Map<String, Object>> rows = new ArrayList<>();
            for (String kind : new String[]{"simple", "complex"}) {
                for (int seed = 0; seed < seeds; seed++) {
                    List<Result> rs = e.runArticle(kind, seed);
                    for (Result r : rs) {
                        rows.add(r.summary);
                        if (e == ex3) {
                            for (Map<String, Object> ev : r.mgr.events) {
                                if ("create".equals(ev.get("type"))) s3sites.merge(kind + "|" + r.label + "|" + ev.get("site"), 1, Integer::sum);
                            }
                        }
                    }
                    if (e == ex3 && seed == 0) s3seed0.put(kind, rs);
                }
            }
            for (Map<String, Object> r : rows) cmp.add(withPct(r, rows, name));
            if (e == ex3) s3.addAll(rows);
        }
        Csv.writeMaps(res.resolve("s3_summary_all_seeds.csv"), s3);
        Csv.writeMaps(res.resolve("s3_price_comparison.csv"), cmp);
        List<Map<String, Object>> s3siteRows = new ArrayList<>();
        s3sites.forEach((k, v) -> {
            String[] p = k.split("\\|");
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("scenario", p[0]); m.put("strategy", p[1]); m.put("site", p[2]);
            m.put("replicas_mean_per_seed", v / (double) seeds);
            s3siteRows.add(m);
        });
        Csv.writeMaps(res.resolve("s3_replica_sites.csv"), s3siteRows);
        charts.articleFigures(s3seed0, "s3_", new String[]{"NoRepLc", "TCDRM", "TCDRM-Predictive"});
        charts.articleMultiSeed(s3, "s3", "S3");
        charts.articleReplicaSites(s3siteRows, siteOrder, "s3", "S3");
        charts.s3PriceComparison(cmp);
        printTable(cmp, new String[]{"rt_mean", "cost_placement", "cost_pct_of_norep", "replicas_created",
                "sla_T_violation_pct", "sla_C_violation_pct"}, "prices");
        printTable(s3, new String[]{"rt_mean", "cost_bw", "cost_total", "gb_provider", "gb_region", "replicas_final"},
                "scenario");

        // P1 (charges S4)
        System.out.println("P1 : protocole de validation (entraînement / validation / test sur des charges S4 distinctes)");
        ForecastValidation fv = new ForecastValidation(c4);
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

        // S4 : prix publics et requêtes aléatoires
        int dynSeeds = c.integer("dyn.seeds");
        DynResult d4 = dynamicScenario("s4", "S4", c4, lstm, dynSeeds, res, charts, siteOrder, -1, t0);
        List<Variant> variants = d4.variants;

        // sensibilités (S4)
        System.out.println("S4 : sensibilité au taux d'écriture (" + sensSeeds + " graines)");
        List<Variant> light = new ArrayList<>();
        for (Variant v : variants) {
            if (List.of("NoRepLc", "TCDRM", "Replicate-All", "Predictive sans contraintes", "TCDRM-Predictive").contains(v.name())) light.add(v);
        }
        List<Map<String, Object>> sens = new ArrayList<>();
        for (double wp : new double[]{0.0, 0.15, 0.30, 0.45}) {
            for (int seed = 0; seed < sensSeeds; seed++) {
                for (Result r : ex4.runDynamic(seed, new S3Params(wp), light).results) {
                    r.summary.put("sweep", "write");
                    sens.add(r.summary);
                }
            }
        }
        Csv.writeMaps(res.resolve("s4_sensitivity.csv"), sens);
        charts.s3Sensitivity(sens);

        // améliorations v2 : U seul, poids dynamiques, saturation, anti-thrashing (S4)
        System.out.println("v2 : améliorations de TCDRM-Predictive sur S4 (" + dynSeeds + " graines)");
        List<Map<String, Object>> v2 = new ArrayList<>();
        for (int seed = 0; seed < dynSeeds; seed++) {
            for (Result r : ex4.runDynamic(seed, S3Params.base(c4), ex4.improvementVariants(lstm)).results) v2.add(r.summary);
        }
        Csv.writeMaps(res.resolve("v2_summary_all_seeds.csv"), v2);
        charts.v2Figures(v2);
        printTable(v2, new String[]{"rt_mean", "cost_placement", "sla_T_violation_pct", "thrash", "replicas_created",
                "max_site_load", "sites_used", "w_cost_mean", "decision_us_mean"}, null);

        // scalabilité de la décision de placement
        List<Map<String, Object>> sc = scalability(c4, lstm);
        Csv.writeMaps(res.resolve("v2_scalability.csv"), sc);
        charts.v2Scalability(sc);
        // S5 : S4 + variation des prix + charge non stationnaire (clés s5.* qui remplacent les clés s4.*)
        Map<String, String> o5 = new LinkedHashMap<>();
        for (String k : c.keys()) if (k.startsWith("s5.")) o5.put("s4." + k.substring(3), c.str(k));
        SimConfig c5 = c4.with(o5);
        System.out.println("P1 sur S5 : protocole de validation (charges S5 distinctes)");
        ForecastValidation fv5 = new ForecastValidation(c5);
        fv5.run();
        Lstm lstm5 = fv5.best;
        Csv.writeMaps(res.resolve("p1_s5_validation_metrics.csv"), fv5.metrics);
        Csv.writeMaps(res.resolve("p1_s5_validation_bootstrap.csv"), fv5.bootstrap);
        Csv.writeMaps(res.resolve("p1_s5_validation_info.csv"), List.of(fv5.info));
        List<Object[]> ur5 = new ArrayList<>();
        fv5.usage.forEach((k, v) -> ur5.add(new Object[]{k, v}));
        Csv.writeRows(res.resolve("p1_s5_method_usage.csv"), new String[]{"method", "count"}, ur5);
        charts.setP1File("p1_validation_s5");
        charts.forecastValidation(fv5);
        for (Map<String, Object> m : fv5.metrics) System.out.printf("  %-24s MAE = %.3f  RMSE = %.3f%n", m.get("method"), m.get("mae"), m.get("rmse"));
        int price5 = c5.integer("s4.priceChangePeriod");
        DynResult d5 = dynamicScenario("s5", "S5", c5, lstm5, dynSeeds, res, charts, siteOrder, price5, t0);
        charts.s5Workload(d5.w0, c5, price5, 0);
        System.out.println("v2 sur S5 (" + dynSeeds + " graines)");
        Experiments ex5 = new Experiments(c5);
        List<Map<String, Object>> v25 = new ArrayList<>();
        for (int seed = 0; seed < dynSeeds; seed++) {
            for (Result r : ex5.runDynamic(seed, S3Params.base(c5), ex5.improvementVariants(lstm5)).results) v25.add(r.summary);
        }
        Csv.writeMaps(res.resolve("s5_v2_summary_all_seeds.csv"), v25);
        charts.s5V2(v25);
        printTable(v25, new String[]{"rt_mean", "cost_placement", "sla_T_violation_pct", "thrash", "replicas_created",
                "replicas_deleted", "retained", "w_cost_mean"}, null);

        // sensibilité aux choix de modélisation : facteur du CSLA et échelle des latences (S3 et S5)
        System.out.println("Sensibilité : facteur du CSLA et échelle des latences (" + sensSeeds + " graines)");
        List<Map<String, Object>> ms = new ArrayList<>();
        double[] cslaF = {5, 7, 10}, latF = {0.5, 1, 2};
        for (double f : cslaF) {
            Map<String, String> o = new LinkedHashMap<>();
            for (String k : new String[]{"simple", "complex", "write"}) o.put("sla." + k + ".CSLA", String.valueOf(c.dbl("sla." + k + ".CSLA") * f));
            ms.addAll(sensitivityRuns(c3.with(o), c5.with(o), lstm5, "csla", f, sensSeeds));
        }
        for (double f : latF) {
            Map<String, String> o = new LinkedHashMap<>();
            for (String k : c.keys()) if (k.startsWith("geo.") || k.startsWith("lat.")) o.put(k, String.valueOf(c.dbl(k) * f));
            ms.addAll(sensitivityRuns(c3.with(o), c5.with(o), lstm5, "latency", f, sensSeeds));
        }
        Csv.writeMaps(res.resolve("sensitivity_models.csv"), ms);
        charts.modelSensitivity(ms, "csla", "sensitivity_csla", "Sensibilité au facteur du CSLA (prix publics, moyenne sur " + sensSeeds + " graines)",
                "CSLA = CSLA de la Table 1 × facteur", cslaF);
        charts.modelSensitivity(ms, "latency", "sensitivity_latency", "Sensibilité à l'échelle des latences (moyenne sur " + sensSeeds + " graines)",
                "Latences × facteur", latF);
        printTable(ms, new String[]{"rt_mean", "cost_pct_of_norep"}, "sweep");

        System.out.printf("Terminé en %.0f s — résultats dans %s, figures dans %s%n",
                (System.currentTimeMillis() - t0) / 1000.0, res.toAbsolutePath(), fig.toAbsolutePath());
    }

    /** S3 (article, simple et complexe) et S5 avec une configuration modifiée ; coût en % de NoRepLc. */
    static List<Map<String, Object>> sensitivityRuns(SimConfig s3c, SimConfig s5c, Lstm lstm, String sweep, double f, int seeds) {
        List<Map<String, Object>> out = new ArrayList<>();
        List<String> keep = List.of("NoRepLc", "TCDRM", "Replicate-All", "TCDRM-Predictive");
        Experiments e3 = new Experiments(s3c), e5 = new Experiments(s5c);
        List<Variant> v5 = new ArrayList<>();
        for (Variant v : e5.dynamicVariants(lstm)) if (keep.contains(v.name())) v5.add(v);
        for (int seed = 0; seed < seeds; seed++) {
            List<Map<String, Object>> rows = new ArrayList<>();
            for (String kind : new String[]{"simple", "complex"}) {
                for (Result r : e3.runArticle(kind, seed)) { r.summary.put("scen", "S3"); rows.add(r.summary); }
            }
            for (Result r : e5.runDynamic(seed, S3Params.base(s5c), v5).results) {
                r.summary.put("scen", "S5");
                r.summary.put("scenario", "simple");
                rows.add(r.summary);
            }
            for (Map<String, Object> r : rows) {
                if (!keep.contains((String) r.get("strategy"))) continue;
                double base = Double.NaN;
                for (Map<String, Object> b : rows) {
                    if ("NoRepLc".equals(b.get("strategy")) && b.get("scen").equals(r.get("scen")) && b.get("scenario").equals(r.get("scenario"))) {
                        base = ((Number) b.get("cost_placement")).doubleValue();
                    }
                }
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("sweep", sweep); m.put("factor", f); m.put("scen", r.get("scen"));
                m.putAll(r);
                m.put("cost_pct_of_norep", 100 * ((Number) r.get("cost_placement")).doubleValue() / base);
                out.add(m);
            }
        }
        return out;
    }

    /** Résultat d'un scénario à requêtes aléatoires (S4 ou S5). */
    static final class DynResult {
        List<Map<String, Object>> summaries;
        List<Variant> variants;
        Workloads.Workload w0, lifeW;
        int lifeSeed;
    }

    /** S4 / S5 : exécution sur toutes les graines, CSV et figures (préfixe file, nom name). */
    static DynResult dynamicScenario(String file, String name, SimConfig c4, Lstm lstm, int seeds, Path res,
                                     Charts charts, List<String> siteOrder, int priceHour, long t0) throws Exception {
        System.out.println(name + " : prix publics, requêtes aléatoires (" + seeds + " graines)");
        Experiments ex4 = new Experiments(c4);
        charts.setDynamic(file, name);
        charts.setTimeScale((int) Math.ceil(c4.integer("s4.queries") / c4.dbl("s4.periodQueries")), c4.integer("s4.cycle"), c4.integer("s4.periodQueries"));
        List<Variant> variants = ex4.dynamicVariants(lstm);
        List<Map<String, Object>> s4 = new ArrayList<>();
        List<Map<String, Object>> daily = new ArrayList<>();
        List<Map<String, Object>> outcomes = new ArrayList<>();
        Map<String, double[]> relAcc = new LinkedHashMap<>();
        Map<String, Integer> s4sites = new LinkedHashMap<>();
        List<Result> s4seed0 = null, lifeRes = null;
        Workloads.Workload lifeW = null, s4w0 = null;
        int lifeSeed = 0;
        List<Map<String, Object>> templates = new ArrayList<>();
        for (int seed = 0; seed < seeds; seed++) {
            DynamicRun dr = ex4.runDynamic(seed, S3Params.base(c4), variants);
            for (Result r : dr.results) {
                s4.add(r.summary);
                relationStats(dr.workload, r, name, relAcc, s4sites);
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
            templateRows(c4, dr.workload, seed, templates);
            // graine montrée pour le cycle de vie : la première où TCDRM-Predictive réplique une relation rare
            if (lifeRes == null) {
                for (Result r : dr.results) {
                    if (!r.label.equals("TCDRM-Predictive")) continue;
                    for (Map<String, Object> e : r.mgr.events) {
                        if ("rare".equals(dr.workload.roles.get((Integer) e.get("rel")))) { lifeRes = dr.results; lifeW = dr.workload; lifeSeed = seed; break; }
                    }
                }
            }
            if (seed == 0) {
                s4seed0 = dr.results;
                s4w0 = dr.workload;
                List<Map<String, Object>> sl = new ArrayList<>();
                dr.sla.forEach((k, v) -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("kind", k); m.put("TSLA_ms", v[0]); m.put("CSLA_usd", v[1]); m.put("budget_cycle", dr.budget);
                    sl.add(m);
                });
                Csv.writeMaps(res.resolve(file + "_sla_seed0.csv"), sl);
                List<Object[]> q4 = new ArrayList<>();
                List<Map<String, Object>> ev = new ArrayList<>();
                for (Result r : dr.results) {
                    for (Object[] row : r.rows) q4.add(concat(row, name, r.label, r.mgr.extraCost.getOrDefault((Integer) row[0], 0.0)));
                    for (Map<String, Object> e : r.mgr.events) {
                        Map<String, Object> m = new LinkedHashMap<>(e);
                        m.put("relation", Workloads.fLabel(c4, (Integer) e.get("rel")));
                        m.put("role", dr.workload.roles.get((Integer) e.get("rel")));
                        m.put("strategy", r.label);
                        ev.add(m);
                    }
                }
                Csv.writeRows(res.resolve(file + "_queries_seed0.csv"), header("scenario", "strategy", "replica"), q4);
                Csv.writeMaps(res.resolve(file + "_replica_events_seed0.csv"), ev);
            }
        }
        if (lifeRes == null) { lifeRes = s4seed0; lifeW = s4w0; }
        System.out.printf("  %s terminé (%.0f s) ; cycle de vie montré : graine %d%n", name, (System.currentTimeMillis() - t0) / 1000.0, lifeSeed);
        Csv.writeMaps(res.resolve(file + "_summary_all_seeds.csv"), s4);
        Csv.writeMaps(res.resolve(file + "_templates.csv"), templates);
        Csv.writeMaps(res.resolve(file + "_budget_cycles.csv"), daily);
        Csv.writeMaps(res.resolve(file + "_replica_outcomes.csv"), outcomes);
        List<Map<String, Object>> relRows = relationRows(relAcc, seeds);
        Csv.writeMaps(res.resolve(file + "_relations.csv"), relRows);
        List<Map<String, Object>> s4siteRows = siteRows(s4sites, seeds);
        Csv.writeMaps(res.resolve(file + "_replica_sites.csv"), s4siteRows);
        charts.s3Timeseries(s4seed0, priceHour);
        charts.s3ArticleFigures(s4seed0, priceHour > 0 ? priceHour * c4.integer("s4.periodQueries") : -1);
        List<String> order = new ArrayList<>();
        for (Variant v : variants) order.add(v.name());
        charts.s3MultiSeed(s4, order);
        charts.validation(s4, daily, outcomes, s4seed0, order);
        charts.s4ReplicaSites(s4siteRows, siteOrder);
        charts.s4Relations(relRows, lifeRes, lifeSeed, c4, lifeW);
        printTable(s4, new String[]{"rt_mean", "rt_p95", "cost_placement", "cost_sync", "cost_replica_create",
                "replicas_created", "replicas_deleted", "sla_T_violation_pct"}, null);
        printTable(s4, new String[]{"budget_violation_pct", "overspend_usd", "cost_intercloud", "gb_intercloud",
                "replicas_active_mean", "thrash", "replicas_paid_back_pct", "sla_C_violation_pct"}, null);

        DynResult out = new DynResult();
        out.summaries = s4; out.variants = variants; out.w0 = s4w0; out.lifeW = lifeW; out.lifeSeed = lifeSeed;
        return out;
    }

    /** Une dimension à la fois : sites (fournisseurs), nombre de requêtes. */
    static List<Map<String, Object>> scalability(SimConfig c, Lstm lstm) {
        int seeds = c.integer("v2.scal.seeds");
        List<Map<String, Object>> out = new ArrayList<>();
        Object[][] grid = {
                {"sites", new int[]{3, 6, 9, 12}}, {"queries", new int[]{1000, 2000, 4000}}};
        // préchauffage de la JVM (non mesuré)
        List<Variant> warm = new ArrayList<>();
        for (Variant vr : new Experiments(c).dynamicVariants(lstm)) {
            if (vr.name().equals("TCDRM") || vr.name().equals("TCDRM-Predictive")) warm.add(vr);
        }
        new Experiments(c).runDynamic(99, S3Params.base(c), warm);
        for (Object[] g : grid) {
            String axis = (String) g[0];
            for (int v : (int[]) g[1]) {
                Map<String, String> o = new LinkedHashMap<>();
                switch (axis) {
                    case "sites" -> o.putAll(Experiments.extraProviders(c, v));
                    default -> o.put("s4.queries", String.valueOf(v));
                }
                SimConfig c2 = c.with(o);
                Experiments e2 = new Experiments(c2);
                List<Variant> vs = new ArrayList<>();
                for (Variant vr : e2.dynamicVariants(lstm)) {
                    if (vr.name().equals("TCDRM") || vr.name().equals("TCDRM-Predictive")) vs.add(vr);
                }
                for (int seed = 0; seed < seeds; seed++) {
                    for (Result r : e2.runDynamic(seed, S3Params.base(c2), vs).results) {
                        Map<String, Object> m = new LinkedHashMap<>();
                        m.put("axis", axis);
                        m.put("axis_value", axis.equals("sites") ? v * c2.regions.size() : v);
                        m.put("sites", c2.nSites());
                                m.put("queries", c2.integer("s4.queries"));
                        m.putAll(r.summary);
                        out.add(m);
                    }
                }
                System.out.printf("  scalabilité %s = %d terminée%n", axis, v);
            }
        }
        return out;
    }

    /** Réplicas créés, supprimés et actifs en fin de simulation, par relation F et par site. */
    static void relationStats(Workloads.Workload w, Result r, String prices, Map<String, double[]> rel, Map<String, Integer> sites) {
        Map<String, Integer> active = new HashMap<>();
        for (String role : Workloads.ROLES) rel.computeIfAbsent(prices + "|" + r.label + "|" + role, kk -> new double[3]);
        for (Map<String, Object> e : r.mgr.events) {
            String f = w.roles.getOrDefault((Integer) e.get("rel"), "autre");
            String k = prices + "|" + r.label + "|" + f;
            double[] a = rel.computeIfAbsent(k, kk -> new double[3]);
            if ("create".equals(e.get("type"))) {
                a[0]++;
                active.merge(f, 1, Integer::sum);
                sites.merge(prices + "|" + r.label + "|" + e.get("site"), 1, Integer::sum);
            } else if ("delete".equals(e.get("type"))) {
                a[1]++;
                active.merge(f, -1, Integer::sum);
            }
        }
        active.forEach((f, v) -> rel.get(prices + "|" + r.label + "|" + f)[2] += v);
    }

    /** Copie d'un résumé avec le jeu de prix et le coût en % de NoRepLc (même scénario, même graine). */
    static Map<String, Object> withPct(Map<String, Object> r, List<Map<String, Object>> all, String prices) {
        double base = Double.NaN;
        for (Map<String, Object> b : all) {
            if ("NoRepLc".equals(b.get("strategy")) && b.get("scenario").equals(r.get("scenario")) && b.get("seed").equals(r.get("seed"))) {
                base = ((Number) b.get("cost_placement")).doubleValue();
            }
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("prices", prices);
        m.putAll(r);
        m.put("cost_pct_of_norep", 100 * ((Number) r.get("cost_placement")).doubleValue() / base);
        return m;
    }

    static List<Map<String, Object>> relationRows(Map<String, double[]> acc, int seeds) {
        List<Map<String, Object>> out = new ArrayList<>();
        acc.forEach((k, a) -> {
            String[] p = k.split("\\|");
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("prices", p[0]); m.put("strategy", p[1]); m.put("role", p[2]);
            m.put("created_mean", a[0] / seeds); m.put("deleted_mean", a[1] / seeds); m.put("active_end_mean", a[2] / seeds);
            out.add(m);
        });
        return out;
    }

    /** Modèles de requête tirés pour une graine (relations F, rôles, part de base, rafale). */
    static void templateRows(SimConfig c, Workloads.Workload w, int seed, List<Map<String, Object>> out) {
        for (int j = 0; j < w.templates.length; j++) {
            StringBuilder rels = new StringBuilder(), roles = new StringBuilder();
            for (int r : w.templates[j]) {
                rels.append(rels.length() > 0 ? " " : "").append(Workloads.fLabel(c, r));
                roles.append(roles.length() > 0 ? " " : "").append(w.roles.get(r));
            }
            long n = w.queries.stream().filter(q -> !q.isWrite()).count();
            final int jj = j;
            long nj = w.queries.stream().filter(q -> !q.isWrite() && q.template == jj).count();
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("seed", seed); m.put("template", "Q" + (j + 1)); m.put("relations", rels.toString()); m.put("roles", roles.toString());
            m.put("base_share", w.templateShare[j]); m.put("read_share", (double) nj / n);
            m.put("rare", j == w.rareTemplate); m.put("burst_periods", j == w.rareTemplate ? w.burstStart + "-" + (w.burstEnd - 1) : "");
            out.add(m);
        }
    }

    static List<Map<String, Object>> siteRows(Map<String, Integer> acc, int seeds) {
        List<Map<String, Object>> out = new ArrayList<>();
        acc.forEach((k, v) -> {
            String[] p = k.split("\\|");
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("prices", p[0]); m.put("strategy", p[1]); m.put("site", p[2]);
            m.put("replicas_mean_per_seed", v / (double) seeds);
            out.add(m);
        });
        return out;
    }

    /** Prix de la Table 1 et prix publics, par site (pour le rapport). */
    static List<Map<String, Object>> priceTable(SimConfig c, SimConfig c3) {
        fr.tcdrm.cloud.MultiCloud a = new fr.tcdrm.cloud.MultiCloud(c), b = new fr.tcdrm.cloud.MultiCloud(c3);
        List<Map<String, Object>> out = new ArrayList<>();
        for (int i = 0; i < a.n; i++) {
            double ra = 0, rb = 0, rbMax = 0;
            for (int j = 0; j < a.n; j++) {
                if (j == i || !a.name(j).split("-")[0].equals(a.name(i).split("-")[0])) continue;
                ra = a.interRegionPrice(i, j);
                rb = Math.min(rb == 0 ? 1 : rb, b.interRegionPrice(i, j));
                rbMax = Math.max(rbMax, b.interRegionPrice(i, j));
            }
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("site", a.name(i));
            m.put("cpu_table1", a.cpu[i]); m.put("cpu_public", b.cpu[i]);
            m.put("io_table1", a.io[i]); m.put("io_public", b.io[i]);
            m.put("storage_table1", a.storagePrice(i)); m.put("storage_public", b.storagePrice(i));
            m.put("intra_table1", a.intra[i]); m.put("intra_public", b.intra[i]);
            m.put("inter_region_table1", ra); m.put("inter_region_public_min", rb); m.put("inter_region_public_max", rbMax);
            m.put("inter_provider_table1", a.interProviderPrice(i)); m.put("inter_provider_public", b.interProviderPrice(i));
            m.put("exec_table1", a.execOf(a.reg(i)) == i); m.put("exec_public", b.execOf(b.reg(i)) == i);
            out.add(m);
        }
        return out;
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
