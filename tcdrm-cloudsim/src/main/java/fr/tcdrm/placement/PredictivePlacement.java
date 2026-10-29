package fr.tcdrm.placement;

import fr.tcdrm.cloud.MultiCloud;
import fr.tcdrm.config.SimConfig;
import fr.tcdrm.core.ReplicaManager;
import fr.tcdrm.forecast.PopularityForecaster;
import fr.tcdrm.workload.Query;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * TCDRM-Predictive : P1 (prédiction) → P3 (cohérence) → P2 (placement multi-objectifs).
 * En mode contraint, un site n'est retenu que s'il respecte les parts de TSLA/CSLA par accès,
 * la latence des écritures Critical, le budget projeté et l'amortissement (voir README).
 */
public final class PredictivePlacement implements PlacementStrategy {

    private static final double EPS = 1e-12;

    private final String name;
    private final PopularityForecaster forecaster;   // null = demande réactive (dernière heure)
    private final boolean useP3, constrained;
    private final double[] w;
    private final int H, maxPerRel;
    private final SimConfig cfg;

    public final Map<String, Long> stats = new LinkedHashMap<>();

    /**
     * Améliorations v2 (toutes désactivées par défaut, ce qui redonne exactement TCDRM-Predictive) :
     * poids dynamiques, politique « U seul », saturation des fournisseurs, anti-thrashing.
     */
    public static final class Options {
        /** Poids dynamiques : ω_coût × (1 + β·u), u = dépense projetée du cycle / budget ; 0 = poids fixes. */
        public double dynBeta = 0;
        /** Politique « Weighted » : argmin U sur tous les candidats, sans filtre de Pareto. */
        public boolean weightedOnly = false;
        /** Saturation : capacité en réplicas par site (0 = désactivée) et poids du 5e critère. */
        public int satCapacity = 0;
        public double satWeight = 0;
        /** Anti-thrashing : l'algorithme 3 garde un réplica si P1 ou la saisonnalité prévoient son retour. */
        public boolean antiThrash = false;
        /** Demande prévue minimale pour garder un réplica, en fraction du seuil de suppression. */
        public double retainFactor = 1.0;
    }

    private final Options opt;
    /** Réplicas déjà choisis dans l'appel courant de place(), par site (saturation). */
    private final Map<Integer, Integer> pendingHost = new java.util.HashMap<>();
    /** Somme et nombre des poids de coût effectivement utilisés (poids dynamiques). */
    public double wCostSum = 0;
    public long wCostN = 0;

    public PredictivePlacement(SimConfig cfg, String name, PopularityForecaster forecaster, boolean useP3) {
        this(cfg, name, forecaster, useP3, true);
    }

    public PredictivePlacement(SimConfig cfg, String name, PopularityForecaster forecaster, boolean useP3,
                               boolean constrained) {
        this(cfg, name, forecaster, useP3, constrained, new Options());
    }

    public PredictivePlacement(SimConfig cfg, String name, PopularityForecaster forecaster, boolean useP3,
                               boolean constrained, Options opt) {
        this.opt = opt;
        this.cfg = cfg;
        this.name = name;
        this.forecaster = forecaster;
        this.useP3 = useP3;
        this.constrained = constrained;
        double[] ww = cfg.doubles("p2.weights");
        if (!useP3) {
            double s = ww[0] + ww[1] + ww[2];
            ww = new double[]{ww[0] / s, ww[1] / s, ww[2] / s, 0.0};
        }
        this.w = ww;
        this.H = cfg.integer("p2.horizon");
        this.maxPerRel = cfg.integer("p2.maxPerRel");
        for (String k : new String[]{"decisions", "candidates", "rejected_TSLA", "rejected_CSLA", "rejected_write_TSLA",
                "rejected_budget", "rejected_amortization", "no_feasible_site", "placed", "ties", "front_multi"}) stats.put(k, 0L);
        if (opt.satCapacity > 0) stats.put("rejected_saturation", 0L);
        if (opt.antiThrash) stats.put("retained", 0L);
    }

    /** Poids effectifs : 4 critères (+ saturation) ; le poids du coût augmente avec la dépense projetée. */
    private double[] weights(Double budget, double projected) {
        int K = opt.satCapacity > 0 ? 5 : 4;
        double[] wk = new double[K];
        double f = opt.satCapacity > 0 ? 1 - opt.satWeight : 1;
        for (int k = 0; k < 4; k++) wk[k] = w[k] * f;
        if (K == 5) wk[4] = opt.satWeight;
        if (opt.dynBeta > 0 && budget != null && !Double.isNaN(projected)) {
            double u = Math.max(0, Math.min(1.5, projected / budget));
            wk[0] *= 1 + opt.dynBeta * u;
            double t = 0;
            for (double x : wk) t += x;
            for (int k = 0; k < K; k++) wk[k] /= t;
        }
        wCostSum += wk[0];
        wCostN++;
        return wk;
    }

    /** Anti-thrashing (algorithme 3 prédictif) : garder le réplica si la demande de sa région doit revenir. */
    @Override
    public boolean retain(ReplicaManager eng, int d, int site, int p) {
        if (!opt.antiThrash) return false;
        int g = cfg.regions.indexOf(eng.cloud.region(site));
        double lam = 0, seas = 0;
        if (forecaster != null) lam = forecaster.predict(eng.counts, d, p + 1)[g];   // P1 sur les τ prochaines périodes
        int lo = p + 1 - eng.cycle;                                                    // même phase, cycle précédent
        if (lo >= 0) {
            int n = 0;
            for (int t = lo; t < lo + H && t <= p; t++) { seas += eng.counts[d][t][g]; n++; }
            seas /= Math.max(1, n);
        }
        boolean keep = Math.max(lam, seas) >= opt.retainFactor * eng.deletionThreshold();
        if (keep) inc("retained");
        return keep;
    }

    @Override
    public String name() { return name; }

    public PopularityForecaster forecaster() { return forecaster; }

    @Override
    public boolean guaranteed() { return constrained; }

    @Override
    public double syncSharePerRead(ReplicaManager eng, int rk, int site) {
        Double v = lastSyncShare.get(((long) rk << 8) | site);
        return v == null ? 0.0 : v;
    }

    private final Map<Long, Double> lastSyncShare = new java.util.HashMap<>();

    private void inc(String k) { stats.merge(k, 1L, Long::sum); }

    // P1
    double[] demand(ReplicaManager eng, int rk, double nowH) {
        int period = eng.period;
        double[] out = new double[3];
        if (period == 0) {          // aucune période complète : taux observé dans la période en cours
            for (int r = 0; r < 3; r++) out[r] = eng.counts[rk][0][r] / Math.max(nowH, 1.0 / 60);
            return out;
        }
        if (forecaster != null) return forecaster.predict(eng.counts, rk, period);
        for (int r = 0; r < 3; r++) out[r] = eng.counts[rk][period - 1][r];
        return out;
    }

    /** Taux d'écriture (écritures/h) : moyenne observée sur les 24 dernières heures. */
    double writeRate(ReplicaManager eng, int rk, double nowH) {
        int period = eng.period;
        if (period == 0) return eng.wcounts[rk][0] / Math.max(nowH, 1.0 / 60);
        int lo = Math.max(0, period - eng.cycle);
        double s = 0;
        for (int t = lo; t < period; t++) s += eng.wcounts[rk][t];
        return s / (period - lo);
    }

    // P2
    @Override
    public List<int[]> place(ReplicaManager eng, Query q, int execSite, List<Integer> RD, double nowH) {
        MultiCloud cloud = eng.cloud;
        int[] execs = new int[3];
        for (int r = 0; r < 3; r++) execs[r] = cloud.execOf(cfg.regions.get(r));
        List<int[]> out = new ArrayList<>();
        pendingHost.clear();
        for (int rk : RD) {
            double[] lam = demand(eng, rk, nowH);
            double tot = 0;
            for (int r = 0; r < 3; r++) { lam[r] = Math.max(0, lam[r]); tot += lam[r]; }
            double[] wts = new double[3];
            for (int r = 0; r < 3; r++) wts[r] = tot > 0 ? lam[r] / tot : 1.0 / 3;
            Set<Integer> copies = new HashSet<>(eng.copySet(rk));
            double wRate = writeRate(eng, rk, nowH);
            int k = 0;
            while (k < q.rels.length && q.rels[k] != rk) k++;
            double selQ = k < q.rels.length ? q.sel(k) : eng.avgSel(rk);
            for (int it = 0; it < maxPerRel; it++) {
                Integer s = select(eng, q, execSite, rk, selQ, lam, tot, wts, copies, wRate, execs, nowH);
                if (s == null) break;
                out.add(new int[]{rk, s});
                copies.add(s);
                pendingHost.merge(s, 1, Integer::sum);
                inc("placed");
            }
        }
        return out;
    }

    /** Meilleure copie (coût puis latence puis indice) pour servir ex avec gb lus. */
    private static double[] best(MultiCloud cloud, Set<Integer> copies, int extra, int ex, double gb) {
        int c = -1;
        double bc = 0, bl = 0;
        for (int x : copies) {
            double tc = cloud.transferCost(x, ex, gb), tl = cloud.latency(x, ex, gb);
            if (c < 0 || tc < bc - 1e-15 || (Math.abs(tc - bc) <= 1e-15 && (tl < bl || (tl == bl && x < c)))) {
                c = x; bc = tc; bl = tl;
            }
        }
        if (extra >= 0) {
            double tc = cloud.transferCost(extra, ex, gb), tl = cloud.latency(extra, ex, gb);
            if (tc < bc - 1e-15 || (Math.abs(tc - bc) <= 1e-15 && tl < bl)) { c = extra; bc = tc; bl = tl; }
        }
        return new double[]{c, bc, bl};
    }

    /** Une itération de P2 : meilleur site, ou null si « aucun réplica » est retenu. */
    private Integer select(ReplicaManager eng, Query q, int execSite, int rk, double selQ, double[] lam, double tot,
                           double[] wts, Set<Integer> copies, double wRate, int[] execs, double nowH) {
        inc("decisions");
        MultiCloud cloud = eng.cloud;
        double size = eng.cat.size[rk];
        double rd = size * eng.avgSel(rk);              // volume lu par accès (moyenne observée)
        String cls = eng.cat.cls[rk];
        int prim = eng.cat.primary[rk];
        // parts de SLA par accès pour la requête déclenchante
        double[] sla = eng.sla.get(q.kind);
        int nRel = Math.max(1, q.rels.length);
        double tslaShare = (sla[0] - cfg.cpuTimeMs(q.kind)) / nRel;
        double cslaShare = (sla[1] - cloud.cpuCost(execSite, cfg.cloudletLength(q.kind))) / nRel;
        double[] wsla = eng.sla.get("write");
        Double budget = eng.budgetPerDay();
        int day = eng.cycle;
        double hInDay = nowH - day * Math.floor(nowH / day), hLeft = day - hInDay;
        double projected = Double.NaN;
        if (budget != null) {
            double rate = hInDay >= 1 ? eng.daySpent() / hInDay : budget / day;
            projected = eng.daySpent() + rate * hLeft;
        }
        double readsH = Math.max(tot * H, 1.0);          // lectures prévues sur τ (≥ 1)
        double[] wk = weights(budget, projected);
        int K = wk.length;
        List<Integer> cand = new ArrayList<>();
        List<double[]> objs = new ArrayList<>();
        double baseAccPerH = 0;
        for (int s = -1; s < cloud.n; s++) {             // -1 = aucun nouveau réplica
            if (s >= 0 && copies.contains(s)) continue;
            if (s >= 0) inc("candidates");
            double acc = 0, rt = 0, bw = 0;
            for (int r = 0; r < 3; r++) {
                double[] b = best(cloud, copies, s, execs[r], rd);
                acc += lam[r] * b[1];
                rt += wts[r] * b[2];
                bw += lam[r] * rd * (cloud.level((int) b[0], execs[r]) != MultiCloud.Level.LOCAL ? 1 : 0);
            }
            double accPerH = acc;
            if (s < 0) baseAccPerH = accPerH;
            acc *= H;
            bw *= H;
            double create = 0, store = 0, sync = 0, copyH = 0, sat = 0;
            if (s >= 0 && opt.satCapacity > 0) {
                // C6 (saturation) : capacité du site ; 5e critère = occupation après placement
                int load = eng.hostedAt(s) + pendingHost.getOrDefault(s, 0);
                if (load >= opt.satCapacity) { inc("rejected_saturation"); continue; }
                sat = (load + 1.0) / opt.satCapacity;
            }
            if (s >= 0) {
                int src = -1;
                double bestC = 0;
                for (int x : copies) {
                    double cc = cloud.transferCost(x, s, size);
                    if (src < 0 || cc < bestC) { src = x; bestC = cc; }
                }
                create = bestC + cloud.io[s] * size;
                store = cloud.storageCost(size, H * eng.periodHours());
                copyH = cloud.latency(src, s, size) / (1000.0 * eng.periodSeconds);   // utilisable après sa copie
                Consistency.Result p3 = null;
                if (useP3) {
                    p3 = Consistency.evaluate(cfg, cloud, cls, wRate, prim, s, size, eng.cat.deltaFrac, H);
                    sync = p3.syncCost;
                    rt += p3.latencyPenalty * wRate / (tot + wRate + 1e-9);
                }
                if (constrained) {
                    // 1-2. parts de TSLA et de CSLA pour l'accès de la requête déclenchante
                    double rdq = size * selQ;
                    double[] b = best(cloud, copies, s, execSite, rdq);
                    double syncShare = useP3 ? sync / readsH : 0.0;
                    lastSyncShare.put(((long) rk << 8) | s, syncShare);
                    if (b[2] > tslaShare + EPS) { inc("rejected_TSLA"); continue; }
                    if (b[1] + syncShare > cslaShare + EPS) { inc("rejected_CSLA"); continue; }
                    // 3. écritures synchrones (Critical) : la latence vers le nouveau réplica s'ajoute
                    if (wsla != null && cls.equals("Critical")
                            && cloud.latency(prim, s, size * eng.cat.deltaFrac) > wsla[0] - cfg.cpuTimeMs("write") + EPS) {
                        inc("rejected_write_TSLA");
                        continue;
                    }
                    // 4. budget journalier projeté
                    if (budget != null) {
                        double dDay = create + hLeft * (store / H + sync / H + accPerH) - hLeft * baseAccPerH;
                        if (dDay > 0 && projected + dDay > budget + EPS) { inc("rejected_budget"); continue; }
                    }
                    // 5. amortissement sur τ (copie comprise)
                    double accWith = accPerH * (H - copyH) + baseAccPerH * copyH;
                    if (create + store + accWith + sync > baseAccPerH * H + EPS) { inc("rejected_amortization"); continue; }
                } else if (create + store + sync > eng.budgetRemaining()) {
                    continue;                                 // mode non contraint : coût ≤ budget restant
                }
            }
            cand.add(s);
            objs.add(K == 5 ? new double[]{create + store + acc, rt, bw, sync, sat}
                    : new double[]{create + store + acc, rt, bw, sync});
        }
        List<Integer> ok = new ArrayList<>();
        if (constrained) {
            for (int i = 0; i < objs.size(); i++) ok.add(i);
            if (ok.size() == 1 && cloud.n - copies.size() > 0) inc("no_feasible_site");
        } else {
            double base = objs.get(0)[0] + objs.get(0)[3];
            for (int i = 0; i < objs.size(); i++) if (i == 0 || objs.get(i)[0] + objs.get(i)[3] <= base) ok.add(i);
        }
        // front de Pareto (politique « U seul » : tous les candidats admissibles)
        List<Integer> front = new ArrayList<>();
        if (opt.weightedOnly) front.addAll(ok);
        else for (int i : ok) {
            boolean dominated = false;
            for (int j : ok) {
                if (j == i) continue;
                boolean le = true, lt = false;
                for (int k = 0; k < K; k++) {
                    if (objs.get(j)[k] > objs.get(i)[k]) le = false;
                    if (objs.get(j)[k] < objs.get(i)[k]) lt = true;
                }
                if (le && lt) { dominated = true; break; }
            }
            if (!dominated) front.add(i);
        }
        if (front.size() > 1) inc("front_multi");          // vrai arbitrage entre plusieurs candidats
        double[] lo = new double[K], hi = new double[K];
        java.util.Arrays.fill(lo, Double.POSITIVE_INFINITY);
        java.util.Arrays.fill(hi, Double.NEGATIVE_INFINITY);
        for (int i : ok) for (int k = 0; k < K; k++) {
            lo[k] = Math.min(lo[k], objs.get(i)[k]);
            hi[k] = Math.max(hi[k], objs.get(i)[k]);
        }
        int best = -1;
        double bu = 0;
        boolean tie = false;
        for (int i : front) {
            double u = 0;
            for (int k = 0; k < K; k++) {
                double rng = hi[k] - lo[k];
                u += wk[k] * (objs.get(i)[k] - lo[k]) / (rng > 1e-12 ? rng : 1.0);
            }
            if (best < 0) { best = i; bu = u; continue; }
            if (!constrained) {
                if (u < bu) { best = i; bu = u; }
                continue;
            }
            if (u < bu - EPS) { best = i; bu = u; tie = false; continue; }
            if (u > bu + EPS) continue;
            tie = true;
            double[] a = objs.get(i), b = objs.get(best);
            double ca = a[0] + a[3], cb = b[0] + b[3];
            if (ca < cb - EPS || (Math.abs(ca - cb) <= EPS && (a[1] < b[1] - EPS
                    || (Math.abs(a[1] - b[1]) <= EPS && a[3] < b[3] - EPS)))) { best = i; bu = u; }
            // à égalité complète, le candidat déjà retenu (aucun réplica, puis ordre Table 1) est conservé
        }
        if (tie) inc("ties");
        int s = cand.get(best);
        return s < 0 ? null : s;
    }
}
