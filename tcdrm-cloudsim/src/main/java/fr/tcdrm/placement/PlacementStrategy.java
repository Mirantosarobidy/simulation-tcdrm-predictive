package fr.tcdrm.placement;

import fr.tcdrm.core.ReplicaManager;
import fr.tcdrm.workload.Query;

import java.util.List;

/** Décision « où placer » appelée par l'algorithme 1 quand il déclenche une réplication. */
public interface PlacementStrategy {

    String name();

    /**
     * @param eng      état courant de la réplication
     * @param q        requête qui a violé TSLA ou CSLA
     * @param execSite site d'exécution de q
     * @param RD       relations populaires non locales (ensemble RD de l'algorithme 1)
     * @param nowH     instant courant (heures simulées)
     * @return paires {relation, site} à répliquer
     */
    List<int[]> place(ReplicaManager eng, Query q, int execSite, List<Integer> RD, double nowH);

    /** Vrai si les réplicas placés respectent par construction les parts de TSLA/CSLA par accès. */
    default boolean guaranteed() { return false; }

    /** Part de synchronisation par lecture estimée lors du placement de (rk, site), en $. */
    default double syncSharePerRead(ReplicaManager eng, int rk, int site) { return 0.0; }

    /**
     * Appelé par l'algorithme 3 avant de supprimer le réplica (d, site) à la fin de la période p.
     * Vrai = conserver le réplica (anti-thrashing) ; par défaut, l'algorithme 3 de l'article s'applique.
     */
    default boolean retain(ReplicaManager eng, int d, int site, int p) { return false; }
}
