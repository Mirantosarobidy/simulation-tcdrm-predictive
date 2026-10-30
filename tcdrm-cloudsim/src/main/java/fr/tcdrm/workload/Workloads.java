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
        /** Périodes par cycle (budget, saisonnalité de P1). */
        public final int cycle;
        /** S3 : modèles de requête tirés pour cette graine (relations, part de base, rôle des relations). */
        public int[][] templates;
        public double[] templateShare;
        public int rareTemplate = -1, burstStart = -1, burstEnd = -1, intermittentTemplate = -1;
        public final java.util.Map<Integer, String> roles = new java.util.LinkedHashMap<>();

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

        public static S3Params base(SimConfig c) { return new S3Params(c.dbl("s4.writeProb")); }
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

    // Scénario S4 : requêtes tirées de modèles aléatoires (exprimé en nombre de requêtes)
    public static final String[] SUN = {"AS", "UE", "US"};

    private static double classWriteWeight(String cls) {
        switch (cls) {
            case "Critical": return 10.0;
            case "ReadMostly": return 1.0;
            default: return 3.0;
        }
    }

    /** Rôles des relations lues en S3, du plus au moins utilisé. */
    public static final String[] ROLES = {"partagée", "commune", "fréquente", "intermittente", "rare"};

    /**
     * Nom « F k » d'une relation du catalogue : les relations F sont réparties sur les 9 sites dans
     * l'ordre fixe de la Table 1 (F1 sur le 1er site, F2 sur le 2e, …, F10 de nouveau sur le 1er).
     */
    public static String fLabel(SimConfig c, int rel) {
        int sites = c.nSites(), per = c.integer("relations.perSite");
        int site = rel / per, local = rel % per;
        return "F" + (local * sites + site + 1);
    }

    /** Relation F_k du catalogue (inverse de fLabel). */
    public static int fRel(SimConfig c, int k) {
        int sites = c.nSites(), per = c.integer("relations.perSite");
        int site = (k - 1) % sites, local = (k - 1) / sites;
        if (local >= per) throw new IllegalArgumentException("F" + k + " dépasse le catalogue");
        return site * per + local;
    }

    /** Relation tirée au hasard sur un site qui n'est pas encore utilisé par la requête. */
    private static int drawRel(Rng rng, int nSites, int per, java.util.Set<Integer> usedSites, java.util.Set<Integer> taken) {
        while (true) {
            int site = rng.integer(nSites);
            if (usedSites.contains(site)) continue;
            int rel = site * per + rng.integer(per);
            if (taken.contains(rel)) continue;
            usedSites.add(site);
            taken.add(rel);
            return rel;
        }
    }

    /**
     * S4 : K modèles de requête tirés au hasard pour chaque graine, sur le principe de l'exemple
     * Q1 = {F1, F5, F20}, Q2 = {F1, F9, F44}, Q3 = {F1, F9, F25} :
     * <ul>
     *   <li>une relation « partagée » est lue par tous les modèles (comme F1) ;</li>
     *   <li>une relation « commune » est lue par plusieurs modèles fréquents, pas par tous (comme F9) ;</li>
     *   <li>les autres relations sont propres à un modèle : « fréquente » si le modèle est fréquent,
     *       « rare » si c'est le modèle rare (comme F5 et F20 dans Q1).</li>
     * </ul>
     * Les modèles fréquents ont des parts de Zipf tirées au hasard ; le modèle rare a une petite part,
     * sauf pendant une rafale de quelques périodes placée au hasard. Les relations d'une requête sont sur
     * des sites distincts. Origine tirée au hasard comme dans l'article (S2), écritures avec probabilité writeProb.
     */
    public static Workload dynamic(SimConfig c, int seed, S3Params sp) {
        Rng rng = new Rng(seed);
        int nq = c.integer("s4.queries"), B = c.integer("s4.periodQueries");
        int cycle = c.integer("s4.cycle");
        double qps = c.dbl("s4.qps");
        Catalog cat = baseCatalog(c, rng, 0.0);
        Arrays.fill(cat.size, c.dbl("relation.sizeGb"));
        cat.deltaFrac = c.dbl("s4.deltaFrac");
        int n = cat.n();
        for (int i = 0; i < n; i++) {
            double u = rng.uniform();
            cat.cls[i] = u < 0.15 ? "Critical" : (u < 0.75 ? "ReadMostly" : "Eventual");
        }
        int nPeriods = (int) Math.ceil(nq / (double) B);

        // modèles de requête
        int K = c.integer("s4.templates"), m = c.integer("s4.relsPerQuery");
        int nSites = c.nSites(), per = c.integer("relations.perSite");
        double commonProb = c.dbl("s4.commonProb"), zipf = c.dbl("s4.zipf");
        java.util.Set<Integer> taken = new java.util.HashSet<>();
        int shared = drawRel(rng, nSites, per, new java.util.HashSet<>(), taken);
        int common = drawRel(rng, nSites, per, new java.util.HashSet<>(java.util.Set.of(shared / per)), taken);
        int rare = K - 1;                                   // le dernier modèle est le modèle rare
        boolean[] hasCommon = new boolean[K];
        int nCommon = 0;
        for (int j = 0; j < rare; j++) { hasCommon[j] = rng.uniform() < commonProb; if (hasCommon[j]) nCommon++; }
        for (int j = 0; nCommon < Math.min(2, rare) && j < rare; j++) if (!hasCommon[j]) { hasCommon[j] = true; nCommon++; }
        int[][] tpl = new int[K][];
        java.util.Map<Integer, String> roles = new java.util.LinkedHashMap<>();
        roles.put(shared, "partagée");
        roles.put(common, "commune");
        for (int j = 0; j < K; j++) {
            List<Integer> rs = new ArrayList<>();
            java.util.Set<Integer> used = new java.util.HashSet<>();
            rs.add(shared); used.add(shared / per);
            if (hasCommon[j]) { rs.add(common); used.add(common / per); }
            while (rs.size() < m) {
                int r = drawRel(rng, nSites, per, used, taken);
                rs.add(r);
                roles.put(r, j == rare ? "rare" : "fréquente");
            }
            tpl[j] = rs.stream().mapToInt(Integer::intValue).toArray();
        }
        // parts : Zipf sur un ordre aléatoire des modèles fréquents ; petite part pour le modèle rare
        double rareShare = c.dbl("s4.rare.share"), rareBurst = c.dbl("s4.rare.burstShare");
        int[] perm = rng.permutation(rare);
        double[] w = new double[K];
        double tot = 0;
        for (int j = 0; j < rare; j++) { w[j] = 1.0 / Math.pow(perm[j] + 1, zipf); tot += w[j]; }
        for (int j = 0; j < rare; j++) w[j] /= tot;
        int burstLen = c.integer("s4.rare.burstPeriods");
        int b0 = 2 + rng.integer(Math.max(1, nPeriods / 2 - 2)), b1 = b0 + burstLen;
        double[] base = new double[K];
        for (int j = 0; j < rare; j++) base[j] = (1 - rareShare) * w[j];
        base[rare] = rareShare;

        // S5 : charge non stationnaire, le classement des modèles fréquents tourne d'un rang toutes les
        // s4.shiftPeriods périodes (0 = parts fixes, S4) ; le motif revient tous les (K − 1) × shift périodes
        int shift = c.has("s4.shiftPeriods") ? c.integer("s4.shiftPeriods") : 0;
        double[][] wRot = new double[Math.max(1, rare)][rare], baseRot = new double[Math.max(1, rare)][K];
        for (int k = 0; k < wRot.length; k++) {
            double tt = 0;
            for (int j = 0; j < rare; j++) { wRot[k][j] = 1.0 / Math.pow((perm[j] + k) % rare + 1, zipf); tt += wRot[k][j]; }
            for (int j = 0; j < rare; j++) { wRot[k][j] /= tt; baseRot[k][j] = (1 - rareShare) * wRot[k][j]; }
            baseRot[k][rare] = rareShare;
        }
        // S5 : modèle intermittent, actif s4.intermittent.on périodes toutes les s4.intermittent.period
        // périodes et absent le reste du temps (sa demande part puis revient : cas de thrashing)
        int interP = c.has("s4.intermittent.period") ? c.integer("s4.intermittent.period") : 0;
        int interOn = interP > 0 ? c.integer("s4.intermittent.on") : 0;
        int inter = interP > 0 && rare > 0 ? rng.integer(rare) : -1;
        double interShare = interP > 0 ? c.dbl("s4.intermittent.share") : 0;
        List<Query> qs = new ArrayList<>(nq);
        for (int qid = 0; qid < nq; qid++) {
            double t = qid / (double) B;                     // instant d'arrivée, en périodes
            int kr = shift > 0 ? ((int) (t / shift)) % wRot.length : 0;
            int j;
            if (inter >= 0 && ((int) t) % interP < interOn && rng.uniform() < interShare) {
                j = inter;                                   // phase active du modèle intermittent
            } else if (t >= b0 && t < b1) {
                double[] wk = wRot[kr].clone();
                if (inter >= 0) wk[inter] = 0;
                j = rng.uniform() < rareBurst ? rare : rng.choice(wk);
            } else {
                double[] bk = baseRot[kr].clone();
                if (inter >= 0) bk[inter] = 0;
                j = rng.choice(bk);
            }
            String origin = c.regions.get(rng.integer(3));    // région d'origine au hasard, comme dans l'article
            if (rng.uniform() < sp.writeProb) {
                double[] cw = new double[tpl[j].length];
                for (int k = 0; k < cw.length; k++) cw[k] = classWriteWeight(cat.cls[tpl[j][k]]);
                int wr = tpl[j][rng.choice(cw)];
                qs.add(new Query(qid, t, "write", new int[0], null, origin, wr, j));
            } else {
                qs.add(new Query(qid, t, "simple", tpl[j].clone(), selectivity(c, tpl[j].length), origin, -1, j));
            }
        }
        Workload wl = new Workload(cat, qs, nPeriods, B / qps, cycle);
        wl.templates = tpl;
        wl.templateShare = base;
        wl.rareTemplate = rare;
        wl.burstStart = b0;
        wl.burstEnd = b1;
        wl.roles.putAll(roles);
        if (inter >= 0) {
            wl.intermittentTemplate = inter;
            for (int r : tpl[inter]) if ("fréquente".equals(roles.get(r))) wl.roles.put(r, "intermittente");
        }
        return wl;
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
