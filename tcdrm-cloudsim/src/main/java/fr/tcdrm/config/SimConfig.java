package fr.tcdrm.config;

import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

/** Paramètres lus depuis tcdrm.properties (ou -Dtcdrm.config=chemin). */
public final class SimConfig {

    public final List<String> providers;
    public final List<String> regions;
    private final Properties p;

    /** Seuils SLA par type de requête : {TSLA (ms), CSLA ($)}. */
    public final Map<String, double[]> sla = new HashMap<>();

    private SimConfig(Properties p) {
        this.p = p;
        this.providers = Arrays.asList(str("providers").split(","));
        this.regions = Arrays.asList(str("regions").split(","));
        for (String k : new String[]{"simple", "complex", "write"}) {
            sla.put(k, new double[]{dbl("sla." + k + ".TSLA"), dbl("sla." + k + ".CSLA")});
        }
    }

    public static SimConfig load() {
        Properties p = new Properties();
        String path = System.getProperty("tcdrm.config");
        try (InputStream in = path != null ? new FileInputStream(path)
                : SimConfig.class.getResourceAsStream("/tcdrm.properties")) {
            if (in == null) throw new IllegalStateException("tcdrm.properties introuvable");
            p.load(new InputStreamReader(in, StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        return new SimConfig(p);
    }

    public String str(String k) {
        String v = p.getProperty(k);
        if (v == null) throw new IllegalArgumentException("Paramètre manquant : " + k);
        return v.trim();
    }

    public boolean has(String k) { return p.getProperty(k) != null; }

    /** Copie de la configuration avec quelques paramètres remplacés (études de scalabilité). */
    public SimConfig with(Map<String, String> overrides) {
        Properties q = new Properties();
        q.putAll(p);
        overrides.forEach(q::setProperty);
        return new SimConfig(q);
    }

    public double dbl(String k) { return Double.parseDouble(str(k)); }

    public int integer(String k) { return Integer.parseInt(str(k)); }

    public long lng(String k) { return Long.parseLong(str(k)); }

    public double[] doubles(String k) {
        return Arrays.stream(str(k).split(",")).mapToDouble(s -> Double.parseDouble(s.trim())).toArray();
    }

    public int nSites() { return providers.size() * regions.size(); }

    public int siteIndex(String provider, String region) {
        return providers.indexOf(provider) * regions.size() + regions.indexOf(region);
    }

    public long cloudletLength(String kind) { return lng("cloudlet." + kind); }

    /** Temps CPU estimé (ms) = longueur / MIPS. */
    public double cpuTimeMs(String kind) { return 1000.0 * cloudletLength(kind) / dbl("vm.mips"); }
}
