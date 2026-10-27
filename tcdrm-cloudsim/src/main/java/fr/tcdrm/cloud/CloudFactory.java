package fr.tcdrm.cloud;

import fr.tcdrm.config.SimConfig;
import org.cloudbus.cloudsim.CloudletSchedulerTimeShared;
import org.cloudbus.cloudsim.Datacenter;
import org.cloudbus.cloudsim.DatacenterCharacteristics;
import org.cloudbus.cloudsim.Host;
import org.cloudbus.cloudsim.Pe;
import org.cloudbus.cloudsim.Storage;
import org.cloudbus.cloudsim.Vm;
import org.cloudbus.cloudsim.VmAllocationPolicySimple;
import org.cloudbus.cloudsim.VmSchedulerTimeShared;
import org.cloudbus.cloudsim.provisioners.BwProvisionerSimple;
import org.cloudbus.cloudsim.provisioners.PeProvisionerSimple;
import org.cloudbus.cloudsim.provisioners.RamProvisionerSimple;

import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

/** Construit les 9 datacenters CloudSim (un par couple fournisseur × région) et les VM. */
public final class CloudFactory {

    private static final Map<String, Double> TIME_ZONE = Map.of("US", -6.0, "UE", 1.0, "AS", 8.0);

    private CloudFactory() { }

    /** Crée les datacenters ; l'élément i correspond au site i de {@link MultiCloud}. */
    public static Datacenter[] createDatacenters(SimConfig c, MultiCloud cloud) throws Exception {
        Datacenter[] dcs = new Datacenter[cloud.n];
        double mips = c.dbl("vm.mips");
        int nHosts = c.integer("hosts.perDatacenter"), pes = c.integer("host.pes");
        for (int i = 0; i < cloud.n; i++) {
            List<Host> hosts = new ArrayList<>();
            for (int h = 0; h < nHosts; h++) {
                List<Pe> peList = new ArrayList<>();
                for (int p = 0; p < pes; p++) peList.add(new Pe(p, new PeProvisionerSimple(mips)));
                hosts.add(new Host(h, new RamProvisionerSimple(c.integer("host.ram")),
                        new BwProvisionerSimple(c.lng("host.bw")), c.lng("host.storage"), peList,
                        new VmSchedulerTimeShared(peList)));
            }
            // Coûts déclarés à CloudSim (informatifs) ; la facturation TCDRM suit la Table 1 (MultiCloud).
            DatacenterCharacteristics ch = new DatacenterCharacteristics("x86", "Linux", "Xen", hosts,
                    TIME_ZONE.getOrDefault(cloud.region(i), 0.0), 0.0, 0.0,
                    c.dbl("storage.price.gbMonth"), cloud.intra[i]);
            dcs[i] = new Datacenter(cloud.name(i), ch, new VmAllocationPolicySimple(hosts),
                    new LinkedList<Storage>(), 0);
        }
        return dcs;
    }

    public static Vm createVm(SimConfig c, int id, int brokerId) {
        return new Vm(id, brokerId, c.dbl("vm.mips"), 1, c.integer("vm.ram"), c.lng("vm.bw"), c.lng("vm.size"),
                "Xen", new CloudletSchedulerTimeShared());
    }
}
