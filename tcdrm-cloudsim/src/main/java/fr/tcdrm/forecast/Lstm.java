package fr.tcdrm.forecast;

import java.io.Serializable;
import java.util.Random;

/** LSTM une couche, BPTT + Adam avec écrêtage du gradient. Portes : i, f, o, g. */
public final class Lstm implements Serializable {
    private static final long serialVersionUID = 1L;

    final int nIn, H;
    final double[][] Wx;   // nIn × 4H
    final double[][] Wh;   // H × 4H
    final double[] b;      // 4H
    final double[] Wy;     // H
    double by;

    // Adam
    private transient double[][] mWx, vWx, mWh, vWh;
    private transient double[] mb, vb, mWy, vWy;
    private transient double mby, vby;
    private int step;

    public Lstm(int nIn, int hidden, long seed) {
        this.nIn = nIn;
        this.H = hidden;
        Random r = new Random(seed);
        double k = 1.0 / Math.sqrt(hidden);
        Wx = new double[nIn][4 * H];
        Wh = new double[H][4 * H];
        b = new double[4 * H];
        Wy = new double[H];
        for (double[] row : Wx) for (int j = 0; j < row.length; j++) row[j] = (2 * r.nextDouble() - 1) * k;
        for (double[] row : Wh) for (int j = 0; j < row.length; j++) row[j] = (2 * r.nextDouble() - 1) * k;
        for (int j = 0; j < H; j++) Wy[j] = (2 * r.nextDouble() - 1) * k;
        for (int j = H; j < 2 * H; j++) b[j] = 1.0;     // biais d'oubli
    }

    private static double sig(double x) { return 1.0 / (1.0 + Math.exp(-x)); }

    public double predict(double[][] x) {
        double[] h = new double[H], c = new double[H], z = new double[4 * H];
        for (double[] xt : x) {
            gates(xt, h, z);
            for (int j = 0; j < H; j++) {
                double i = sig(z[j]), f = sig(z[H + j]), o = sig(z[2 * H + j]), g = Math.tanh(z[3 * H + j]);
                c[j] = f * c[j] + i * g;
                h[j] = o * Math.tanh(c[j]);
            }
        }
        double y = by;
        for (int j = 0; j < H; j++) y += h[j] * Wy[j];
        return y;
    }

    private void gates(double[] xt, double[] h, double[] z) {
        System.arraycopy(b, 0, z, 0, 4 * H);
        for (int a = 0; a < nIn; a++) {
            double xa = xt[a];
            double[] w = Wx[a];
            for (int j = 0; j < 4 * H; j++) z[j] += xa * w[j];
        }
        for (int a = 0; a < H; a++) {
            double ha = h[a];
            if (ha == 0) continue;
            double[] w = Wh[a];
            for (int j = 0; j < 4 * H; j++) z[j] += ha * w[j];
        }
    }

    /** Accumule les gradients de la perte (y − target)² · scale pour un exemple ; renvoie l'erreur². */
    private double backprop(double[][] x, double target, double scale,
                            double[][] gWx, double[][] gWh, double[] gb, double[] gWy, double[] gby) {
        int T = x.length;
        double[][] hs = new double[T + 1][H], cs = new double[T + 1][H];
        double[][] I = new double[T][H], F = new double[T][H], O = new double[T][H], G = new double[T][H];
        double[] z = new double[4 * H];
        for (int t = 0; t < T; t++) {
            gates(x[t], hs[t], z);
            for (int j = 0; j < H; j++) {
                I[t][j] = sig(z[j]); F[t][j] = sig(z[H + j]);
                O[t][j] = sig(z[2 * H + j]); G[t][j] = Math.tanh(z[3 * H + j]);
                cs[t + 1][j] = F[t][j] * cs[t][j] + I[t][j] * G[t][j];
                hs[t + 1][j] = O[t][j] * Math.tanh(cs[t + 1][j]);
            }
        }
        double y = by;
        for (int j = 0; j < H; j++) y += hs[T][j] * Wy[j];
        double err = y - target;
        double dy = 2 * err * scale;
        for (int j = 0; j < H; j++) gWy[j] += hs[T][j] * dy;
        gby[0] += dy;
        double[] dh = new double[H], dc = new double[H], dz = new double[4 * H];
        for (int j = 0; j < H; j++) dh[j] = dy * Wy[j];
        for (int t = T - 1; t >= 0; t--) {
            for (int j = 0; j < H; j++) {
                double tc = Math.tanh(cs[t + 1][j]);
                double dO = dh[j] * tc;
                dc[j] += dh[j] * O[t][j] * (1 - tc * tc);
                double dI = dc[j] * G[t][j], dG = dc[j] * I[t][j], dF = dc[j] * cs[t][j];
                dz[j] = dI * I[t][j] * (1 - I[t][j]);
                dz[H + j] = dF * F[t][j] * (1 - F[t][j]);
                dz[2 * H + j] = dO * O[t][j] * (1 - O[t][j]);
                dz[3 * H + j] = dG * (1 - G[t][j] * G[t][j]);
            }
            for (int a = 0; a < nIn; a++) {
                double xa = x[t][a];
                for (int j = 0; j < 4 * H; j++) gWx[a][j] += xa * dz[j];
            }
            for (int a = 0; a < H; a++) {
                double ha = hs[t][a];
                for (int j = 0; j < 4 * H; j++) gWh[a][j] += ha * dz[j];
            }
            for (int j = 0; j < 4 * H; j++) gb[j] += dz[j];
            double[] ndh = new double[H];
            for (int a = 0; a < H; a++) {
                double s = 0;
                double[] w = Wh[a];
                for (int j = 0; j < 4 * H; j++) s += dz[j] * w[j];
                ndh[a] = s;
            }
            dh = ndh;
            for (int j = 0; j < H; j++) dc[j] *= F[t][j];
        }
        return err * err;
    }

    public double[] fit(double[][][] X, double[] Y, int epochs, int batch, double lr, long seed) {
        initAdam();
        Random r = new Random(seed);
        int n = X.length;
        double[] hist = new double[epochs];
        int[] idx = new int[n];
        for (int i = 0; i < n; i++) idx[i] = i;
        for (int ep = 0; ep < epochs; ep++) {
            for (int i = n - 1; i > 0; i--) {
                int j = r.nextInt(i + 1);
                int t = idx[i]; idx[i] = idx[j]; idx[j] = t;
            }
            double tot = 0;
            for (int s = 0; s < n; s += batch) {
                int e = Math.min(n, s + batch);
                double[][] gWx = new double[nIn][4 * H], gWh = new double[H][4 * H];
                double[] gb = new double[4 * H], gWy = new double[H], gby = new double[1];
                for (int k = s; k < e; k++) tot += backprop(X[idx[k]], Y[idx[k]], 1.0 / (e - s), gWx, gWh, gb, gWy, gby);
                adam(gWx, gWh, gb, gWy, gby[0], lr);
            }
            hist[ep] = tot / n;
        }
        return hist;
    }

    public double mse(double[][][] X, double[] Y) {
        double t = 0;
        for (int i = 0; i < X.length; i++) {
            double e = predict(X[i]) - Y[i];
            t += e * e;
        }
        return t / X.length;
    }

    private double[][] snapshot() {
        double[][] snap = new double[nIn + H + 3][];
        int k = 0;
        for (double[] r : Wx) snap[k++] = r.clone();
        for (double[] r : Wh) snap[k++] = r.clone();
        snap[k++] = b.clone();
        snap[k++] = Wy.clone();
        snap[k] = new double[]{by};
        return snap;
    }

    private void restore(double[][] snap) {
        int k = 0;
        for (double[] r : Wx) { System.arraycopy(snap[k++], 0, r, 0, r.length); }
        for (double[] r : Wh) { System.arraycopy(snap[k++], 0, r, 0, r.length); }
        System.arraycopy(snap[k++], 0, b, 0, b.length);
        System.arraycopy(snap[k++], 0, Wy, 0, Wy.length);
        by = snap[k][0];
    }

    /** Arrêt anticipé après {@code patience} époques sans gain ; restaure les meilleurs poids. */
    public double[][] fitEarlyStopping(double[][][] X, double[] Y, double[][][] Xv, double[] Yv, int maxEpochs,
                                       int patience, int batch, double lr, long seed) {
        initAdam();
        Random r = new Random(seed);
        int n = X.length;
        int[] idx = new int[n];
        for (int i = 0; i < n; i++) idx[i] = i;
        java.util.List<double[]> hist = new java.util.ArrayList<>();
        double bestVal = Double.POSITIVE_INFINITY;
        double[][] bestW = snapshot();
        int wait = 0;
        for (int ep = 0; ep < maxEpochs; ep++) {
            for (int i = n - 1; i > 0; i--) {
                int j = r.nextInt(i + 1);
                int t = idx[i]; idx[i] = idx[j]; idx[j] = t;
            }
            double tot = 0;
            for (int s = 0; s < n; s += batch) {
                int e = Math.min(n, s + batch);
                double[][] gWx = new double[nIn][4 * H], gWh = new double[H][4 * H];
                double[] gb = new double[4 * H], gWy = new double[H], gby = new double[1];
                for (int k = s; k < e; k++) tot += backprop(X[idx[k]], Y[idx[k]], 1.0 / (e - s), gWx, gWh, gb, gWy, gby);
                adam(gWx, gWh, gb, gWy, gby[0], lr);
            }
            double val = mse(Xv, Yv);
            hist.add(new double[]{tot / n, val});
            if (val < bestVal - 1e-7) {
                bestVal = val;
                bestW = snapshot();
                wait = 0;
            } else if (++wait >= patience) {
                break;
            }
        }
        restore(bestW);
        return hist.toArray(new double[0][]);
    }

    private void initAdam() {
        mWx = new double[nIn][4 * H]; vWx = new double[nIn][4 * H];
        mWh = new double[H][4 * H]; vWh = new double[H][4 * H];
        mb = new double[4 * H]; vb = new double[4 * H];
        mWy = new double[H]; vWy = new double[H];
        mby = 0; vby = 0; step = 0;
    }

    private void adam(double[][] gWx, double[][] gWh, double[] gb, double[] gWy, double gby, double lr) {
        final double b1 = 0.9, b2 = 0.999, eps = 1e-8, clip = 5.0;
        step++;
        double norm = gby * gby;
        for (double[] row : gWx) for (double v : row) norm += v * v;
        for (double[] row : gWh) for (double v : row) norm += v * v;
        for (double v : gb) norm += v * v;
        for (double v : gWy) norm += v * v;
        double scale = Math.min(1.0, clip / (Math.sqrt(norm) + 1e-12));
        double c1 = 1 - Math.pow(b1, step), c2 = 1 - Math.pow(b2, step);
        for (int a = 0; a < nIn; a++) upd(Wx[a], gWx[a], mWx[a], vWx[a], scale, lr, c1, c2);
        for (int a = 0; a < H; a++) upd(Wh[a], gWh[a], mWh[a], vWh[a], scale, lr, c1, c2);
        upd(b, gb, mb, vb, scale, lr, c1, c2);
        upd(Wy, gWy, mWy, vWy, scale, lr, c1, c2);
        double g = gby * scale;
        mby = b1 * mby + (1 - b1) * g;
        vby = b2 * vby + (1 - b2) * g * g;
        by -= lr * (mby / c1) / (Math.sqrt(vby / c2) + eps);
    }

    private static void upd(double[] w, double[] g, double[] m, double[] v, double scale, double lr, double c1, double c2) {
        final double b1 = 0.9, b2 = 0.999, eps = 1e-8;
        for (int j = 0; j < w.length; j++) {
            double gj = g[j] * scale;
            m[j] = b1 * m[j] + (1 - b1) * gj;
            v[j] = b2 * v[j] + (1 - b2) * gj * gj;
            w[j] -= lr * (m[j] / c1) / (Math.sqrt(v[j] / c2) + eps);
        }
    }

    /** Vérification du gradient par différences finies (utilisée par les tests). */
    public double[] gradientCheck(double[][] x, double target) {
        double[][] gWx = new double[nIn][4 * H], gWh = new double[H][4 * H];
        double[] gb = new double[4 * H], gWy = new double[H], gby = new double[1];
        backprop(x, target, 1.0, gWx, gWh, gb, gWy, gby);
        double eps = 1e-6, old = Wx[0][3];
        Wx[0][3] = old + eps;
        double lp = Math.pow(predict(x) - target, 2);
        Wx[0][3] = old - eps;
        double lm = Math.pow(predict(x) - target, 2);
        Wx[0][3] = old;
        double oldh = Wh[1][5];
        Wh[1][5] = oldh + eps;
        double lp2 = Math.pow(predict(x) - target, 2);
        Wh[1][5] = oldh - eps;
        double lm2 = Math.pow(predict(x) - target, 2);
        Wh[1][5] = oldh;
        return new double[]{gWx[0][3], (lp - lm) / (2 * eps), gWh[1][5], (lp2 - lm2) / (2 * eps)};
    }
}
