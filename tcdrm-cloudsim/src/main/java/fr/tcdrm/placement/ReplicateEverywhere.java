package fr.tcdrm.placement;

import fr.tcdrm.core.ReplicaManager;
import fr.tcdrm.workload.Query;

import java.util.ArrayList;
import java.util.List;

/** Témoin : copie la relation sur la VM d'exécution de chaque région dès le déclenchement. */
public final class ReplicateEverywhere implements PlacementStrategy {

    @Override
    public String name() { return "Replicate-All"; }

    @Override
    public List<int[]> place(ReplicaManager eng, Query q, int execSite, List<Integer> RD, double nowH) {
        List<int[]> out = new ArrayList<>();
        for (int rk : RD) {
            for (String r : eng.cfg.regions) {
                int s = eng.cloud.execOf(r);
                if (!eng.copySet(rk).contains(s)) out.add(new int[]{rk, s});
            }
        }
        return out;
    }
}
