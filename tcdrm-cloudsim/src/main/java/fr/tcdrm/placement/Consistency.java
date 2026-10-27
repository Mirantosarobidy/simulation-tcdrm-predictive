package fr.tcdrm.placement;

import fr.tcdrm.cloud.MultiCloud;
import fr.tcdrm.config.SimConfig;

/**
 * Algorithme P3 — cohérence et coût de synchronisation.
 * Critical → strong/quorum ; ReadMostly → bounded-staleness ; Eventual → eventual.
 */
public final class Consistency {

    public static final class Result {
        public final String mode;
        public final double syncCost;       // $ sur l'horizon
        public final double latencyPenalty; // ms par écriture (Critical uniquement)

        Result(String mode, double syncCost, double latencyPenalty) {
            this.mode = mode;
            this.syncCost = syncCost;
            this.latencyPenalty = latencyPenalty;
        }
    }

    private Consistency() { }

    public static String mode(String cls) {
        switch (cls) {
            case "Critical": return "strong/quorum";
            case "ReadMostly": return "bounded-staleness";
            default: return "eventual";
        }
    }

    /** Fraction du volume de mise à jour réellement transférée (regroupement, synchro différée). */
    public static double syncFactor(SimConfig c, String cls) {
        return c.dbl("p3.syncFactor." + cls);
    }

    /** Volume de mise à jour valorisé au prix du lien primaire → réplica ; latence prise en compte pour Critical seulement. */
    public static Result evaluate(SimConfig c, MultiCloud cloud, String cls, double wRate, int primary,
                                  int site, double size, double deltaFrac, double horizon) {
        double delta = size * deltaFrac;
        double sync = wRate * horizon * cloud.transferCost(primary, site, delta) * syncFactor(c, cls);
        double pen = cls.equals("Critical") ? cloud.latency(primary, site, delta) : 0.0;
        return new Result(mode(cls), sync, pen);
    }
}
