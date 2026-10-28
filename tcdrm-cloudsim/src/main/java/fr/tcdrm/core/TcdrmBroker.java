package fr.tcdrm.core;

import fr.tcdrm.cloud.MultiCloud;
import fr.tcdrm.workload.Query;
import org.cloudbus.cloudsim.*;
import org.cloudbus.cloudsim.core.CloudActionTags;
import org.cloudbus.cloudsim.core.CloudSim;
import org.cloudbus.cloudsim.core.CloudSimTags;
import org.cloudbus.cloudsim.core.GuestEntity;
import org.cloudbus.cloudsim.core.SimEvent;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Broker du locataire : une requête = un événement QUERY_ARRIVAL puis un cloudlet,
 * soumis après le temps de rapatriement des données.
 */
public final class TcdrmBroker extends DatacenterBroker {

    enum Tags implements CloudSimTags { QUERY_ARRIVAL, PERIOD_END, REPLICA_READY }

    private final ReplicaManager mgr;
    private final List<Query> queries;
    private final Map<Integer, Integer> vmTargetDc;          // vmId -> id d'entité du datacenter
    private final Map<String, List<Vm>> vmsByRegion;
    private final Map<String, Integer> rr = new HashMap<>();
    private final int priceHour;
    private final Consumer<MultiCloud> priceChange;
    private final Map<Integer, Object[]> inflight = new HashMap<>();
    private boolean started = false;
    private int returned = 0;
    /** Copies de réplicas en cours : la simulation ne s'arrête qu'une fois toutes arrivées. */
    private int pendingCopies = 0;
    private boolean finished = false;
    private int nextCloudletId = 0;

    public TcdrmBroker(String name, ReplicaManager mgr, List<Query> queries, Map<Integer, Integer> vmTargetDc,
                       Map<String, List<Vm>> vmsByRegion, int priceHour, Consumer<MultiCloud> priceChange)
            throws Exception {
        super(name);
        this.mgr = mgr;
        this.queries = queries;
        this.vmTargetDc = vmTargetDc;
        this.vmsByRegion = vmsByRegion;
        this.priceHour = priceHour;
        this.priceChange = priceChange;
    }

    /** Chaque VM est créée dans le datacenter d'exécution de sa région (et non dans le premier venu). */
    @Override
    protected void createVmsInDatacenter(int ignored) {
        int requested = 0;
        for (GuestEntity vm : getGuestList()) {
            if (!getVmsToDatacentersMap().containsKey(vm.getId())) {
                int dc = vmTargetDc.get(vm.getId());
                sendNow(dc, CloudActionTags.VM_CREATE_ACK, vm);
                if (!getDatacenterRequestedIdsList().contains(dc)) getDatacenterRequestedIdsList().add(dc);
                requested++;
            }
        }
        setVmsRequested(requested);
        setVmsAcks(0);
    }

    @Override
    protected void submitCloudlets() {
        if (started) return;
        if (getGuestsCreatedList().size() != getGuestList().size()) {
            throw new IllegalStateException("VM non créées : " + getGuestsCreatedList().size() + "/" + getGuestList().size());
        }
        started = true;
        double now = CloudSim.clock();
        double lastH = queries.get(queries.size() - 1).tHours;
        double ps = mgr.periodSeconds;
        for (Query q : queries) schedule(getId(), Math.max(0, q.tHours * ps - now), Tags.QUERY_ARRIVAL, q);
        for (int p = 0; p + 1 <= Math.floor(lastH); p++) {
            schedule(getId(), (p + 1) * ps - now, Tags.PERIOD_END, p);
        }
    }

    @Override
    protected void processOtherEvent(SimEvent ev) {
        CloudSimTags tag = ev.getTag();
        if (tag == Tags.QUERY_ARRIVAL) {
            onArrival((Query) ev.getData());
        } else if (tag == Tags.PERIOD_END) {
            int p = (Integer) ev.getData();
            mgr.endPeriod(p, nextQid());
            if (priceChange != null && p + 1 == priceHour) priceChange.accept(mgr.cloud);
        } else if (tag == Tags.REPLICA_READY) {
            int[] rs = (int[]) ev.getData();
            mgr.replicaReady(rs[0], rs[1]);
            pendingCopies--;
            maybeFinish();
        } else {
            super.processOtherEvent(ev);
        }
    }

    private int nextQid() {
        int done = returned + inflight.size();
        return done < queries.size() ? queries.get(done).qid : queries.get(queries.size() - 1).qid;
    }

    private void onArrival(Query q) {
        ReplicaManager.Routing r = mgr.route(q);
        List<Vm> vms = vmsByRegion.get(q.origin);
        int k = rr.merge(q.origin, 1, Integer::sum) - 1;
        Vm vm = vms.get(k % vms.size());
        int id = nextCloudletId++;
        UtilizationModelFull full = new UtilizationModelFull();
        Cloudlet cl = new Cloudlet(id, mgr.cfg.cloudletLength(q.kind), 1, 300, 300, full, full, full);
        cl.setUserId(getId());
        cl.setGuestId(vm.getId());
        inflight.put(id, new Object[]{q, r, CloudSim.clock()});
        // les données distantes sont rapatriées avant l'exécution
        Log.printlnConcat(CloudSim.clock(), ": ", getName(), ": requête ", q.qid, " (", q.kind, ") → Cloudlet #", id,
                " → VM #", vm.getId(), " @ DC #", getVmsToDatacentersMap().get(vm.getId()),
                ", rapatriement des données ", String.format("%.1f", r.dataLatencyMs), " ms");
        schedule(getVmsToDatacentersMap().get(vm.getId()), r.dataLatencyMs / 1000.0, CloudActionTags.CLOUDLET_SUBMIT, cl);
        cloudletsSubmitted++;
    }

    @Override
    protected void processCloudletReturn(SimEvent ev) {
        Cloudlet cl = (Cloudlet) ev.getData();
        getCloudletReceivedList().add(cl);
        Object[] inf = inflight.remove(cl.getCloudletId());
        cloudletsSubmitted--;
        Query q = (Query) inf[0];
        ReplicaManager.Routing r = (ReplicaManager.Routing) inf[1];
        double rtMs = (CloudSim.clock() - (Double) inf[2]) * 1000.0;
        Log.printlnConcat(CloudSim.clock(), ": ", getName(), ": Cloudlet #", cl.getCloudletId(), " return received (requête ",
                q.qid, ", temps de réponse ", String.format("%.1f", rtMs), " ms)");
        Log.printlnConcat(CloudSim.clock(), ": ", getName(), ": The number of finished Cloudlets is:", returned + 1);
        double nowH = CloudSim.clock() / mgr.periodSeconds;
        for (ReplicaManager.Creation c : mgr.complete(q, r, rtMs, nowH)) {
            Log.printlnConcat(CloudSim.clock(), ": ", getName(), ": création du réplica de la relation ", c.rel,
                    " sur le site ", c.site, ", prêt dans ", String.format("%.1f", c.latencyMs), " ms");
            schedule(getId(), c.latencyMs / 1000.0, Tags.REPLICA_READY, new int[]{c.rel, c.site});
            pendingCopies++;
        }
        returned++;
        maybeFinish();
    }

    private void maybeFinish() {
        if (finished || returned < queries.size() || pendingCopies > 0) return;
        finished = true;
        Query last = queries.get(queries.size() - 1);
        mgr.finish(last.tHours, last.qid, (int) Math.ceil(last.tHours / mgr.cycle));
        clearDatacenters();
        finishExecution();
    }
}
