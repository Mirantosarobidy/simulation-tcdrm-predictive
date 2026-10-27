package fr.tcdrm.workload;

/** Catalogue des relations : taille, site primaire, classe de cohérence. */
public final class Catalog {
    public final double[] size;     // GB
    public final int[] primary;     // site primaire
    public final String[] cls;      // Critical | ReadMostly | Eventual
    public double deltaFrac = 0.02; // taille d'une mise à jour (fraction de la relation)

    public Catalog(double[] size, int[] primary, String[] cls) {
        this.size = size;
        this.primary = primary;
        this.cls = cls;
    }

    public int n() { return size.length; }
}
