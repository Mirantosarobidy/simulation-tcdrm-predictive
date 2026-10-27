package fr.tcdrm.forecast;

import fr.tcdrm.config.SimConfig;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/** P1 : historique court → EMA ; série stable → régression ; sinon → LSTM. */
public final class PopularityForecaster {
    public static final double SCALE = 5.0;

    private final Lstm lstm;
    private final int tau, W, minLen, cycle;
    private final double alpha;
    private final String method;   // auto | EMA | REG | LSTM
    public final Map<String, Integer> used = new LinkedHashMap<>();

    public PopularityForecaster(SimConfig c, Lstm lstm, String method) {
        this.lstm = lstm;
        this.tau = c.integer("p2.horizon");
        this.W = c.integer("p1.window");
        this.minLen = c.integer("p1.minLen");
        this.cycle = c.integer("s3.cycle");
        this.alpha = c.dbl("p1.ema.alpha");
        this.method = method;
        used.put("EMA", 0);
        used.put("REG", 0);
        used.put("LSTM", 0);
    }

    public static double ema(double[] s, int len, double alpha) {
        double v = s[0];
        for (int i = 1; i < len; i++) v = alpha * s[i] + (1 - alpha) * v;
        return v;
    }

    /** Régression linéaire sur les 12 dernières périodes, évaluée au milieu de l'horizon. */
    public static double regression(double[] s, int len, int tau) {
        int w = Math.min(12, len), lo = len - w;
        if (w < 2) return s[len - 1];
        double[] ab = fit(s, lo, w);
        double xf = w - 1 + (tau + 1) / 2.0;
        return Math.max(0.0, ab[0] * xf + ab[1]);
    }

    private static double[] fit(double[] s, int lo, int w) {
        double mx = (w - 1) / 2.0, my = 0;
        for (int i = 0; i < w; i++) my += s[lo + i];
        my /= w;
        double sxy = 0, sxx = 0;
        for (int i = 0; i < w; i++) {
            sxy += (i - mx) * (s[lo + i] - my);
            sxx += (i - mx) * (i - mx);
        }
        double a = sxx > 0 ? sxy / sxx : 0;
        return new double[]{a, my - a * mx};
    }

    /** Série stable : écart-type des résidus de la tendance < 25 % de la moyenne (dernier cycle). */
    public static boolean isStable(double[] s, int len, int cycle) {
        int w = Math.min(cycle, len), lo = len - w;
        double mean = 0;
        for (int i = 0; i < w; i++) mean += s[lo + i];
        mean /= w;
        if (mean < 1e-9) return true;
        double[] ab = fit(s, lo, w);
        double ss = 0, mr = 0;
        double[] res = new double[w];
        for (int i = 0; i < w; i++) {
            res[i] = s[lo + i] - (ab[0] * i + ab[1]);
            mr += res[i];
        }
        mr /= w;
        for (double r : res) ss += (r - mr) * (r - mr);
        return Math.sqrt(ss / w) / (mean + 1e-9) < 0.25;
    }

    /** Fenêtre [end−W, end) → (W, 3) : log1p(accès)/SCALE, sin et cos de la position dans le cycle. */
    public static double[][] window(double[] s, int end, int W, int cycle) {
        double[][] x = new double[W][3];
        for (int k = 0; k < W; k++) {
            int t = end - W + k;
            double hr = (t % cycle) * 2 * Math.PI / cycle;
            x[k][0] = Math.log1p(s[t]) / SCALE;
            x[k][1] = Math.sin(hr);
            x[k][2] = Math.cos(hr);
        }
        return x;
    }

    public double lstmPredict(double[] s, int len) {
        return Math.max(0.0, Math.expm1(lstm.predict(window(s, len, W, cycle)) * SCALE));
    }

    public double predictSeries(double[] s, int len) {
        String m = method;
        if (m.equals("auto")) {
            if (len < minLen || lstm == null) m = "EMA";
            else if (isStable(s, len, cycle)) m = "REG";
            else m = "LSTM";
        }
        used.merge(m, 1, Integer::sum);
        switch (m) {
            case "EMA": return ema(s, len, alpha);
            case "REG": return regression(s, len, tau);
            default: return lstmPredict(s, len);
        }
    }

    /** Taux prévu (accès par période) pour chaque région, à partir des périodes complètes < period. */
    public double[] predict(float[][][] counts, int rel, int period) {
        double[] out = new double[3];
        double[] s = new double[period];
        for (int r = 0; r < 3; r++) {
            for (int t = 0; t < period; t++) s[t] = counts[rel][t][r];
            out[r] = predictSeries(s, period);
        }
        return out;
    }

    public static final class TrainingSet {
        public final double[][][] X;
        public final double[] Y;

        TrainingSet(double[][][] x, double[] y) { X = x; Y = y; }
    }

    /** Une série par (relation, région) ; cible = log1p(moyenne des τ périodes suivantes)/SCALE. */
    public static TrainingSet buildTrainingSet(float[][][] counts, int W, int tau, int cycle, int maxSamples, long seed) {
        int nRel = counts.length, T = counts[0].length;
        List<int[]> pairs = new ArrayList<>();
        List<double[]> series = new ArrayList<>();
        for (int d = 0; d < nRel; d++) {
            for (int r = 0; r < 3; r++) {
                double[] s = new double[T];
                double tot = 0;
                for (int t = 0; t < T; t++) { s[t] = counts[d][t][r]; tot += s[t]; }
                if (tot <= 0) continue;
                int sid = series.size();
                series.add(s);
                for (int e = W; e <= T - tau; e++) pairs.add(new int[]{sid, e});
            }
        }
        Collections.shuffle(pairs, new Random(seed));
        int n = Math.min(maxSamples, pairs.size());
        double[][][] X = new double[n][][];
        double[] Y = new double[n];
        for (int i = 0; i < n; i++) {
            double[] s = series.get(pairs.get(i)[0]);
            int e = pairs.get(i)[1];
            X[i] = window(s, e, W, cycle);
            double m = 0;
            for (int t = e; t < e + tau; t++) m += s[t];
            Y[i] = Math.log1p(m / tau) / SCALE;
        }
        return new TrainingSet(X, Y);
    }
}
