package fr.tcdrm.workload;

import fr.tcdrm.config.SimConfig;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public final class Workloads {

    public static final class Workload {
        public final Catalog catalog;
        public final List<Query> queries;
        public final int nPeriods;
        /** Durée d'une période en secondes simulées (Éq. 1, algorithme 3, stockage). */
        public final double periodSeconds;
        /** Périodes par cycle (budget, origine follow-the-sun, saisonnalité de P1). */
        public final int cycle;

        Workload(Catalog c, List<Query> q, int nPeriods, double periodSeconds, int cycle) {
            this.catalog = c;
            this.queries = q;
            this.nPeriods = nPeriods;
            this.periodSeconds = periodSeconds;
            this.cycle = cycle;
        }
    }

    /** Probabilité d'écriture (balayage de sensibilité). */
    public static final class S3Params {
        public double writeProb;

        public S3Params(double writeProb) { this.writeProb = writeProb; }

        public static S3Params base(SimConfig c) { return new S3Params(c.dbl("s3.writeProb")); }
    }

    static double[] selectivity(SimConfig c, int n) {
        double[] s = new double[n];
        Arrays.fill(s, c.dbl("query.selectivity"));
        return s;
    }

    private Workloads() { }

    static Catalog baseCatalog(SimConfig c, Rng rng, double sizeSigma) {
        int nSites = c.nSites(), per = c.integer("relations.perSite");
        int n = nSites * per;
        int[] primary = new int[n];
        double[] size = new double[n];
        double mean = c.dbl("relation.sizeGb");
        double mu = Math.log(mean) - sizeSigma * sizeSigma / 2;
        for (int i = 0; i < n; i++) {
            primary[i] = i / per;
            size[i] = sizeSigma > 0 ? Math.min(2.0, Math.max(0.05, rng.lognormal(mu, sizeSigma)))
                    : rng.uniform(0.35, 0.55);
        }
        String[] cls = new String[n];
        Arrays.fill(cls, "Eventual");
        return new Catalog(size, primary, cls);
    }

    // Scénario de l'article
    // Requête simple : une relation par région ; complexe : deux par région.
    // Les relations sont chez Google et AWS ; l'exécution se fait sur la VM la moins
    // chère (Azure dans les 3 régions) -> échanges inter-fournisseurs, comme la Fig. 4.
    private static final String[][] SIMPLE_SITES = {{"Google", "US"}, {"AWS", "UE"}, {"Google", "AS"}};
    private static final String[][] COMPLEX_SITES = {{"Google", "US"}, {"AWS", "US"}, {"AWS", "UE"},
            {"Google", "UE"}, {"Google", "AS"}, {"AWS", "AS"}};

    public static Workload article(SimConfig c, String kind, int seed) {
        Rng rng = new Rng(seed);
        Catalog cat = baseCatalog(c, rng, 0.0);
        Arrays.fill(cat.size, c.dbl("relation.sizeGb"));
        String key = "article.sites." + kind;
        String[][] sites = kind.equals("simple") ? SIMPLE_SITES : COMPLEX_SITES;
        if (c.has(key)) {
            String[] parts = c.str(key).split(",");
            sites = new String[parts.length][];
            for (int k = 0; k < parts.length; k++) sites[k] = parts[k].trim().split("-");
        }
        int per = c.integer("relations.perSite");
        int[] rels = new int[sites.length];
        for (int k = 0; k < sites.length; k++) rels[k] = c.siteIndex(sites[k][0], sites[k][1]) * per + k;
        int nq = c.integer("article.queries");
        double qps = c.dbl("article.qps");
        List<Query> qs = new ArrayList<>(nq);
        for (int i = 0; i < nq; i++) {
            String origin = c.regions.get(rng.integer(3));
            qs.add(new Query(i, i / (qps * 3600.0), kind, rels.clone(), selectivity(c, rels.length), origin, -1, -1));
        }
        return new Workload(cat, qs, 1, 3600.0, 24);
    }

    // Scénario dynamique S3 (exprimé en nombre de requêtes)
    public static final String[] SUN = {"AS", "UE", "US"};

    private static double classWriteWeight(String cls) {
        switch (cls) {
            case "Critical": return 10.0;
            case "ReadMostly": return 1.0;
            default: return 3.0;
        }
    }

    /** S3 : popularité Zipf tirée à chaque cycle, origine follow-the-sun, écritures avec probabilité writeProb. */
    public static Workload dynamic(SimConfig c, int seed, S3Params sp) {
        Rng rng = new Rng(seed);
        int nq = c.integer("s3.queries"), B = c.integer("s3.periodQueries");
        int phaseP = c.integer("s3.phasePeriods"), cycle = c.integer("s3.cycle");
        double qps = c.dbl("s3.qps");
        Catalog cat = baseCatalog(c, rng, 0.0);
        Arrays.fill(cat.size, c.dbl("relation.sizeGb"));
        cat.deltaFrac = c.dbl("s3.deltaFrac");
        int n = cat.n();
        for (int i = 0; i < n; i++) {
            double u = rng.uniform();
            cat.cls[i] = u < 0.15 ? "Critical" : (u < 0.75 ? "ReadMostly" : "Eventual");
        }
        // modèles de requête : moitié simples (1 relation par région), moitié complexes (2 par région)
        int nT = c.integer("s3.templates"), per = c.integer("relations.perSite");
        String[] hosts = {"Google", "AWS"};
        String[] tKind = new String[nT];
        int[][] tRels = new int[nT][];
        int[] tHome = new int[nT];
        for (int j = 0; j < nT; j++) {
            tKind[j] = j % 2 == 0 ? "simple" : "complex";
            int perRegion = tKind[j].equals("simple") ? 1 : 2;
            tRels[j] = new int[3 * perRegion];
            int k = 0;
            for (String r : c.regions) {
                for (int m = 0; m < perRegion; m++) {
                    String p = perRegion == 2 ? hosts[m] : hosts[rng.integer(2)];
                    tRels[j][k++] = c.siteIndex(p, r) * per + rng.integer(per);
                }
            }
            tHome[j] = rng.integer(3);
        }
        int nPeriods = (int) Math.ceil(nq / (double) B);
        int nCycles = (int) Math.ceil(nPeriods / (double) cycle);
        double zs = c.dbl("s3.zipf");
        double[][] cycW = new double[nCycles][nT];
        for (int y = 0; y < nCycles; y++) {
            int[] perm = rng.permutation(nT);
            double tot = 0;
            for (int i = 0; i < nT; i++) {
                cycW[y][perm[i]] = 1.0 / Math.pow(i + 1, zs);
                tot += cycW[y][perm[i]];
            }
            for (int i = 0; i < nT; i++) cycW[y][i] /= tot;
        }
        double blend = c.dbl("s3.blendPeriods"), boost = c.dbl("s3.homeBoost"), dom = c.dbl("s3.dominant");
        List<Query> qs = new ArrayList<>(nq);
        for (int qid = 0; qid < nq; qid++) {
            double t = qid / (double) B;                     // instant d'arrivée, en périodes
            int y = (int) (t / cycle);
            double pos = t - y * cycle;
            String sun = SUN[Math.min(2, (int) (pos / phaseP))];
            double[] w;
            if (y == 0 || pos >= blend) {
                w = cycW[y];
            } else {
                double a = pos / blend;
                w = new double[nT];
                for (int i = 0; i < nT; i++) w[i] = (1 - a) * cycW[y - 1][i] + a * cycW[y][i];
            }
            int j = rng.choice(w);
            double[] ow = new double[3];
            for (int r = 0; r < 3; r++) {
                ow[r] = (c.regions.get(r).equals(sun) ? dom : (1 - dom) / 2) * (1 + boost * (r == tHome[j] ? 1 : 0));
            }
            String origin = c.regions.get(rng.choice(ow));
            if (rng.uniform() < sp.writeProb) {
                double[] cw = new double[tRels[j].length];
                for (int m = 0; m < cw.length; m++) cw[m] = classWriteWeight(cat.cls[tRels[j][m]]);
                int wr = tRels[j][rng.choice(cw)];
                qs.add(new Query(qid, t, "write", new int[0], null, origin, wr, j));
            } else {
                qs.add(new Query(qid, t, tKind[j], tRels[j].clone(), selectivity(c, tRels[j].length), origin, -1, j));
            }
        }
        return new Workload(cat, qs, nPeriods, B / qps, cycle);
    }

    public static float[][][] accessCounts(SimConfig c, Workload w) {
        float[][][] counts = new float[w.catalog.n()][w.nPeriods][3];
        for (Query q : w.queries) {
            int p = (int) q.tHours;
            if (p >= w.nPeriods) continue;
            int ri = c.regions.indexOf(q.origin);
            for (int d : q.rels) counts[d][p][ri] += 1;
        }
        return counts;
    }
}
