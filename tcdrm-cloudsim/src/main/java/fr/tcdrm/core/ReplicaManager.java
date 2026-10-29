package fr.tcdrm.core;

import fr.tcdrm.cloud.MultiCloud;
import fr.tcdrm.config.SimConfig;
import fr.tcdrm.placement.Consistency;
import fr.tcdrm.placement.PlacementStrategy;
import fr.tcdrm.workload.Catalog;
import fr.tcdrm.workload.Query;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/** Algorithmes 1 (création) et 3 (suppression) de TCDRM ; le placement est délégué à une PlacementStrategy. */
public final class ReplicaManager {

    public static final class Replica {
        double createdH;
        boolean ready;
        int hits;
        int below;
        double createCost;
        /** Gain net cumulé ($) : économies de transfert − création − stockage − synchronisation. */
        double net;
        /** Temps (h) entre la création et le premier instant où le gain net devient ≥ 0 ; NaN sinon. */
        double paybackH = Double.NaN;
        boolean guaranteed;
        double syncShare;
    }

    public static final class Routing {
        public int execSite;
        public double cpuCost, bwCost, syncCost;
        public double dataLatencyMs;          // temps de rapatriement des données avant exécution
        public double gbLocal, gbRegion, gbProvider, gbSync;
        public double interCloudCost;         // part non locale des transferts (lecture/écriture)
        public int period;
        public String relations = "";
    }

    public final SimConfig cfg;
    public final MultiCloud cloud;
    public final Catalog cat;
    public final PlacementStrategy placement;
    public final String label;
    public final Map<String, double[]> sla;
    final double psla, delPsla;
    final Integer deltaT;
    final Double budgetPerDay;

    final List<Map<Integer, Replica>> replicas = new ArrayList<>();
    final double[] reqCount, selSum;
    final int[] firstPeriod;
    public final float[][][] counts;     // [rel][period][région] lectures
    public final float[][] wcounts;      // [rel][period] écritures
    public int period = 0;
    /** Durée d'une période (s) et nombre de périodes par cycle de budget. */
    public double periodSeconds = 3600.0;
    public int cycle = 24;

    public void setTimeScale(double periodSeconds, int cycle) {
        this.periodSeconds = periodSeconds;
        this.cycle = cycle;
    }

    public double periodHours() { return periodSeconds / 3600.0; }
    double daySpent = 0;
    final Map<Long, Double> deletedAt = new HashMap<>();

    public final List<Object[]> rows = new ArrayList<>();
    public final List<Map<String, Object>> events = new ArrayList<>();
    public final Map<Integer, Double> extraCost = new HashMap<>();
    public final List<Double> dayLog = new ArrayList<>();
    double storageTotal = 0, createTotal = 0, gbReplication = 0, createInterCloud = 0;
    /** Bilan de chaque réplica : {créé (h), fin (h), coût de création, gain net, retour (h), supprimé 1/0}. */
    public final List<double[]> replicaOutcomes = new ArrayList<>();
    public final List<Integer> activeLog = new ArrayList<>();
    /** Audit des garanties : lectures servies par un réplica placé sous contraintes, et dépassements de part. */
    public long auditReads = 0, auditTViol = 0, auditCViol = 0;
    int nCreated = 0, nDeleted = 0, thrash = 0;
    /** Temps passé dans la décision de placement (ns) et nombre d'appels. */
    public long placeNanos = 0, placeCalls = 0;
    /** Réplicas hébergés par site (création comprise) et charge maximale observée. */
    int[] hosted;
    int maxSiteLoad = 0, retained = 0;
    final java.util.Set<Integer> sitesUsed = new java.util.HashSet<>();

    public ReplicaManager(SimConfig cfg, MultiCloud cloud, Catalog cat, PlacementStrategy placement,
                          String label, int nPeriods, double psla, double delPsla, Integer deltaT,
                          Double budgetPerDay, Map<String, double[]> sla) {
        this.cfg = cfg;
        this.cloud = cloud;
        this.cat = cat;
        this.placement = placement;
        this.label = label;
        this.psla = psla;
        this.delPsla = delPsla;
        this.deltaT = deltaT;
        this.budgetPerDay = budgetPerDay;
        this.sla = sla;
        int n = cat.n();
        for (int i = 0; i < n; i++) replicas.add(new LinkedHashMap<>());
        reqCount = new double[n];
        selSum = new double[n];
        firstPeriod = new int[n];
        java.util.Arrays.fill(firstPeriod, -1);
        counts = new float[n][nPeriods + 1][3];
        hosted = new int[cloud.n];
        wcounts = new float[n][nPeriods + 1];
    }

    // état
    /** Copies existantes ou en cours de création (évite les doublons de placement). */
    public Set<Integer> copySet(int d) {
        Set<Integer> s = new TreeSet<>(replicas.get(d).keySet());
        s.add(cat.primary[d]);
        return s;
    }

    /** Copies utilisables pour servir une lecture (primaire + réplicas prêts). */
    Set<Integer> readyCopies(int d) {
        Set<Integer> s = new TreeSet<>();
        s.add(cat.primary[d]);
        for (Map.Entry<Integer, Replica> e : replicas.get(d).entrySet()) if (e.getValue().ready) s.add(e.getKey());
        return s;
    }

    public int nReplicas() {
        int k = 0;
        for (Map<Integer, Replica> m : replicas) k += m.size();
        return k;
    }

    public double avgSel(int d) { return reqCount[d] > 0 ? selSum[d] / reqCount[d] : 1.0; }

    /** Équation 1 de l'article : #Requests / (T_current − T_first + 1), T en périodes d'une heure. */
    public double popularity(int d) {
        if (firstPeriod[d] < 0) return 0.0;
        return reqCount[d] / (period - firstPeriod[d] + 1);
    }

    public double daySpent() { return daySpent; }

    /** Nombre de réplicas hébergés par le site (saturation des fournisseurs). */
    public int hostedAt(int site) { return hosted[site]; }

    /** Seuil de suppression de l'algorithme 3 (accès par période). */
    public double deletionThreshold() { return delPsla; }

    public Double budgetPerDay() { return budgetPerDay; }

    public double budgetRemaining() {
        return budgetPerDay == null ? Double.POSITIVE_INFINITY : budgetPerDay - daySpent;
    }

    int bestOf(Set<Integer> cands, int exec, double gb) {
        int best = -1;
        double bc = 0, bl = 0;
        for (int c : cands) {
            double tc = cloud.transferCost(c, exec, gb), tl = cloud.latency(c, exec, gb);
            if (best < 0 || tc < bc - 1e-15 || (Math.abs(tc - bc) <= 1e-15 && tl < bl)) {
                best = c; bc = tc; bl = tl;
            }
        }
        return best;
    }

    public int bestCopy(int d, int exec, Integer extra) {
        Set<Integer> c = copySet(d);
        if (extra != null) c.add(extra);
        return bestOf(c, exec, cat.size[d]);
    }

    /** Coût et temps de réponse estimés de q avec des copies hypothétiques supplémentaires. */
    public double[] estimate(Query q, int exec, Map<Integer, Integer> extra) {
        double cost = cloud.cpuCost(exec, cfg.cloudletLength(q.kind));
        double rt = cfg.cpuTimeMs(q.kind);
        for (int k = 0; k < q.rels.length; k++) {
            int d = q.rels[k];
            int c = bestCopy(d, exec, extra == null ? null : extra.get(d));
            double s = cat.size[d] * q.sel(k);
            cost += cloud.transferCost(c, exec, s);
            rt += cloud.latency(c, exec, s);
        }
        return new double[]{cost, rt};
    }

    // arrivée d'une requête : routage, popularité, coûts
    public Routing route(Query q) {
        Routing r = new Routing();
        int exec = cloud.execOf(q.origin);
        int ri = cfg.regions.indexOf(q.origin);
        r.execSite = exec;
        r.period = period;
        r.cpuCost = cloud.cpuCost(exec, cfg.cloudletLength(q.kind));
        if (q.isWrite()) {
            int d = q.writeRel, prim = cat.primary[d];
            double delta = cat.size[d] * cat.deltaFrac;
            r.relations = "W" + d + "@" + cloud.name(prim) + "(" + cat.cls[d] + ")";
            r.bwCost += cloud.transferCost(exec, prim, delta);
            r.dataLatencyMs += cloud.latency(exec, prim, delta);
            addGb(r, cloud.level(exec, prim), delta);
            double worst = 0, f = Consistency.syncFactor(cfg, cat.cls[d]);
            if (cloud.level(exec, prim) != MultiCloud.Level.LOCAL) r.interCloudCost += cloud.transferCost(exec, prim, delta);
            for (Map.Entry<Integer, Replica> re : replicas.get(d).entrySet()) {
                int s = re.getKey();
                double sc = cloud.transferCost(prim, s, delta) * f;
                r.syncCost += sc;
                re.getValue().net -= sc;
                if (cloud.level(prim, s) != MultiCloud.Level.LOCAL) r.gbSync += delta * f;
                worst = Math.max(worst, cloud.latency(prim, s, delta));
            }
            if (cat.cls[d].equals("Critical")) r.dataLatencyMs += worst;   // écriture synchrone
            wcounts[d][period] += 1;
        } else {
            for (int k = 0; k < q.rels.length; k++) {
                int d = q.rels[k];
                double sel = q.sel(k), s = cat.size[d] * sel;
                selSum[d] += sel;
                Set<Integer> ready = readyCopies(d);
                int c = bestOf(ready, exec, cat.size[d]);
                r.relations += (k > 0 ? ";" : "") + d + "@" + cloud.name(cat.primary[d]) + "<-" + cloud.name(c);
                double tc = cloud.transferCost(c, exec, s);
                r.bwCost += tc;
                if (cloud.level(c, exec) != MultiCloud.Level.LOCAL) r.interCloudCost += tc;
                r.dataLatencyMs += cloud.latency(c, exec, s);
                addGb(r, cloud.level(c, exec), s);
                Replica rep = replicas.get(d).get(c);
                if (rep != null) {
                    rep.hits++;
                    // économie marginale : coût depuis la meilleure autre copie − coût depuis ce réplica
                    ready.remove(c);
                    int alt = bestOf(ready, exec, cat.size[d]);
                    rep.net += cloud.transferCost(alt, exec, s) - tc;
                    checkPayback(rep, q.tHours);
                    if (rep.guaranteed) {
                        double[] sl = sla.get(q.kind);
                        int nRel = q.rels.length;
                        double tShare = (sl[0] - cfg.cpuTimeMs(q.kind)) / nRel;
                        double cShare = (sl[1] - cloud.cpuCost(exec, cfg.cloudletLength(q.kind))) / nRel;
                        auditReads++;
                        if (cloud.latency(c, exec, s) > tShare + 1e-9) auditTViol++;
                        if (tc + rep.syncShare > cShare + 1e-12) auditCViol++;
                    }
                }
                reqCount[d] += 1;
                if (firstPeriod[d] < 0) firstPeriod[d] = period;
                counts[d][period][ri] += 1;
            }
        }
        daySpent += r.cpuCost + r.bwCost + r.syncCost;
        return r;
    }

    private static void checkPayback(Replica rep, double nowH) {
        if (Double.isNaN(rep.paybackH) && rep.net >= 0) rep.paybackH = Math.max(0, nowH - rep.createdH);
    }

    private void recordOutcome(Replica m, double endH, boolean deleted) {
        replicaOutcomes.add(new double[]{m.createdH, endH, m.createCost, m.net, m.paybackH, deleted ? 1 : 0});
    }

    private static void addGb(Routing r, MultiCloud.Level lv, double gb) {
        switch (lv) {
            case LOCAL: r.gbLocal += gb; break;
            case REGION: r.gbRegion += gb; break;
            default: r.gbProvider += gb;
        }
    }

    // fin d'exécution : mesure et algorithme 1
    public static final class Creation {
        public final int rel, site;
        public final double latencyMs;

        Creation(int rel, int site, double latencyMs) {
            this.rel = rel; this.site = site; this.latencyMs = latencyMs;
        }
    }

    public List<Creation> complete(Query q, Routing r, double rtMs, double nowH) {
        double[] s = sla.get(q.kind);
        double cq = r.cpuCost + r.bwCost + r.syncCost;
        boolean violT = rtMs > s[0], violC = cq > s[1];
        List<Creation> out = new ArrayList<>();
        if (placement != null && !q.isWrite() && (violT || violC)) {
            List<Integer> RD = new ArrayList<>();
            for (int d : q.rels) {
                if (popularity(d) > psla && bestCopy(d, r.execSite, null) != r.execSite && !RD.contains(d)) RD.add(d);
            }
            if (!RD.isEmpty()) {
                long t0 = System.nanoTime();
                List<int[]> pls = placement.place(this, q, r.execSite, RD, nowH);
                placeNanos += System.nanoTime() - t0;
                placeCalls++;
                for (int[] pl : pls) {
                    if (!copySet(pl[0]).contains(pl[1])) out.add(createReplica(pl[0], pl[1], q, nowH));
                }
            }
        }
        rows.add(new Object[]{q.qid, q.tHours, q.kind, cfg.regions.indexOf(q.origin), rtMs, r.cpuCost, r.bwCost,
                r.syncCost, r.gbLocal, r.gbRegion, r.gbProvider, r.gbSync, violT, violC, nReplicas(), out.size(),
                r.interCloudCost, r.relations});
        return out;
    }

    Creation createReplica(int d, int site, Query q, double nowH) {
        double size = cat.size[d];
        int src = -1;
        double best = 0;
        for (int x : copySet(d)) {
            double c = cloud.transferCost(x, site, size);
            if (src < 0 || c < best) { src = x; best = c; }
        }
        double cost = best + cloud.io[site] * size;
        Replica rep = new Replica();
        rep.createdH = nowH;
        rep.createCost = cost;
        rep.net = -cost;
        if (placement != null && placement.guaranteed()) {
            rep.guaranteed = true;
            rep.syncShare = placement.syncSharePerRead(this, d, site);
        }
        replicas.get(d).put(site, rep);
        hosted[site]++;
        maxSiteLoad = Math.max(maxSiteLoad, hosted[site]);
        sitesUsed.add(site);
        extraCost.merge(q.qid, cost, Double::sum);
        daySpent += cost;
        createTotal += cost;
        nCreated++;
        if (cloud.level(src, site) != MultiCloud.Level.LOCAL) {
            gbReplication += size;
            createInterCloud += best;
        }
        Double del = deletedAt.get(key(d, site));
        if (del != null && nowH - del <= cycle) thrash++;
        Map<String, Object> ev = new LinkedHashMap<>();
        ev.put("t", nowH); ev.put("qid", q.qid); ev.put("type", "create"); ev.put("rel", d);
        ev.put("site", cloud.name(site)); ev.put("src", cloud.name(src)); ev.put("cost", cost);
        ev.put("level", cloud.level(src, site).name());
        events.add(ev);
        return new Creation(d, site, cloud.latency(src, site, size));
    }

    /** Événement CloudSim REPLICA_READY : la copie est arrivée, elle peut servir des lectures. */
    public void replicaReady(int d, int site) {
        Replica rep = replicas.get(d).get(site);
        if (rep != null) rep.ready = true;
    }

    private static long key(int d, int site) { return ((long) d << 8) | site; }

    // fin de période : stockage, algorithme 3, budget
    public void endPeriod(int p, int nextQid) {
        double st = 0;
        for (int d = 0; d < replicas.size(); d++) {
            for (Replica m : replicas.get(d).values()) {
                double c = cloud.storageCost(cat.size[d], periodHours());
                st += c;
                m.net -= c;
            }
        }
        storageTotal += st;
        extraCost.merge(nextQid, st, Double::sum);
        daySpent += st;
        for (int d = 0; d < replicas.size(); d++) {
            var it = replicas.get(d).entrySet().iterator();
            while (it.hasNext()) {
                var e = it.next();
                Replica m = e.getValue();
                if (deltaT != null) {
                    m.below = m.hits < delPsla ? m.below + 1 : 0;
                    if (m.below >= deltaT && placement != null && placement.retain(this, d, e.getKey(), p)) {
                        retained++;                       // anti-thrashing : la demande doit revenir
                    } else if (m.below >= deltaT) {
                        it.remove();
                        hosted[e.getKey()]--;
                        recordOutcome(m, p + 1, true);
                        deletedAt.put(key(d, e.getKey()), (double) (p + 1));
                        nDeleted++;
                        Map<String, Object> ev = new LinkedHashMap<>();
                        ev.put("t", (double) (p + 1)); ev.put("qid", nextQid); ev.put("type", "delete");
                        ev.put("rel", d); ev.put("site", cloud.name(e.getKey()));
                        events.add(ev);
                    }
                }
                m.hits = 0;
            }
        }
        activeLog.add(nReplicas());
        if ((p + 1) % cycle == 0) {
            dayLog.add(daySpent);
            daySpent = 0;
        }
        period = p + 1;
    }

    /** Stockage de la dernière période (partielle). */
    public void finish(double lastH, int lastQid, int nDays) {
        double frac = Math.max(0, lastH - period), st = 0;
        for (int d = 0; d < replicas.size(); d++) {
            for (Replica m : replicas.get(d).values()) {
                double c = cloud.storageCost(cat.size[d], frac * periodHours());
                st += c;
                m.net -= c;
                recordOutcome(m, lastH, false);
            }
        }
        storageTotal += st;
        if (st > 0) extraCost.merge(lastQid, st, Double::sum);
        daySpent += st;
        // dernière journée (partielle) pour le suivi du budget
        if (budgetPerDay != null && dayLog.size() < nDays) dayLog.add(daySpent);
    }
}
