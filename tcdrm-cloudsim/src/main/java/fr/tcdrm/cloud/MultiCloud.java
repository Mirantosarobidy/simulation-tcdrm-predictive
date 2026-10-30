package fr.tcdrm.cloud;

import fr.tcdrm.config.SimConfig;

import java.util.HashMap;
import java.util.Map;

/** 3 fournisseurs × 3 régions ; prix mutables pour les variations de S3. */
public final class MultiCloud {

    public enum Level { LOCAL, REGION, PROVIDER }

    public final SimConfig cfg;
    public final int n;
    private final String[] prov;
    private final String[] reg;
    public final double[] cpu;          // $ / 10^6 MI
    public final double[] io;           // $ / GB
    public final double[] intra;        // $ / GB (prix d'entrée dans le site)
    public final Map<String, Double> interRegion = new HashMap<>();
    public final Map<String, Double> interProvider = new HashMap<>();
    /**
     * Prix détaillés (optionnels, prix publics) : sortie inter-région par paire de régions
     * (bw.interRegion.P.R1-R2) et sortie vers un autre fournisseur par région source
     * (bw.interProvider.P.R). À défaut, le prix par fournisseur de la Table 1 s'applique.
     */
    private final Map<String, Double> interRegionPair = new HashMap<>();
    private final Map<String, Double> interProviderRegion = new HashMap<>();
    /** Facteurs appliqués lors d'une variation des prix (1 = prix de départ). */
    private final Map<String, Double> regionScale = new HashMap<>(), providerScale = new HashMap<>();
    private final Map<String, Integer> execOfRegion = new HashMap<>();
    private final double[][] geo;
    private final double storagePrice;
    private final double[] storageSite;   // $/GB/mois par site (storage.price.P.R, sinon prix unique)
    private final double latLocalBase, latLocalPerGb, latRegionPerGb, latProvOverhead, latProvPerGb;

    public MultiCloud(SimConfig cfg) {
        this.cfg = cfg;
        this.n = cfg.nSites();
        prov = new String[n];
        reg = new String[n];
        cpu = new double[n];
        io = new double[n];
        intra = new double[n];
        for (String p : cfg.providers) {
            for (String r : cfg.regions) {
                int i = cfg.siteIndex(p, r);
                prov[i] = p;
                reg[i] = r;
                cpu[i] = cfg.dbl("cpu." + p + "." + r);
                io[i] = cfg.dbl("io." + p + "." + r);
                intra[i] = cfg.dbl("bw.intra." + r);
            }
            interRegion.put(p, cfg.dbl("bw.interRegion." + p));
            interProvider.put(p, cfg.dbl("bw.interProvider." + p));
            for (String r : cfg.regions) {
                if (cfg.has("bw.interProvider." + p + "." + r)) interProviderRegion.put(p + "." + r, cfg.dbl("bw.interProvider." + p + "." + r));
                for (String r2 : cfg.regions) {
                    String k = "bw.interRegion." + p + "." + r + "-" + r2;
                    if (!r.equals(r2) && cfg.has(k)) interRegionPair.put(p + "." + r + "-" + r2, cfg.dbl(k));
                }
            }
        }
        geo = new double[n][n];
        for (int a = 0; a < n; a++) for (int b = 0; b < n; b++) geo[a][b] = geoLookup(a, b);
        storagePrice = cfg.dbl("storage.price.gbMonth");
        storageSite = new double[n];
        for (int i = 0; i < n; i++) {
            String k = "storage.price." + prov[i] + "." + reg[i];
            storageSite[i] = cfg.has(k) ? cfg.dbl(k) : storagePrice;
        }
        latLocalBase = cfg.dbl("lat.local.base");
        latLocalPerGb = cfg.dbl("lat.local.perGb");
        latRegionPerGb = cfg.dbl("lat.region.perGb");
        latProvOverhead = cfg.dbl("lat.provider.overhead");
        latProvPerGb = cfg.dbl("lat.provider.perGb");
        recompute();
    }

    /** VM d'exécution : la moins chère (CPU) de chaque région (hypothèse §4 de l'article). */
    private void recompute() {
        for (String r : cfg.regions) {
            int best = -1;
            for (int i = 0; i < n; i++) {
                if (reg[i].equals(r) && (best < 0 || cpu[i] < cpu[best])) best = i;
            }
            execOfRegion.put(r, best);
        }
    }

    public void setInterRegion(String provider, double price) { interRegion.put(provider, price); recompute(); }

    public void setInterProvider(String provider, double price) { interProvider.put(provider, price); recompute(); }

    /** Variation des prix : multiplie la sortie inter-région (ou vers un autre fournisseur) d'un fournisseur. */
    public void scaleInterRegion(String provider, double f) { regionScale.merge(provider, f, (a, b) -> a * b); }

    public void scaleInterProvider(String provider, double f) { providerScale.merge(provider, f, (a, b) -> a * b); }

    /** Prix ($/Go) de la sortie de src vers une autre région du même fournisseur. */
    public double interRegionPrice(int src, int dst) {
        Double v = interRegionPair.get(prov[src] + "." + reg[src] + "-" + reg[dst]);
        return (v != null ? v : interRegion.get(prov[src])) * regionScale.getOrDefault(prov[src], 1.0);
    }

    /** Prix ($/Go) de la sortie de src vers un autre fournisseur. */
    public double interProviderPrice(int src) {
        Double v = interProviderRegion.get(prov[src] + "." + reg[src]);
        return (v != null ? v : interProvider.get(prov[src])) * providerScale.getOrDefault(prov[src], 1.0);
    }

    public int execOf(String region) { return execOfRegion.get(region); }

    public String provider(int i) { return prov[i]; }

    public String region(int i) { return reg[i]; }

    public String name(int i) { return prov[i] + "-" + reg[i]; }

    public String reg(int i) { return reg[i]; }

    /** Noms des sites dans l'ordre fixe de la Table 1. */
    public java.util.List<String> siteNames() {
        java.util.List<String> out = new java.util.ArrayList<>();
        for (int i = 0; i < n; i++) out.add(name(i));
        return out;
    }

    public Level level(int a, int b) {
        if (a == b) return Level.LOCAL;
        return prov[a].equals(prov[b]) ? Level.REGION : Level.PROVIDER;
    }

    private double geoLookup(int a, int b) {
        if (reg[a].equals(reg[b])) return 0.0;
        String k1 = reg[a] + "-" + reg[b], k2 = reg[b] + "-" + reg[a];
        try {
            return cfg.dbl("geo." + k1);
        } catch (IllegalArgumentException e) {
            return cfg.dbl("geo." + k2);
        }
    }

    /** Coût d'un transfert de gb GB de src vers dst (Table 1 ; entrée intra-DC incluse). */
    public double transferCost(int src, int dst, double gb) {
        switch (level(src, dst)) {
            case LOCAL:
                return intra[dst] * gb;
            case REGION:
                return interRegionPrice(src, dst) * gb + intra[dst] * gb;
            default:
                return interProviderPrice(src) * gb + intra[dst] * gb;
        }
    }

    /** Latence d'un transfert (ms), modèle hiérarchique local / inter-région / inter-fournisseur. */
    public double latency(int src, int dst, double gb) {
        switch (level(src, dst)) {
            case LOCAL:
                return latLocalBase + latLocalPerGb * gb;
            case REGION:
                return geo[src][dst] + latRegionPerGb * gb;
            default:
                return geo[src][dst] + latProvOverhead + latProvPerGb * gb;
        }
    }

    public double cpuCost(int site, long mi) { return cpu[site] * mi / 1e6; }

    public double storageCost(double gb, double hours) {
        return gb * storagePrice * hours / (30 * 24);
    }

    public double storagePrice(int site) { return storageSite[site]; }

    /** Stockage sur un site donné (prix du fournisseur et de la région s'ils sont renseignés). */
    public double storageCost(int site, double gb, double hours) {
        return gb * storageSite[site] * hours / (30 * 24);
    }
}
