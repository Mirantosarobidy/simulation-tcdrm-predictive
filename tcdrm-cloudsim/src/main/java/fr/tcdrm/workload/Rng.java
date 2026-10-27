package fr.tcdrm.workload;

import java.util.Random;

/** Générateur pseudo-aléatoire reproductible avec les lois utilisées par les scénarios. */
public final class Rng {
    private final Random r;

    public Rng(long seed) { r = new Random(seed); }

    public double uniform() { return r.nextDouble(); }

    public double uniform(double lo, double hi) { return lo + (hi - lo) * r.nextDouble(); }

    public int integer(int bound) { return r.nextInt(bound); }

    public double gaussian() { return r.nextGaussian(); }

    public double lognormal(double mu, double sigma) { return Math.exp(mu + sigma * r.nextGaussian()); }

    /** Loi de Poisson (méthode de Knuth par blocs pour les grandes moyennes). */
    public int poisson(double lambda) {
        int k = 0;
        double remaining = lambda;
        while (remaining > 0) {
            double step = Math.min(remaining, 30.0);
            remaining -= step;
            double L = Math.exp(-step), p = 1.0;
            int c = -1;
            do {
                c++;
                p *= r.nextDouble();
            } while (p > L);
            k += c;
        }
        return k;
    }

    public int choice(double[] w) {
        double tot = 0;
        for (double x : w) tot += x;
        double u = r.nextDouble() * tot, acc = 0;
        for (int i = 0; i < w.length; i++) {
            acc += w[i];
            if (u < acc) return i;
        }
        return w.length - 1;
    }

    public int[] permutation(int n) {
        int[] a = new int[n];
        for (int i = 0; i < n; i++) a[i] = i;
        for (int i = n - 1; i > 0; i--) {
            int j = r.nextInt(i + 1);
            int t = a[i]; a[i] = a[j]; a[j] = t;
        }
        return a;
    }
}
