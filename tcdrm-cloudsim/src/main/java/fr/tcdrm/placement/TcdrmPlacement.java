package fr.tcdrm.placement;

import fr.tcdrm.core.ReplicaManager;
import fr.tcdrm.workload.Query;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Algorithme 2 de l'article : premier site (ordre firstProvider / nextProvider) qui respecte CSLA puis TSLA. */
public final class TcdrmPlacement implements PlacementStrategy {

    @Override
    public String name() { return "TCDRM"; }

    @Override
    public List<int[]> place(ReplicaManager eng, Query q, int execSite, List<Integer> RD, double nowH) {
        double[] sla = eng.sla.get(q.kind);
        List<int[]> out = new ArrayList<>();
        for (int rk : RD) {
            for (int pi = 0; pi < eng.cloud.n; pi++) {
                if (eng.copySet(rk).contains(pi)) continue;
                Map<Integer, Integer> extra = new HashMap<>();
                for (int d : RD) if (!eng.copySet(d).contains(pi)) extra.put(d, pi);
                double[] e = eng.estimate(q, execSite, extra);
                if (e[0] < sla[1] && e[1] < sla[0]) {
                    out.add(new int[]{rk, pi});
                    break;
                }
            }
        }
        return out;
    }
}
