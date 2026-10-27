package fr.tcdrm.workload;

/** Requête d'un locataire : lecture (simple / complexe) ou écriture. */
public final class Query {
    public final int qid;
    public final double tHours;     // instant d'arrivée (heures simulées)
    public final String kind;       // simple | complex | write
    public final int[] rels;        // relations lues
    public final double[] sel;      // fraction lue de chaque relation (null = relation entière)
    public final String origin;     // région d'origine
    public final int writeRel;      // relation modifiée (écriture)
    public final int template;

    public Query(int qid, double tHours, String kind, int[] rels, double[] sel, String origin,
                 int writeRel, int template) {
        this.qid = qid;
        this.tHours = tHours;
        this.kind = kind;
        this.rels = rels;
        this.sel = sel;
        this.origin = origin;
        this.writeRel = writeRel;
        this.template = template;
    }

    public boolean isWrite() { return "write".equals(kind); }

    public double sel(int k) { return sel == null ? 1.0 : sel[k]; }
}
