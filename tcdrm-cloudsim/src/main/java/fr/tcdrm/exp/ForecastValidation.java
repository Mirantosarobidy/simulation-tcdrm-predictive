package fr.tcdrm.exp;

import fr.tcdrm.config.SimConfig;
import fr.tcdrm.forecast.Lstm;
import fr.tcdrm.forecast.PopularityForecaster;
import fr.tcdrm.workload.Workloads;
import fr.tcdrm.workload.Workloads.S3Params;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.stream.IntStream;

/**
 * Validation de P1 : entraînement, validation et test sur des charges S3 de graines distinctes.
 * LSTM comparé à dernière valeur, EMA, régression, naïf saisonnier et prévision nulle (MAE, RMSE, bootstrap apparié).
 */
public final class ForecastValidation {

    public static final String[] METHODS = {"LSTM", "P1 (sélection auto)", "EMA α réglé", "EMA α = 0,3",
            "Dernière valeur", "Régression 12 périodes", "Même phase, cycle précédent", "Prévision nulle"};

    public final SimConfig c;
    public Lstm best;
    public double bestAlpha;
    public final List<Map<String, Object>> inits = new ArrayList<>();
    public final List<Map<String, Object>> training = new ArrayList<>();
    public final List<Map<String, Object>> metrics = new ArrayList<>();
    public final List<Map<String, Object>> bootstrap = new ArrayList<>();
    public final Map<String, Integer> usage = new LinkedHashMap<>();
    public double initMaeMean, initMaeSd;

    public ForecastValidation(SimConfig c) { this.c = c; }

    private static int[] ints(SimConfig c, String k) {
        return Arrays.stream(c.str(k).split(",")).mapToInt(s -> Integer.parseInt(s.trim())).toArray();
    }

    private float[][][] week(int seed) {
        return Workloads.accessCounts(c, Workloads.dynamic(c, seed, S3Params.base(c)));
    }

    private static List<double[]> series(float[][][] counts) {
        List<double[]> out = new ArrayList<>();
        int T = counts[0].length;
        for (float[][] rel : counts) {
            for (int r = 0; r < 3; r++) {
                double[] s = new double[T];
                double tot = 0;
                for (int t = 0; t < T; t++) { s[t] = rel[t][r]; tot += s[t]; }
                if (tot > 0) out.add(s);
            }
        }
        return out;
    }

    public void run() {
        int W = c.integer("p1.window"), tau = c.integer("p2.horizon"), cycle = c.integer("s3.cycle");
        int[] trainSeeds = ints(c, "p1.trainSeeds"), testSeeds = ints(c, "p1.testSeeds");
        int valSeed = c.integer("p1.valSeed"), nInits = c.integer("p1.inits");

        // jeux d'entraînement et de validation (semaines distinctes)
        List<double[][]> xs = new ArrayList<>();
        List<Double> ys = new ArrayList<>();
        int per = c.integer("p1.lstm.samples") / trainSeeds.length;
        for (int ts : trainSeeds) {
            var set = PopularityForecaster.buildTrainingSet(week(ts), W, tau, cycle, per, ts);
            xs.addAll(Arrays.asList(set.X));
            for (double y : set.Y) ys.add(y);
        }
        double[][][] X = xs.toArray(new double[0][][]);
        double[] Y = ys.stream().mapToDouble(Double::doubleValue).toArray();
        float[][][] valCounts = week(valSeed);
        var vset = PopularityForecaster.buildTrainingSet(valCounts, W, tau, cycle, c.integer("p1.valSamples"), valSeed);

        // plusieurs initialisations, arrêt anticipé (en parallèle : modèles indépendants)
        Lstm[] models = new Lstm[nInits];
        double[][][] hist = new double[nInits][][];
        IntStream.range(0, nInits).parallel().forEach(i -> {
            Lstm m = new Lstm(3, c.integer("p1.lstm.hidden"), i);
            hist[i] = m.fitEarlyStopping(X, Y, vset.X, vset.Y, c.integer("p1.lstm.maxEpochs"),
                    c.integer("p1.lstm.patience"), 128, c.dbl("p1.lstm.lr"), 100 + i);
            models[i] = m;
        });
        int bi = 0;
        double[] valMse = new double[nInits];
        for (int i = 0; i < nInits; i++) {
            valMse[i] = models[i].mse(vset.X, vset.Y);
            if (valMse[i] < valMse[bi]) bi = i;
            for (int e = 0; e < hist[i].length; e++) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("init", i); m.put("epoch", e + 1); m.put("train_mse", hist[i][e][0]); m.put("val_mse", hist[i][e][1]);
                training.add(m);
            }
        }
        best = models[bi];

        // réglage de l'EMA sur la semaine de validation
        List<double[]> valSeries = series(valCounts);
        double bestMae = Double.POSITIVE_INFINITY;
        for (double a = 0.05; a < 0.951; a += 0.05) {
            double t = 0;
            long n = 0;
            for (double[] s : valSeries) {
                for (int e = W; e <= s.length - tau; e += 2) {
                    t += Math.abs(PopularityForecaster.ema(s, e, a) - target(s, e, tau));
                    n++;
                }
            }
            if (t / n < bestMae) { bestMae = t / n; bestAlpha = Math.round(a * 100) / 100.0; }
        }

        // évaluation sur les semaines de test
        PopularityForecaster auto = new PopularityForecaster(c, best, "auto");
        PopularityForecaster[] fInit = new PopularityForecaster[nInits];
        for (int i = 0; i < nInits; i++) fInit[i] = new PopularityForecaster(c, models[i], "LSTM");
        int M = METHODS.length;
        List<double[]> serAbs = new ArrayList<>();     // par série : somme |erreur| par méthode
        List<double[]> serSq = new ArrayList<>();
        List<Integer> serN = new ArrayList<>(), serWeek = new ArrayList<>();
        List<Double> serVol = new ArrayList<>();
        double[] initAbs = new double[nInits];
        long nTot = 0;
        for (int wi = 0; wi < testSeeds.length; wi++) {
            for (double[] s : series(week(testSeeds[wi]))) {
                double[] ab = new double[M], sq = new double[M];
                int n = 0;
                double vol = 0;
                for (int e = W; e <= s.length - tau; e += 2) {
                    double y = target(s, e, tau);
                    double[] p = {
                            auto.lstmPredict(s, e), auto.predictSeries(s, e),
                            PopularityForecaster.ema(s, e, bestAlpha), PopularityForecaster.ema(s, e, 0.3),
                            s[e - 1], PopularityForecaster.regression(s, e, tau), seasonal(s, e, tau, cycle), 0.0};
                    for (int k = 0; k < M; k++) {
                        double d = p[k] - y;
                        ab[k] += Math.abs(d);
                        sq[k] += d * d;
                    }
                    for (int i = 0; i < nInits; i++) initAbs[i] += Math.abs(fInit[i].lstmPredict(s, e) - y);
                    vol += y;
                    n++;
                }
                serAbs.add(ab); serSq.add(sq); serN.add(n); serWeek.add(wi); serVol.add(vol / n);
                nTot += n;
            }
        }
        usage.putAll(auto.used);
        double[] initMae = new double[nInits];
        for (int i = 0; i < nInits; i++) {
            initMae[i] = initAbs[i] / nTot;
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("init", i); m.put("epochs", hist[i].length); m.put("val_mse", valMse[i]);
            m.put("test_mae", initMae[i]); m.put("selected", i == bi);
            inits.add(m);
        }
        initMaeMean = Arrays.stream(initMae).average().orElse(0);
        initMaeSd = Math.sqrt(Arrays.stream(initMae).map(v -> (v - initMaeMean) * (v - initMaeMean)).sum()
                / Math.max(1, nInits - 1));

        // tiers de volume (taux moyen de la série)
        double[] vols = serVol.stream().mapToDouble(Double::doubleValue).sorted().toArray();
        double q1 = vols[vols.length / 3], q2 = vols[2 * vols.length / 3];
        for (int k = 0; k < M; k++) {
            double ab = 0, sq = 0;
            double[] abT = new double[3], abW = new double[testSeeds.length];
            long[] nT = new long[3], nW = new long[testSeeds.length];
            for (int i = 0; i < serAbs.size(); i++) {
                ab += serAbs.get(i)[k];
                sq += serSq.get(i)[k];
                int t = serVol.get(i) <= q1 ? 0 : (serVol.get(i) <= q2 ? 1 : 2);
                abT[t] += serAbs.get(i)[k];
                nT[t] += serN.get(i);
                abW[serWeek.get(i)] += serAbs.get(i)[k];
                nW[serWeek.get(i)] += serN.get(i);
            }
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("method", METHODS[k]);
            m.put("mae", ab / nTot);
            m.put("rmse", Math.sqrt(sq / nTot));
            m.put("mae_low_volume", abT[0] / nT[0]);
            m.put("mae_mid_volume", abT[1] / nT[1]);
            m.put("mae_high_volume", abT[2] / nT[2]);
            for (int wi = 0; wi < testSeeds.length; wi++) m.put("mae_week_seed" + testSeeds[wi], abW[wi] / nW[wi]);
            metrics.add(m);
        }

        // bootstrap apparié par série : écart MAE(LSTM) − MAE(référence)
        int B = c.integer("p1.bootstrap");
        Random rnd = new Random(7);
        int S = serAbs.size();
        for (int ref = 2; ref < M; ref++) {
            double[] diffs = new double[B];
            for (int b = 0; b < B; b++) {
                double dl = 0, dr = 0;
                long n = 0;
                for (int j = 0; j < S; j++) {
                    int i = rnd.nextInt(S);
                    dl += serAbs.get(i)[0];
                    dr += serAbs.get(i)[ref];
                    n += serN.get(i);
                }
                diffs[b] = (dl - dr) / n;
            }
            Arrays.sort(diffs);
            long better = 0;
            double obsL = 0, obsR = 0;
            for (int i = 0; i < S; i++) {
                if (serAbs.get(i)[0] < serAbs.get(i)[ref]) better++;
                obsL += serAbs.get(i)[0];
                obsR += serAbs.get(i)[ref];
            }
            long nonNeg = Arrays.stream(diffs).filter(d -> d >= 0).count();
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("reference", METHODS[ref]);
            m.put("mae_diff", (obsL - obsR) / nTot);
            m.put("relative_pct", 100 * (obsL - obsR) / obsR);
            m.put("ci95_low", diffs[(int) (0.025 * B)]);
            m.put("ci95_high", diffs[(int) (0.975 * B) - 1]);
            m.put("p_one_sided", (nonNeg + 1.0) / (B + 1.0));
            m.put("series_lstm_better_pct", 100.0 * better / S);
            bootstrap.add(m);
        }
        Map<String, Object> info = new LinkedHashMap<>();
        info.put("points", nTot); info.put("series", S); info.put("tertile_q1", q1); info.put("tertile_q2", q2);
        info.put("ema_alpha", bestAlpha); info.put("train_windows", X.length); info.put("val_windows", vset.X.length);
        info.put("selected_init", bi);
        this.info = info;
    }

    public Map<String, Object> info;

    static double target(double[] s, int e, int tau) {
        double t = 0;
        for (int k = e; k < e + tau; k++) t += s[k];
        return t / tau;
    }

    /** Même phase au cycle précédent : moyenne de [e − cycle, e − cycle + τ). */
    static double seasonal(double[] s, int e, int tau, int cycle) {
        double t = 0;
        for (int k = e - cycle; k < e - cycle + tau; k++) t += s[k];
        return t / tau;
    }
}
