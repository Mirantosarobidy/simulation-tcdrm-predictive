package fr.tcdrm.charts;

import fr.tcdrm.core.SimulationRunner.Result;
import org.jfree.chart.ChartUtils;
import org.jfree.chart.JFreeChart;
import org.jfree.chart.axis.CategoryAxis;
import org.jfree.chart.axis.NumberAxis;
import org.jfree.chart.labels.StandardCategoryItemLabelGenerator;
import org.jfree.chart.plot.CategoryPlot;
import org.jfree.chart.plot.PlotOrientation;
import org.jfree.chart.renderer.category.BarRenderer;
import org.jfree.chart.renderer.category.StackedBarRenderer;
import org.jfree.chart.renderer.category.StandardBarPainter;
import org.jfree.chart.renderer.category.StatisticalBarRenderer;
import org.jfree.chart.title.TextTitle;
import org.jfree.chart.ui.RectangleEdge;
import org.jfree.data.category.DefaultCategoryDataset;
import org.jfree.data.statistics.DefaultStatisticalCategoryDataset;
import org.knowm.xchart.AnnotationLine;
import org.knowm.xchart.AnnotationText;
import org.knowm.xchart.BitmapEncoder;
import org.knowm.xchart.XYChart;
import org.knowm.xchart.XYChartBuilder;
import org.knowm.xchart.XYSeries;
import org.knowm.xchart.style.Styler;
import org.knowm.xchart.style.markers.SeriesMarkers;

import javax.imageio.ImageIO;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/** Figures des scénarios : XChart (courbes) et JFreeChart (barres). */
public final class Charts {

    static final Color SURF = new Color(0xfcfcfb), INK = new Color(0x0b0b0b), INK2 = new Color(0x52514e),
            GRID = new Color(0xe4e3df);
    public static final Map<String, Color> COL = new LinkedHashMap<>();

    static {
        COL.put("NoRepLc", new Color(0x8a8984));
        COL.put("TCDRM", new Color(0x2a78d6));
        COL.put("TCDRM-Predictive", new Color(0xeb6834));
        COL.put("Replicate-All", new Color(0x6250d6));
        COL.put("P2", new Color(0x1baf7a));
        COL.put("P1+P2", new Color(0xeda100));
        COL.put("P3+P2", new Color(0xe87ba4));
        COL.put("Predictive sans contraintes", new Color(0x008300));
        COL.put("P-U seul", new Color(0x0f8f8f));
        COL.put("P-poids dynamiques", new Color(0xb8860b));
        COL.put("P-saturation", new Color(0x9c27b0));
        COL.put("P-anti-thrashing", new Color(0xd81b60));
        COL.put("TCDRM-Predictive v2", new Color(0x6d3b0f));
    }

    static final Font F_BASE = new Font("SansSerif", Font.PLAIN, 13);
    static final Font F_TITLE = new Font("SansSerif", Font.BOLD, 14);
    static final Font F_SUPER = new Font("SansSerif", Font.BOLD, 17);
    static final Map<String, String> KIND_FR = Map.of("simple", "Requêtes simples", "complex", "Requêtes complexes");
    static final String[] S12 = {"NoRepLc", "TCDRM", "TCDRM-Predictive"};
    /** Stratégies tracées dans les figures de l'article (S1 : NoRepLc et TCDRM ; S2 : + TCDRM-Predictive). */
    private String[] shown = S12;

    private final Path dir;
    /** Préfixe des fichiers et nom des figures du scénario à requêtes aléatoires (S4 ou S5). */
    private String dyn = "s4", dynName = "S4";
    private String p1File = "p1_validation";

    public void setDynamic(String file, String name) { dyn = file; dynName = name; }

    public void setP1File(String file) { p1File = file; }
    /** Échelle de temps de S3 : nombre de périodes, périodes par cycle, requêtes par période. */
    private int nPeriods = 60, cycle = 15, periodQueries = 50;

    public void setTimeScale(int nPeriods, int cycle, int periodQueries) {
        this.nPeriods = nPeriods;
        this.cycle = cycle;
        this.periodQueries = periodQueries;
    }

    public Charts(Path dir) {
        this.dir = dir;
        dir.toFile().mkdirs();
    }

    // utilitaires
    static XYChart xy(int w, int h, String title, String xl, String yl) {
        XYChart c = new XYChartBuilder().width(w).height(h).title(title).xAxisTitle(xl).yAxisTitle(yl).build();
        var s = c.getStyler();
        s.setChartBackgroundColor(SURF);
        s.setPlotBackgroundColor(SURF);
        s.setPlotBorderVisible(false);
        s.setPlotGridLinesColor(GRID);
        s.setChartFontColor(INK);
        s.setAxisTickLabelsColor(INK2);
        s.setBaseFont(F_BASE);
        s.setChartTitleFont(F_TITLE);
        s.setAxisTitleFont(F_BASE);
        s.setAxisTickLabelsFont(F_BASE);
        s.setLegendFont(F_BASE);
        s.setLegendBorderColor(SURF);
        s.setLegendBackgroundColor(new Color(252, 252, 251, 220));
        s.setMarkerSize(0);
        s.setAntiAlias(true);
        s.setAxisTicksLineVisible(false);
        s.setPlotGridVerticalLinesVisible(false);
        s.setLegendPosition(Styler.LegendPosition.InsideNE);
        s.setXAxisTickMarkSpacingHint(110);
        s.setXAxisDecimalPattern("0");
        s.setAnnotationLineColor(INK2);
        s.setAnnotationLineStroke(new BasicStroke(1f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 10f, new float[]{5f, 4f}, 0f));
        s.setAnnotationTextFont(new Font("SansSerif", Font.PLAIN, 12));
        s.setAnnotationTextFontColor(INK2);
        return c;
    }

    static XYSeries line(XYChart c, String name, double[] x, double[] y, float width) {
        if (x.length > 30) {
            // XChart choisit des graduations « une valeur sur k » (37, 74, …) quand les x sont
            // régulièrement espacés ; un point final décalé rétablit des graduations rondes.
            x = java.util.Arrays.copyOf(x, x.length + 1);
            y = java.util.Arrays.copyOf(y, y.length + 1);
            x[x.length - 1] = x[x.length - 2] + 1e-3;   // XChart dédoublonne les x : décalage invisible
            y[y.length - 1] = y[y.length - 2];
        }
        XYSeries s = c.addSeries(name, x, y);
        s.setLineColor(COL.getOrDefault(name, INK2));
        s.setMarker(SeriesMarkers.NONE);
        s.setLineStyle(new BasicStroke(width, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        return s;
    }

    static BufferedImage img(XYChart c) { return BitmapEncoder.getBufferedImage(c); }

    static BufferedImage img(JFreeChart c, int w, int h) { return c.createBufferedImage(w, h); }

    void compose(String file, String title, int cols, BufferedImage... panels) throws IOException {
        int pw = panels[0].getWidth(), ph = panels[0].getHeight();
        int rows = (panels.length + cols - 1) / cols, top = 46;
        BufferedImage out = new BufferedImage(pw * cols, ph * rows + top, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = out.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setColor(SURF);
        g.fillRect(0, 0, out.getWidth(), out.getHeight());
        g.setColor(INK);
        g.setFont(F_SUPER);
        g.drawString(title, 16, 30);
        for (int i = 0; i < panels.length; i++) g.drawImage(panels[i], (i % cols) * pw, top + (i / cols) * ph, null);
        g.dispose();
        ImageIO.write(out, "png", new File(dir.toFile(), file + ".png"));
    }

    static double[] seq(int n, double start) {
        double[] x = new double[n];
        for (int i = 0; i < n; i++) x[i] = start + i;
        return x;
    }

    static double[] col(Result r, int k) {
        double[] v = new double[r.rows.size()];
        for (int i = 0; i < v.length; i++) v[i] = ((Number) r.rows.get(i)[k]).doubleValue();
        return v;
    }

    static double[] rolling(double[] v, int w) {
        double[] o = new double[v.length];
        double s = 0;
        for (int i = 0; i < v.length; i++) {
            s += v[i];
            if (i >= w) s -= v[i - w];
            o[i] = s / Math.min(i + 1, w);
        }
        return o;
    }

    static double[] cumsum(double[] v) {
        double[] o = new double[v.length];
        double s = 0;
        for (int i = 0; i < v.length; i++) { s += v[i]; o[i] = s; }
        return o;
    }

    static Result find(List<Result> rs, String label) {
        for (Result r : rs) if (r.label.equals(label)) return r;
        throw new IllegalArgumentException(label);
    }

    static void styleCategory(JFreeChart ch, String title) {
        ch.setBackgroundPaint(SURF);
        ch.setTitle(new TextTitle(title, F_TITLE));
        ch.getTitle().setPaint(INK);
        ch.getTitle().setHorizontalAlignment(org.jfree.chart.ui.HorizontalAlignment.LEFT);
        CategoryPlot p = ch.getCategoryPlot();
        p.setBackgroundPaint(SURF);
        p.setOutlineVisible(false);
        p.setRangeGridlinePaint(GRID);
        p.setRangeGridlineStroke(new BasicStroke(0.8f));
        p.setDomainGridlinesVisible(false);
        CategoryAxis ca = p.getDomainAxis();
        ca.setTickLabelFont(F_BASE);
        ca.setTickLabelPaint(INK2);
        ca.setAxisLineVisible(false);
        ca.setTickMarksVisible(false);
        ca.setLabelFont(F_BASE);
        NumberAxis na = (NumberAxis) p.getRangeAxis();
        na.setTickLabelFont(F_BASE);
        na.setTickLabelPaint(INK2);
        na.setLabelFont(F_BASE);
        na.setLabelPaint(INK2);
        na.setAxisLineVisible(false);
        na.setTickMarksVisible(false);
        if (ch.getLegend() != null) {
            ch.getLegend().setBackgroundPaint(SURF);
            ch.getLegend().setItemFont(F_BASE);
            ch.getLegend().setFrame(org.jfree.chart.block.BlockBorder.NONE);
            ch.getLegend().setPosition(RectangleEdge.TOP);
        }
        if (p.getRenderer() instanceof BarRenderer br) {
            br.setBarPainter(new StandardBarPainter());
            br.setShadowVisible(false);
            br.setDrawBarOutline(false);
        }
    }

    // S1 / S2
    /** article.get(kind) = résultats de la graine 0 pour les stratégies du scénario de l'article. */
    public void articleFigures(Map<String, List<Result>> article, String prefix, String[] strategies) throws IOException {
        shown = strategies;
        String tag = prefix.toUpperCase().replace("_", "") + " — ";
        twoPanels(article, prefix + "fig2_replicas", tag + "Facteur de réplication", "Nombre de réplicas",
                r -> col(r, 14), true, Styler.LegendPosition.OutsideS);
        twoPanels(article, prefix + "fig3_response_time", tag + "Temps de réponse — moyenne glissante sur 20 requêtes",
                "Temps de réponse (ms)", r -> rolling(col(r, 4), 20), false, Styler.LegendPosition.OutsideS);
        twoPanels(article, prefix + "fig5_bw_cost_per_query", tag + "Coût de bande passante par requête — moyenne glissante",
                "Coût BW ($ / requête)", r -> rolling(col(r, 6), 20), false, Styler.LegendPosition.OutsideS);
        twoPanels(article, prefix + "fig6_bw_cumulative", tag + "Coût cumulé de bande passante", "Coût BW cumulé ($)",
                r -> cumsum(col(r, 6)), false, Styler.LegendPosition.OutsideS);

        // Fig. 4 : volumes (JFreeChart, barres groupées)
        List<BufferedImage> f4 = new ArrayList<>(), f7 = new ArrayList<>();
        for (String kind : new String[]{"simple", "complex"}) {
            DefaultCategoryDataset ds = new DefaultCategoryDataset();
            DefaultCategoryDataset cost = new DefaultCategoryDataset();
            for (String st : shown) {
                Map<String, Object> s = find(article.get(kind), st).summary;
                ds.addValue((Double) s.get("gb_provider"), "inter-fournisseur", st);
                ds.addValue((Double) s.get("gb_region"), "inter-région", st);
                cost.addValue((Double) s.get("cost_cpu"), "CPU", st);
                cost.addValue((Double) s.get("cost_bw"), "Bande passante", st);
                cost.addValue((Double) s.get("cost_replica_create") + (Double) s.get("cost_storage"),
                        "Réplicas (création + stockage)", st);
            }
            JFreeChart ch = org.jfree.chart.ChartFactory.createBarChart(KIND_FR.get(kind), null, "Volume transféré (GB)",
                    ds, PlotOrientation.VERTICAL, true, false, false);
            styleCategory(ch, KIND_FR.get(kind));
            BarRenderer br = (BarRenderer) ch.getCategoryPlot().getRenderer();
            br.setSeriesPaint(0, COL.get("TCDRM"));
            br.setSeriesPaint(1, COL.get("TCDRM-Predictive"));
            br.setItemMargin(0.06);
            br.setDefaultItemLabelGenerator(new StandardCategoryItemLabelGenerator("{2}", new java.text.DecimalFormat("0")));
            br.setDefaultItemLabelsVisible(true);
            br.setDefaultItemLabelFont(new Font("SansSerif", Font.PLAIN, 12));
            br.setDefaultItemLabelPaint(INK2);
            f4.add(img(ch, 640, 420));

            JFreeChart c7 = org.jfree.chart.ChartFactory.createStackedBarChart(KIND_FR.get(kind), null, "Coût total ($)",
                    cost, PlotOrientation.VERTICAL, true, false, false);
            styleCategory(c7, KIND_FR.get(kind));
            StackedBarRenderer sr = (StackedBarRenderer) c7.getCategoryPlot().getRenderer();
            sr.setSeriesPaint(0, COL.get("TCDRM"));
            sr.setSeriesPaint(1, COL.get("TCDRM-Predictive"));
            sr.setSeriesPaint(2, COL.get("P1+P2"));
            sr.setMaximumBarWidth(0.28);
            sr.setDefaultItemLabelGenerator(new StandardCategoryItemLabelGenerator("{2}", new java.text.DecimalFormat("0.00")));
            sr.setDefaultItemLabelsVisible(true);
            sr.setDefaultItemLabelFont(new Font("SansSerif", Font.PLAIN, 11));
            sr.setDefaultItemLabelPaint(Color.WHITE);
            f7.add(img(c7, 640, 420));
        }
        compose(prefix + "fig4_bw_volume", tag + "Volumes transférés sur 1000 requêtes", 2, f4.toArray(new BufferedImage[0]));
        compose(prefix + "fig7_total_cost", tag + "Coût total pour le locataire sur 1000 requêtes", 2, f7.toArray(new BufferedImage[0]));
    }

    private void twoPanels(Map<String, List<Result>> article, String file, String title, String yl,
                           Function<Result, double[]> f, boolean step, Styler.LegendPosition lp) throws IOException {
        List<BufferedImage> imgs = new ArrayList<>();
        for (String kind : new String[]{"simple", "complex"}) {
            XYChart c = xy(640, 430, KIND_FR.get(kind), "Nombre de requêtes", yl);
            c.getStyler().setLegendPosition(lp);
            c.getStyler().setXAxisMin(0.0);
            c.getStyler().setXAxisMax(1000.0);
            c.getStyler().setXAxisMaxLabelCount(11);
            for (String st : shown) {
                Result r = find(article.get(kind), st);
                double[] y = f.apply(r);
                double[] x = seq(y.length, 1);
                if (step) {
                    double[][] sv = stepify(x, y);
                    x = sv[0];
                    y = sv[1];
                }
                line(c, st, x, y, 2.2f);
            }
            imgs.add(img(c));
        }
        compose(file, title, 2, imgs.toArray(new BufferedImage[0]));
    }

    static double[][] stepify(double[] x, double[] y) {
        List<double[]> pts = new ArrayList<>();
        for (int i = 0; i < x.length; i++) {
            if (i > 0 && y[i] != y[i - 1]) pts.add(new double[]{x[i], y[i - 1]});
            if (i == 0 || i == x.length - 1 || y[i] != y[i - 1]) pts.add(new double[]{x[i], y[i]});
        }
        double[] xs = new double[pts.size()], ys = new double[pts.size()];
        for (int i = 0; i < xs.length; i++) { xs[i] = pts.get(i)[0]; ys[i] = pts.get(i)[1]; }
        return new double[][]{xs, ys};
    }

    // P1
    public void forecastValidation(fr.tcdrm.exp.ForecastValidation fv) throws IOException {
        // A. MAE par méthode (semaines de test)
        DefaultCategoryDataset ds = new DefaultCategoryDataset();
        List<String> names = new ArrayList<>();
        for (Map<String, Object> m : fv.metrics) {
            ds.addValue((Double) m.get("mae"), "MAE", (String) m.get("method"));
            names.add((String) m.get("method"));
        }
        JFreeChart a = org.jfree.chart.ChartFactory.createBarChart(null, null,
                "Erreur absolue moyenne (accès par période, horizon 5 périodes)", ds, PlotOrientation.HORIZONTAL, false, false, false);
        styleCategory(a, "MAE sur 3 charges de test");
        BarRenderer br = new BarRenderer() {
            @Override
            public java.awt.Paint getItemPaint(int row, int column) {
                return names.get(column).startsWith("LSTM") || names.get(column).startsWith("P1")
                        ? COL.get("TCDRM-Predictive") : COL.get("NoRepLc");
            }
        };
        br.setBarPainter(new StandardBarPainter());
        br.setShadowVisible(false);
        br.setMaximumBarWidth(0.1);
        br.setDefaultItemLabelGenerator(new StandardCategoryItemLabelGenerator("{2}", new java.text.DecimalFormat("0.00")));
        br.setDefaultItemLabelsVisible(true);
        br.setDefaultItemLabelFont(F_BASE);
        br.setDefaultItemLabelPaint(INK2);
        br.setDefaultPositiveItemLabelPosition(new org.jfree.chart.labels.ItemLabelPosition(
                org.jfree.chart.labels.ItemLabelAnchor.OUTSIDE3, org.jfree.chart.ui.TextAnchor.CENTER_LEFT));
        a.getCategoryPlot().setRenderer(br);
        // B. MAE par tiers de volume des séries
        DefaultCategoryDataset vt = new DefaultCategoryDataset();
        String[] keep = {"LSTM", "EMA α réglé", "Dernière valeur", "Même phase, cycle précédent"};
        String[][] tiers = {{"mae_low_volume", "séries peu actives"}, {"mae_mid_volume", "séries moyennes"},
                {"mae_high_volume", "séries très actives"}};
        for (String k : keep) {
            for (Map<String, Object> m : fv.metrics) {
                if (!k.equals(m.get("method"))) continue;
                for (String[] t : tiers) vt.addValue((Double) m.get(t[0]), k, t[1]);
            }
        }
        JFreeChart b = org.jfree.chart.ChartFactory.createBarChart(null, null, "MAE (accès par période)", vt, PlotOrientation.VERTICAL, true, false, false);
        styleCategory(b, "MAE selon le volume des séries (tiers)");
        BarRenderer r2 = (BarRenderer) b.getCategoryPlot().getRenderer();
        r2.setSeriesPaint(0, COL.get("TCDRM-Predictive"));
        r2.setSeriesPaint(1, new Color(0x2a78d6));
        r2.setSeriesPaint(2, new Color(0x9fc3ee));
        r2.setSeriesPaint(3, COL.get("NoRepLc"));
        r2.setItemMargin(0.05);
        r2.setDefaultItemLabelGenerator(new StandardCategoryItemLabelGenerator("{2}", new java.text.DecimalFormat("0.0")));
        r2.setDefaultItemLabelsVisible(true);
        r2.setDefaultItemLabelFont(new Font("SansSerif", Font.PLAIN, 11));
        r2.setDefaultItemLabelPaint(INK2);
        compose(p1File, "P1 — validation de la prédiction (entraînement, validation et test sur des charges distinctes)",
                2, img(a, 680, 440), img(b, 680, 440));
    }

    // S3 : évolution temporelle (graine 0)
    public void s3Timeseries(List<Result> rs, int priceHour) throws IOException {
        String[] st = {"NoRepLc", "TCDRM", "Replicate-All", "TCDRM-Predictive"};
        XYChart a = xy(1300, 330, "Temps de réponse des lectures (moyenne par période, lissée sur 3 périodes)", null, "ms");
        XYChart b = xy(1300, 330, "Coût lié au placement cumulé (bande passante + synchronisation + réplicas)", null, "$");
        XYChart c = xy(1300, 330, "Réplicas actifs", "Période (" + periodQueries + " requêtes)", "réplicas");
        a.getStyler().setLegendVisible(false);
        b.getStyler().setLegendVisible(false);
        c.getStyler().setLegendPosition(Styler.LegendPosition.InsideNW);
        for (String s : st) {
            Result r = find(rs, s);
            int H = nPeriods;
            double[] sum = new double[H], cnt = new double[H];
            for (Object[] row : r.rows) {
                if ("write".equals(row[2])) continue;
                int h = (int) (double) (Double) row[1];
                if (h < H) { sum[h] += (Double) row[4]; cnt[h]++; }
            }
            double[] hr = new double[H];
            for (int h = 0; h < H; h++) hr[h] = cnt[h] > 0 ? sum[h] / cnt[h] : Double.NaN;
            line(a, s, seq(H, 0), rolling(hr, 3), 2f);
            double[] t = col(r, 1), cost = new double[r.rows.size()];
            for (int i = 0; i < cost.length; i++) {
                Object[] row = r.rows.get(i);
                cost[i] = (Double) row[6] + (Double) row[7] + r.mgr.extraCost.getOrDefault((Integer) row[0], 0.0);
            }
            line(b, s, t, cumsum(cost), 2.2f);
            double[][] sv = stepify(t, col(r, 14));
            line(c, s, sv[0], sv[1], 1.8f);
        }
        for (XYChart ch : new XYChart[]{a, b, c}) {
            ch.getStyler().setXAxisMin(0.0);
            ch.getStyler().setXAxisMax((double) nPeriods);
            ch.getStyler().setXAxisMaxLabelCount(15);
            if (priceHour > 0) ch.addAnnotation(new AnnotationLine(priceHour, true, false));
        }
        if (priceHour > 0) a.addAnnotation(new AnnotationText("variation des prix", priceHour + 3, 105, false));
        compose(dyn + "_timeseries", dynName + " — évolution sur " + nPeriods * periodQueries + " requêtes (graine 0)", 1, img(a), img(b), img(c));
    }

    // S3 : figures de l'article (graine 0)
    /** Temps de réponse (simple, complexe), facteur de réplication, coût BW par requête et cumulé, en fonction des requêtes. */
    public void s3ArticleFigures(List<Result> rs, int priceQuery) throws IOException {
        String[] st = {"NoRepLc", "TCDRM", "Replicate-All", "TCDRM-Predictive"};
        int n = nPeriods * periodQueries;
        List<BufferedImage> rt = new ArrayList<>();
        for (String kind : new String[]{"simple", "complex"}) {
            boolean any = false;
            for (Object[] row : rs.get(0).rows) any |= kind.equals(row[2]);
            if (!any) continue;                     // S3 : requêtes Q1, Q2, Q3 de 3 relations (type simple)
            XYChart c = xy(1300, 430, kind.equals("simple") ? "Lectures (3 relations par requête)" : KIND_FR.get(kind), "Numéro de requête", "Temps de réponse (ms)");
            c.getStyler().setLegendPosition(Styler.LegendPosition.OutsideS);
            for (String s : st) {
                Result r = find(rs, s);
                List<Double> xs = new ArrayList<>(), ys = new ArrayList<>();
                for (Object[] row : r.rows) if (kind.equals(row[2])) { xs.add((double) (Integer) row[0]); ys.add((Double) row[4]); }
                double[] y = rolling(ys.stream().mapToDouble(Double::doubleValue).toArray(), 20);
                line(c, s, xs.stream().mapToDouble(Double::doubleValue).toArray(), y, 1.8f);
            }
            s3Axis(c, n, priceQuery);
            rt.add(img(c));
        }
        compose(dyn + "_fig3_response_time", dynName + " — Temps de réponse des lectures, moyenne glissante sur 20 requêtes", rt.size(),
                rt.toArray(new BufferedImage[0]));
        XYChart rep = xy(1300, 430, "", "Numéro de requête", "Réplicas actifs");
        XYChart per = xy(1300, 430, "", "Numéro de requête", "Coût BW ($ / requête)");
        XYChart cum = xy(1300, 430, "", "Numéro de requête", "Coût BW cumulé ($)");
        for (XYChart c : new XYChart[]{rep, per, cum}) c.getStyler().setLegendPosition(Styler.LegendPosition.OutsideS);
        for (String s : st) {
            Result r = find(rs, s);
            double[] x = col(r, 0);
            double[][] sv = stepify(x, col(r, 14));
            line(rep, s, sv[0], sv[1], 1.8f);
            line(per, s, x, rolling(col(r, 6), 50), 1.8f);
            line(cum, s, x, cumsum(col(r, 6)), 2.2f);
        }
        for (XYChart c : new XYChart[]{rep, per, cum}) s3Axis(c, n, priceQuery);
        compose(dyn + "_fig2_replicas", dynName + " — Facteur de réplication (réplicas actifs)", 1, img(rep));
        compose(dyn + "_fig5_bw_cost_per_query", dynName + " — Coût de bande passante par requête, moyenne glissante sur 50 requêtes", 1, img(per));
        compose(dyn + "_fig6_bw_cumulative", dynName + " — Coût cumulé de bande passante", 1, img(cum));
    }

    private void s3Axis(XYChart c, int n, int priceQuery) {
        c.getStyler().setXAxisMin(0.0);
        c.getStyler().setXAxisMax((double) n);
        c.getStyler().setXAxisMaxLabelCount(13);
        for (int q = cycle * periodQueries; q < n; q += cycle * periodQueries) c.addAnnotation(new AnnotationLine(q, true, false));
        if (priceQuery > 0) c.addAnnotation(new AnnotationLine(priceQuery, true, false));
    }

    // S3 : comparaison multi-graines
    public void s3MultiSeed(List<Map<String, Object>> sums, List<String> order) throws IOException {
        String[][] metrics = {{"rt_mean", "Temps de réponse moyen (ms)"}, {"cost_placement", "Coût lié au placement ($)"},
                {"replicas_created", "Réplicas créés"}, {"replicas_deleted", "Réplicas supprimés (algorithme 3)"}};
        List<BufferedImage> imgs = new ArrayList<>();
        for (String[] m : metrics) {
            DefaultStatisticalCategoryDataset ds = new DefaultStatisticalCategoryDataset();
            for (String st : order) {
                List<Double> v = new ArrayList<>();
                for (Map<String, Object> s : sums) if (st.equals(s.get("strategy"))) v.add(((Number) s.get(m[0])).doubleValue());
                double mean = v.stream().mapToDouble(d -> d).average().orElse(0);
                double sd = Math.sqrt(v.stream().mapToDouble(d -> (d - mean) * (d - mean)).sum() / Math.max(1, v.size() - 1));
                ds.add(mean, sd, "moyenne", st);
            }
            JFreeChart ch = new JFreeChart(new CategoryPlot(ds, new CategoryAxis(), new NumberAxis(), null));
            ch.getCategoryPlot().setOrientation(PlotOrientation.HORIZONTAL);
            ch.removeLegend();
            StatisticalBarRenderer r = new StatisticalBarRenderer() {
                @Override
                public java.awt.Paint getItemPaint(int row, int column) {
                    return COL.getOrDefault(order.get(column), INK2);
                }
            };
            r.setErrorIndicatorPaint(INK2);
            r.setBarPainter(new StandardBarPainter());
            r.setShadowVisible(false);
            r.setItemMargin(0);
            r.setMaximumBarWidth(0.09);
            r.setDefaultItemLabelGenerator(new StandardCategoryItemLabelGenerator("{2}",
                    new java.text.DecimalFormat("0.0")));
            r.setDefaultItemLabelsVisible(true);
            r.setDefaultItemLabelFont(new Font("SansSerif", Font.PLAIN, 12));
            r.setDefaultItemLabelPaint(INK2);
            r.setDefaultPositiveItemLabelPosition(new org.jfree.chart.labels.ItemLabelPosition(
                    org.jfree.chart.labels.ItemLabelAnchor.INSIDE9, org.jfree.chart.ui.TextAnchor.CENTER_LEFT));
            ch.getCategoryPlot().setRenderer(r);
            styleCategory(ch, m[1]);
            r.setDefaultItemLabelPaint(Color.WHITE);
            imgs.add(img(ch, 650, 380));
        }
        compose(dyn + "_multiseed", dynName + " — moyenne ± écart-type sur " + sums.stream().map(m -> m.get("seed")).distinct().count() + " graines", 2, imgs.toArray(new BufferedImage[0]));
    }

    // S3 : indicateurs de validation
    static double meanOf(List<Map<String, Object>> sums, String st, String key) {
        double t = 0;
        int n = 0;
        for (Map<String, Object> s : sums) {
            if (st.equals(s.get("strategy")) && s.get(key) instanceof Number v) { t += v.doubleValue(); n++; }
        }
        return n == 0 ? Double.NaN : t / n;
    }

    private JFreeChart bars(String title, String yLabel, DefaultCategoryDataset ds, boolean stacked, String fmt) {
        JFreeChart ch = stacked
                ? org.jfree.chart.ChartFactory.createStackedBarChart(title, null, yLabel, ds, PlotOrientation.VERTICAL, true, false, false)
                : org.jfree.chart.ChartFactory.createBarChart(title, null, yLabel, ds, PlotOrientation.VERTICAL, true, false, false);
        styleCategory(ch, title);
        BarRenderer br = (BarRenderer) ch.getCategoryPlot().getRenderer();
        br.setItemMargin(0.04);
        br.setMaximumBarWidth(0.1);
        if (fmt != null) {
            br.setDefaultItemLabelGenerator(new StandardCategoryItemLabelGenerator("{2}", new java.text.DecimalFormat(fmt)));
            br.setDefaultItemLabelsVisible(true);
            br.setDefaultItemLabelFont(new Font("SansSerif", Font.PLAIN, 11));
            br.setDefaultItemLabelPaint(INK2);
        }
        ch.getCategoryPlot().getDomainAxis().setCategoryLabelPositions(
                org.jfree.chart.axis.CategoryLabelPositions.createUpRotationLabelPositions(Math.PI / 9));
        return ch;
    }

    private static void colorByStrategy(JFreeChart ch, List<String> cats) {
        BarRenderer old = (BarRenderer) ch.getCategoryPlot().getRenderer();
        BarRenderer br = new BarRenderer() {
            @Override
            public java.awt.Paint getItemPaint(int row, int column) {
                return COL.getOrDefault(cats.get(column), INK2);
            }
        };
        br.setBarPainter(new StandardBarPainter());
        br.setShadowVisible(false);
        br.setMaximumBarWidth(0.1);
        br.setDefaultItemLabelGenerator(old.getDefaultItemLabelGenerator());
        br.setDefaultItemLabelsVisible(old.getDefaultItemLabelsVisible());
        br.setDefaultItemLabelFont(old.getDefaultItemLabelFont());
        br.setDefaultItemLabelPaint(INK2);
        ch.getCategoryPlot().setRenderer(br);
        if (ch.getLegend() != null) ch.removeLegend();
    }

    public void validation(List<Map<String, Object>> sums, List<Map<String, Object>> daily,
                           List<Map<String, Object>> outcomes, List<Result> seed0, List<String> order) throws IOException {
        List<String> repl = new ArrayList<>(order);
        repl.remove("NoRepLc");

        // 1. Budget : dépense journalière / budget (moyenne 10 graines) + coût cumulé (graine 0)
        XYChart a = xy(640, 420, "Dépense par cycle / budget (moyenne sur 10 graines)", "Cycle (" + cycle * periodQueries + " requêtes)", "% du budget");
        a.getStyler().setLegendPosition(Styler.LegendPosition.OutsideS);
        a.getStyler().setMarkerSize(7);
        int days = 0;
        for (Map<String, Object> d : daily) days = Math.max(days, (Integer) d.get("day"));
        for (String st : order) {
            double[] x = new double[days], y = new double[days];
            for (int k = 1; k <= days; k++) {
                double t = 0;
                int n = 0;
                for (Map<String, Object> d : daily) {
                    if (st.equals(d.get("strategy")) && (Integer) d.get("day") == k) {
                        t += 100 * (Double) d.get("spend_usd") / (Double) d.get("budget_usd");
                        n++;
                    }
                }
                x[k - 1] = k;
                y[k - 1] = t / Math.max(1, n);
            }
            mark(line(a, st, x, y, 2f));
        }
        a.addAnnotation(new AnnotationLine(100, false, false));
        a.addAnnotation(new AnnotationText("budget", 6.6, 101.5, false));
        XYChart b = xy(640, 420, "Coût cumulé − budget cumulé (graine 0) : > 0 = dépassement",
                "Période (" + periodQueries + " requêtes)", "$");
        b.getStyler().setLegendPosition(Styler.LegendPosition.OutsideS);
        double budget0 = 0;
        for (Map<String, Object> d : daily) if ((Integer) d.get("seed") == 0) budget0 = (Double) d.get("budget_usd");
        for (String st : new String[]{"NoRepLc", "TCDRM", "Replicate-All", "TCDRM-Predictive"}) {
            Result r = find(seed0, st);
            double[] t = col(r, 1), cost = new double[r.rows.size()];
            for (int i = 0; i < cost.length; i++) {
                Object[] row = r.rows.get(i);
                cost[i] = (Double) row[5] + (Double) row[6] + (Double) row[7]
                        + r.mgr.extraCost.getOrDefault((Integer) row[0], 0.0);
            }
            double[] cum = cumsum(cost);
            for (int i = 0; i < cum.length; i++) cum[i] -= budget0 * t[i] / cycle;
            line(b, st, t, cum, 2f);
        }
        b.addAnnotation(new AnnotationLine(0, false, false));
        compose(dyn + "_validation_budget", "Respect du budget (période de budget = cycle de " + cycle * periodQueries + " requêtes)", 2, img(a), img(b));

        // 2. SLA : latence moyenne, p95, p99
        DefaultCategoryDataset sla = new DefaultCategoryDataset();
        for (String st : order) {
            sla.addValue(meanOf(sums, st, "rt_mean"), "moyenne", st);
            sla.addValue(meanOf(sums, st, "rt_p95"), "p95", st);
            sla.addValue(meanOf(sums, st, "rt_p99"), "p99", st);
        }
        JFreeChart cs = bars("Temps de réponse des lectures (moyenne sur 10 graines)", "ms", sla, false, "0");
        BarRenderer sr = (BarRenderer) cs.getCategoryPlot().getRenderer();
        sr.setSeriesPaint(0, new Color(0x2a78d6));
        sr.setSeriesPaint(1, new Color(0x2a78d6).brighter());
        sr.setSeriesPaint(2, new Color(0x9fc3ee));
        sr.setMaximumBarWidth(0.04);
        DefaultCategoryDataset vt = new DefaultCategoryDataset();
        for (String st : order) vt.addValue(meanOf(sums, st, "sla_T_violation_pct"), "violations", st);
        JFreeChart cv = bars("Lectures au-dessus de TSLA (%)", "%", vt, false, "0.0");
        colorByStrategy(cv, order);
        compose(dyn + "_validation_sla", "Respect du SLA", 2, img(cs, 700, 460), img(cv, 600, 460));

        // 3. Bande passante inter-cloud : volume par origine + coût
        DefaultCategoryDataset vol = new DefaultCategoryDataset();
        DefaultCategoryDataset cost = new DefaultCategoryDataset();
        for (String st : order) {
            vol.addValue(meanOf(sums, st, "gb_provider"), "lectures/écritures inter-fournisseur", st);
            vol.addValue(meanOf(sums, st, "gb_region"), "lectures/écritures inter-région", st);
            vol.addValue(meanOf(sums, st, "gb_replication"), "copies de réplicas", st);
            vol.addValue(meanOf(sums, st, "gb_sync"), "synchronisation", st);
            cost.addValue(meanOf(sums, st, "cost_intercloud"), "coût", st);
        }
        JFreeChart cvol = bars("Volume inter-cloud (GB)", "GB", vol, true, null);
        BarRenderer vr = (BarRenderer) cvol.getCategoryPlot().getRenderer();
        vr.setSeriesPaint(0, new Color(0x2a78d6));
        vr.setSeriesPaint(1, new Color(0x9fc3ee));
        vr.setSeriesPaint(2, new Color(0xeda100));
        vr.setSeriesPaint(3, new Color(0xe87ba4));
        vr.setMaximumBarWidth(0.1);
        cvol.getLegend().setPosition(RectangleEdge.BOTTOM);
        JFreeChart ccost = bars("Coût inter-cloud ($)", "$", cost, false, "0.0");
        colorByStrategy(ccost, order);
        compose(dyn + "_validation_bandwidth", "Bande passante inter-cloud (moyenne sur 10 graines)", 2,
                img(cvol, 680, 480), img(ccost, 620, 480));

        // 4. Facteur de réplication + stabilité
        DefaultCategoryDataset rep = new DefaultCategoryDataset();
        DefaultCategoryDataset thr = new DefaultCategoryDataset();
        for (String st : repl) {
            rep.addValue(meanOf(sums, st, "replicas_created"), "créés", st);
            rep.addValue(meanOf(sums, st, "replicas_deleted"), "supprimés", st);
            rep.addValue(meanOf(sums, st, "replicas_active_mean"), "actifs (moyenne horaire)", st);
            thr.addValue(meanOf(sums, st, "thrash_rate_pct"), "thrashing", st);
        }
        JFreeChart crep = bars("Facteur de réplication", "réplicas", rep, false, "0");
        BarRenderer rr = (BarRenderer) crep.getCategoryPlot().getRenderer();
        rr.setSeriesPaint(0, new Color(0x2a78d6));
        rr.setSeriesPaint(1, new Color(0x9fc3ee));
        rr.setSeriesPaint(2, new Color(0x8a8984));
        rr.setMaximumBarWidth(0.05);
        JFreeChart cthr = bars("Taux de thrashing : recréations < 1 cycle / créations (%)", "%", thr, false, "0.0");
        colorByStrategy(cthr, repl);
        compose(dyn + "_validation_replication", "Facteur de réplication et stabilité des décisions (moyenne sur 10 graines)", 2,
                img(crep, 680, 460), img(cthr, 620, 460));

        // 5. Amortissement : distribution du temps de retour + part des réplicas amortis
        XYChart cdf = xy(680, 460, "Réplicas amortis en moins de x périodes (% des réplicas créés)",
                "Temps depuis la création (périodes)", "% des réplicas créés");
        cdf.getStyler().setLegendPosition(Styler.LegendPosition.InsideSE);
        cdf.getStyler().setXAxisMax((double) cycle);
        cdf.getStyler().setYAxisMin(0.0);
        cdf.getStyler().setYAxisMax(100.0);
        DefaultCategoryDataset paid = new DefaultCategoryDataset();
        for (String st : repl) {
            List<Double> pb = new ArrayList<>();
            int n = 0;
            for (Map<String, Object> o : outcomes) {
                if (!st.equals(o.get("strategy"))) continue;
                n++;
                if (o.get("payback_h") instanceof Double v) pb.add(v);
            }
            java.util.Collections.sort(pb);
            int K = 10 * cycle + 1;
            double[] x = new double[K], y = new double[K];
            int j = 0;
            for (int k = 0; k < K; k++) {
                x[k] = k * 0.1;
                while (j < pb.size() && pb.get(j) <= x[k]) j++;
                y[k] = 100.0 * j / Math.max(1, n);
            }
            line(cdf, st, x, y, 2f);
            paid.addValue(100.0 * pb.size() / Math.max(1, n), "amortis", st);
        }
        JFreeChart cpaid = bars("Réplicas amortis avant leur suppression ou la fin (%)", "%", paid, false, "0");
        colorByStrategy(cpaid, repl);
        compose(dyn + "_validation_amortization", "Amortissement économique des réplicas (10 graines)", 2,
                img(cdf), img(cpaid, 620, 460));
    }

    // S3 : sensibilités
    public void s3Sensitivity(List<Map<String, Object>> sens) throws IOException {
        String[] lines = {"TCDRM", "Replicate-All", "Predictive sans contraintes", "TCDRM-Predictive"};
        XYChart a = xy(560, 400, "Coût lié au placement vs NoRepLc", "Part des écritures (%)", "écart (%)");
        XYChart b = xy(560, 400, "Coût de synchronisation", "Part des écritures (%)", "$");
        a.getStyler().setLegendPosition(Styler.LegendPosition.InsideNW);
        b.getStyler().setLegendVisible(false);
        XYChart c = xy(560, 400, "Temps de réponse moyen", "Part des écritures (%)", "ms");
        c.getStyler().setLegendVisible(false);
        for (XYChart ch : new XYChart[]{a, b, c}) ch.getStyler().setMarkerSize(8);
        double[] wps = {0.0, 0.15, 0.30, 0.45};
        for (String st : lines) {
            double[] x = new double[4], ya = new double[4], yb = new double[4], yc = new double[4];
            for (int i = 0; i < 4; i++) {
                x[i] = 100 * wps[i];
                ya[i] = relGap(sens, "write", "write_prob", wps[i], st, "cost_placement");
                yb[i] = mean(sens, "write", "write_prob", wps[i], st, "cost_sync");
                yc[i] = mean(sens, "write", "write_prob", wps[i], st, "rt_mean");
            }
            mark(line(a, st, x, ya, 2.2f));
            mark(line(b, st, x, yb, 2.2f));
            mark(line(c, st, x, yc, 2.2f));
        }
        a.addAnnotation(new AnnotationLine(0, false, false));
        compose(dyn + "_sensitivity", dynName + " — sensibilité au taux d'écriture (moyenne sur 5 graines)", 3, img(a), img(b), img(c));
    }

    private static void mark(XYSeries s) {
        s.setMarker(SeriesMarkers.CIRCLE);
        s.setMarkerColor(s.getLineColor());
    }

    private static boolean match(Map<String, Object> s, String sweep, String key, Object val, String st) {
        if (!sweep.equals(s.get("sweep")) || !st.equals(s.get("strategy"))) return false;
        Object v = s.get(key);
        if (val instanceof Double d) return Math.abs(((Number) v).doubleValue() - d) < 1e-9;
        return val.equals(v);
    }

    static double mean(List<Map<String, Object>> sens, String sweep, String key, Object val, String st, String m) {
        double t = 0;
        int n = 0;
        for (Map<String, Object> s : sens) if (match(s, sweep, key, val, st)) { t += ((Number) s.get(m)).doubleValue(); n++; }
        return t / Math.max(1, n);
    }

    static double relGap(List<Map<String, Object>> sens, String sweep, String key, Object val, String st, String m) {
        Map<Object, Double> ref = new HashMap<>();
        for (Map<String, Object> s : sens) if (match(s, sweep, key, val, "NoRepLc")) ref.put(s.get("seed"), ((Number) s.get(m)).doubleValue());
        double t = 0;
        int n = 0;
        for (Map<String, Object> s : sens) {
            if (match(s, sweep, key, val, st)) {
                t += 100 * (((Number) s.get(m)).doubleValue() / ref.get(s.get("seed")) - 1);
                n++;
            }
        }
        return t / Math.max(1, n);
    }
    // Améliorations v2 : figures de vérification
    /** Barres horizontales moyenne ± écart-type d'un indicateur, une barre par stratégie. */
    private BufferedImage statBars(List<Map<String, Object>> sums, List<String> order, String key, String title,
                                   String fmt, int w, int h) {
        DefaultStatisticalCategoryDataset ds = new DefaultStatisticalCategoryDataset();
        for (String st : order) {
            List<Double> v = new ArrayList<>();
            for (Map<String, Object> s : sums) {
                if (st.equals(s.get("strategy")) && s.get(key) instanceof Number n) v.add(n.doubleValue());
            }
            double mean = v.stream().mapToDouble(d -> d).average().orElse(0);
            double sd = Math.sqrt(v.stream().mapToDouble(d -> (d - mean) * (d - mean)).sum() / Math.max(1, v.size() - 1));
            ds.add(mean, sd, "moyenne", st);
        }
        JFreeChart ch = new JFreeChart(new CategoryPlot(ds, new CategoryAxis(), new NumberAxis(), null));
        ch.getCategoryPlot().setOrientation(PlotOrientation.HORIZONTAL);
        ch.removeLegend();
        StatisticalBarRenderer r = new StatisticalBarRenderer() {
            @Override
            public java.awt.Paint getItemPaint(int row, int column) {
                return COL.getOrDefault(order.get(column), INK2);
            }
        };
        r.setErrorIndicatorPaint(INK2);
        r.setBarPainter(new StandardBarPainter());
        r.setShadowVisible(false);
        r.setItemMargin(0);
        r.setMaximumBarWidth(0.16);
        r.setDefaultItemLabelGenerator(new StandardCategoryItemLabelGenerator("{2}", new java.text.DecimalFormat(fmt)));
        r.setDefaultItemLabelsVisible(true);
        r.setDefaultItemLabelFont(new Font("SansSerif", Font.PLAIN, 12));
        r.setDefaultPositiveItemLabelPosition(new org.jfree.chart.labels.ItemLabelPosition(
                org.jfree.chart.labels.ItemLabelAnchor.INSIDE9, org.jfree.chart.ui.TextAnchor.CENTER_LEFT));
        ch.getCategoryPlot().setRenderer(r);
        styleCategory(ch, title);
        r.setDefaultItemLabelPaint(Color.WHITE);
        return img(ch, w, h);
    }

    private void panels(String file, String title, List<Map<String, Object>> sums, List<String> order,
                        String[][] metrics, int cols) throws IOException {
        int h = 150 + 42 * order.size();
        List<BufferedImage> imgs = new ArrayList<>();
        for (String[] m : metrics) imgs.add(statBars(sums, order, m[0], m[1], m[2], 620, h));
        compose(file, title, cols, imgs.toArray(new BufferedImage[0]));
    }

    public void v2Figures(List<Map<String, Object>> sums) throws IOException {
        int n = (int) sums.stream().map(m -> m.get("seed")).distinct().count();
        String sfx = " (moyenne ± écart-type sur " + n + " graines)";
        panels("v2_dynamic_weights", "Poids dynamiques et politique « U seul »" + sfx, sums,
                List.of("TCDRM-Predictive", "P-U seul", "P-poids dynamiques"), new String[][]{
                        {"w_cost_mean", "Poids effectif du coût ω1", "0.000"},
                        {"cost_placement", "Coût lié au placement ($)", "0.00"},
                        {"sla_T_violation_pct", "Lectures au-dessus de TSLA (%)", "0.00"},
                        {"max_day_pct_of_budget", "Cycle le plus cher (% du budget)", "0.0"}}, 2);
        panels("v2_saturation", "Saturation des fournisseurs" + sfx, sums,
                List.of("TCDRM", "TCDRM-Predictive", "P-saturation"), new String[][]{
                        {"max_site_load", "Réplicas hébergés par le site le plus chargé", "0.0"},
                        {"sites_used", "Sites ayant reçu au moins un réplica", "0.0"},
                        {"rt_mean", "Temps de réponse moyen (ms)", "0.0"},
                        {"cost_placement", "Coût lié au placement ($)", "0.00"}}, 2);
        panels("v2_antithrashing", "Anti-thrashing (suppression prédictive)" + sfx, sums,
                List.of("TCDRM", "TCDRM-Predictive", "P-anti-thrashing"), new String[][]{
                        {"thrash", "Réplicas recréés moins d'un cycle après suppression", "0.0"},
                        {"replicas_deleted", "Réplicas supprimés", "0.0"},
                        {"cost_placement", "Coût lié au placement ($)", "0.00"},
                        {"sla_T_violation_pct", "Lectures au-dessus de TSLA (%)", "0.00"}}, 2);
        panels("v2_summary", "TCDRM-Predictive v2 : bilan" + sfx, sums,
                List.of("NoRepLc", "TCDRM", "TCDRM-Predictive", "P-U seul", "P-poids dynamiques", "P-saturation",
                        "P-anti-thrashing", "TCDRM-Predictive v2"), new String[][]{
                        {"rt_mean", "Temps de réponse moyen (ms)", "0.0"},
                        {"cost_placement", "Coût lié au placement ($)", "0.0"},
                        {"sla_T_violation_pct", "Lectures au-dessus de TSLA (%)", "0.0"},
                        {"thrash", "Réplicas recréés moins d'un cycle après suppression", "0.0"}}, 2);
    }

    /** Scalabilité : temps moyen d'une décision de placement et durée d'une simulation selon la taille. */
    public void v2Scalability(List<Map<String, Object>> sc) throws IOException {
        String[][] axes = {{"sites", "Nombre de sites (fournisseurs × 3 régions)", "decision_us_mean", "Temps d'une décision (µs)"},
                {"sites", "Nombre de sites (fournisseurs × 3 régions)", "cand_per_decision", "Candidats évalués par décision P2"},
                {"queries", "Nombre de requêtes simulées", "wall_s", "Durée d'une simulation (s)"}};
        String[] strat = {"TCDRM", "TCDRM-Predictive"};
        List<BufferedImage> imgs = new ArrayList<>();
        for (String[] ax : axes) {
            XYChart c = xy(620, 400, ax[3], ax[1], ax[3]);
            c.getStyler().setMarkerSize(8);
            c.getStyler().setLegendPosition(Styler.LegendPosition.InsideNW);
            c.getStyler().setXAxisDecimalPattern("#,##0");
            c.getStyler().setYAxisMin(0.0);
            for (String st : strat) {
                java.util.TreeMap<Double, double[]> acc = new java.util.TreeMap<>();
                for (Map<String, Object> s : sc) {
                    if (!ax[0].equals(s.get("axis")) || !st.equals(s.get("strategy"))) continue;
                    double x = ((Number) s.get("axis_value")).doubleValue();
                    Object yv;
                    if (ax[2].equals("wall_s")) yv = ((Number) s.get("wall_ms")).doubleValue() / 1000.0;
                    else if (ax[2].equals("cand_per_decision")) yv = s.get("p2_decisions") instanceof Number dn && dn.doubleValue() > 0
                            ? ((Number) s.get("p2_candidates")).doubleValue() / dn.doubleValue() : null;
                    else yv = s.get(ax[2]);
                    if (!(yv instanceof Number y)) continue;
                    double[] a = acc.computeIfAbsent(x, k -> new double[2]);
                    a[0] += y.doubleValue();
                    a[1]++;
                }
                double[] x = new double[acc.size()], y = new double[acc.size()];
                int i = 0;
                for (var e : acc.entrySet()) { x[i] = e.getKey(); y[i++] = e.getValue()[0] / e.getValue()[1]; }
                if (x.length > 0) mark(line(c, st, x, y, 2.2f));
            }
            imgs.add(img(c));
        }
        compose("v2_scalability", "Scalabilité de la décision de placement (moyenne sur 5 graines)", 3,
                imgs.toArray(new BufferedImage[0]));
    }

    // S2 : moyennes sur les graines et sensibilité à PSLA
    private static List<Map<String, Object>> ofKind(List<Map<String, Object>> sums, String kind) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> m : sums) if (kind.equals(m.get("scenario"))) out.add(m);
        return out;
    }

    /** S2 : moyenne ± écart-type sur les graines, une colonne par type de requête. */
    public void s2MultiSeed(List<Map<String, Object>> s12) throws IOException { articleMultiSeed(s12, "s2", "S2"); }

    public void articleMultiSeed(List<Map<String, Object>> s12, String file, String name) throws IOException {
        List<String> order = List.of("NoRepLc", "TCDRM", "TCDRM-Predictive", "Predictive sans contraintes", "Replicate-All");
        String[][] metrics = {{"rt_mean", "Temps de réponse moyen (ms)", "0.0"},
                {"cost_placement", "Coût lié au placement ($)", "0.00"},
                {"replicas_created", "Réplicas créés", "0"},
                {"sla_T_violation_pct", "Lectures au-dessus de TSLA (%)", "0.0"}};
        int n = (int) s12.stream().map(m -> m.get("seed")).distinct().count();
        List<BufferedImage> imgs = new ArrayList<>();
        for (String[] m : metrics) {
            for (String kind : new String[]{"simple", "complex"}) {
                imgs.add(statBars(ofKind(s12, kind), order, m[0], KIND_FR.get(kind) + " — " + m[1], m[2], 620, 360));
            }
        }
        compose(file + "_multiseed", name + " — moyenne ± écart-type sur " + n + " graines", 2, imgs.toArray(new BufferedImage[0]));
    }

    /** S2 : effet du seuil PSLA sur le temps de réponse, le coût total et le nombre de réplicas. */
    public void s2Sensitivity(List<Map<String, Object>> sens) throws IOException {
        String[] lines = {"NoRepLc", "TCDRM", "TCDRM-Predictive", "Replicate-All"};
        String[][] metrics = {{"rt_mean", "Temps de réponse moyen", "ms"}, {"cost_total", "Coût total", "$"},
                {"replicas_created", "Réplicas créés", "réplicas"}};
        int[] pslas = {50, 100, 200, 400};
        int n = (int) sens.stream().map(m -> m.get("seed")).distinct().count();
        List<BufferedImage> imgs = new ArrayList<>();
        for (String kind : new String[]{"simple", "complex"}) {
            for (String[] m : metrics) {
                XYChart c = xy(520, 380, KIND_FR.get(kind) + " — " + m[1], "PSLA (accès)", m[2]);
                c.getStyler().setMarkerSize(8);
                c.getStyler().setYAxisMin(0.0);
                c.getStyler().setLegendVisible(kind.equals("simple") && m[0].equals("rt_mean"));
                c.getStyler().setLegendPosition(Styler.LegendPosition.InsideNW);
                for (String st : lines) {
                    double[] x = new double[pslas.length], y = new double[pslas.length];
                    for (int i = 0; i < pslas.length; i++) {
                        x[i] = pslas[i];
                        double t = 0;
                        int k = 0;
                        for (Map<String, Object> s : sens) {
                            if (kind.equals(s.get("scenario")) && st.equals(s.get("strategy"))
                                    && ((Number) s.get("psla")).intValue() == pslas[i]) {
                                t += ((Number) s.get(m[0])).doubleValue();
                                k++;
                            }
                        }
                        y[i] = t / Math.max(1, k);
                    }
                    mark(line(c, st, x, y, 2.2f));
                }
                imgs.add(img(c));
            }
        }
        compose("s2_sensitivity", "S2 — sensibilité au seuil de popularité PSLA (moyenne sur " + n + " graines)", 3,
                imgs.toArray(new BufferedImage[0]));
    }

    /** S2 : nombre moyen de réplicas créés par site, pour chaque stratégie (requêtes simples et complexes). */
    public void s2ReplicaSites(List<Map<String, Object>> rows, List<String> siteOrder) throws IOException {
        articleReplicaSites(rows, siteOrder, "s2", "S2");
    }

    public void articleReplicaSites(List<Map<String, Object>> rows, List<String> siteOrder, String file, String name) throws IOException {
        String[] strat = {"TCDRM", "TCDRM-Predictive", "Replicate-All"};
        List<BufferedImage> imgs = new ArrayList<>();
        for (String kind : new String[]{"simple", "complex"}) {
            DefaultCategoryDataset ds = new DefaultCategoryDataset();
            for (String st : strat) {
                for (String site : siteOrder) {
                    double v = 0;
                    for (Map<String, Object> r : rows) {
                        if (kind.equals(r.get("scenario")) && st.equals(r.get("strategy")) && site.equals(r.get("site"))) {
                            v = ((Number) r.get("replicas_mean_per_seed")).doubleValue();
                        }
                    }
                    ds.addValue(v, st, site);
                }
            }
            JFreeChart ch = bars(KIND_FR.get(kind) + " — réplicas créés par site (moyenne par graine)", "Réplicas", ds, false, "0.#");
            BarRenderer br = (BarRenderer) ch.getCategoryPlot().getRenderer();
            for (int k = 0; k < strat.length; k++) br.setSeriesPaint(k, COL.get(strat[k]));
            br.setMaximumBarWidth(0.06);
            imgs.add(img(ch, 1240, 400));
        }
        compose(file + "_replica_sites", name + " — répartition des réplicas par site (NoRepLc : aucun réplica)", 1,
                imgs.toArray(new BufferedImage[0]));
    }

    // S3 : réalisme des prix (scénario de l'article)
    static final String[] S3_CMP = {"NoRepLc", "TCDRM", "Replicate-All", "TCDRM-Predictive"};
    public static final String[] PRICE_CFG = {"Table 1 (S2)", "Prix publics, CSLA Table 1", "Prix publics, CSLA × 7 (S3)"};

    /** Scénario de l'article : Table 1 (S2) contre prix publics, requêtes simples et complexes. */
    public void s3PriceComparison(List<Map<String, Object>> cmp) throws IOException {
        String[][] metrics = {{"rt_mean", "Temps de réponse moyen (ms)", "0"},
                {"cost_pct_of_norep", "Coût lié au placement (% de NoRepLc)", "0"},
                {"replicas_created", "Réplicas créés", "0.#"}};
        List<BufferedImage> imgs = new ArrayList<>();
        for (String[] m : metrics) {
            for (String kind : new String[]{"simple", "complex"}) {
                DefaultCategoryDataset ds = new DefaultCategoryDataset();
                for (String st : S3_CMP) {
                    for (String pc : PRICE_CFG) {
                        double t = 0;
                        int n = 0;
                        for (Map<String, Object> r : cmp) {
                            if (pc.equals(r.get("prices")) && kind.equals(r.get("scenario")) && st.equals(r.get("strategy"))
                                    && r.get(m[0]) instanceof Number v) {
                                t += v.doubleValue();
                                n++;
                            }
                        }
                        ds.addValue(n == 0 ? 0 : t / n, st, pc);
                    }
                }
                JFreeChart ch = bars(KIND_FR.get(kind) + " — " + m[1], null, ds, false, m[2]);
                ch.getCategoryPlot().getDomainAxis().setCategoryLabelPositions(org.jfree.chart.axis.CategoryLabelPositions.STANDARD);
                ch.getCategoryPlot().getDomainAxis().setMaximumCategoryLabelLines(2);
                BarRenderer br = (BarRenderer) ch.getCategoryPlot().getRenderer();
                for (int k = 0; k < S3_CMP.length; k++) br.setSeriesPaint(k, COL.get(S3_CMP[k]));
                br.setMaximumBarWidth(0.07);
                imgs.add(img(ch, 680, 400));
            }
        }
        int n = (int) cmp.stream().map(m -> m.get("seed")).distinct().count();
        compose("s3_price_comparison", "Scénario de l'article : prix de la Table 1 (S2) contre prix publics (S3), moyenne sur "
                + n + " graines", 2, imgs.toArray(new BufferedImage[0]));
    }

    // S4 : requêtes aléatoires
    /** Réplicas créés par site (une seule sorte de requête). */
    public void s4ReplicaSites(List<Map<String, Object>> rows, List<String> siteOrder) throws IOException {
        String[] strat = {"TCDRM", "Replicate-All", "TCDRM-Predictive"};
        DefaultCategoryDataset ds = new DefaultCategoryDataset();
        for (String st : strat) {
            for (String site : siteOrder) {
                double v = 0;
                for (Map<String, Object> r : rows) {
                    if (st.equals(r.get("strategy")) && site.equals(r.get("site"))) v = ((Number) r.get("replicas_mean_per_seed")).doubleValue();
                }
                ds.addValue(v, st, site);
            }
        }
        JFreeChart ch = bars("Réplicas créés par site (moyenne par graine)", "Réplicas", ds, false, "0.#");
        BarRenderer br = (BarRenderer) ch.getCategoryPlot().getRenderer();
        for (int k = 0; k < strat.length; k++) br.setSeriesPaint(k, COL.get(strat[k]));
        br.setMaximumBarWidth(0.06);
        compose(dyn + "_replica_sites", dynName + " — où vont les réplicas ? (NoRepLc : aucun réplica)", 1, img(ch, 1240, 420));
    }

    /** Réplicas par rôle de relation et cycle de vie de la relation partagée et d'une relation rare. */
    public void s4Relations(List<Map<String, Object>> relRows, List<Result> seedRes, int seed,
                            fr.tcdrm.config.SimConfig c, fr.tcdrm.workload.Workloads.Workload w0) throws IOException {
        String[] rels = fr.tcdrm.workload.Workloads.ROLES;
        String[] strat = {"TCDRM", "Replicate-All", "TCDRM-Predictive"};
        List<BufferedImage> top = new ArrayList<>();
        for (String[] m : new String[][]{{"created_mean", "Réplicas créés selon le rôle de la relation"},
                {"deleted_mean", "Réplicas supprimés selon le rôle de la relation"}}) {
            DefaultCategoryDataset ds = new DefaultCategoryDataset();
            for (String st : strat) {
                for (String f : rels) {
                    double v = 0;
                    for (Map<String, Object> r : relRows) {
                        if (st.equals(r.get("strategy")) && f.equals(r.get("role"))) v = ((Number) r.get(m[0])).doubleValue();
                    }
                    ds.addValue(v, st, f);
                }
            }
            JFreeChart ch = bars(m[1] + " (moyenne par graine)", "Réplicas", ds, false, "0.#");
            ch.getCategoryPlot().getDomainAxis().setCategoryLabelPositions(org.jfree.chart.axis.CategoryLabelPositions.STANDARD);
            BarRenderer br = (BarRenderer) ch.getCategoryPlot().getRenderer();
            for (int k = 0; k < strat.length; k++) br.setSeriesPaint(k, COL.get(strat[k]));
            br.setMaximumBarWidth(0.07);
            top.add(img(ch, 650, 400));
        }
        compose(dyn + "_relations", dynName + " — réplicas selon le rôle de la relation (partagée par toutes les requêtes → propre à la requête rare)", 2,
                top.toArray(new BufferedImage[0]));
        int f1 = -1, f20 = -1;
        for (var e : w0.roles.entrySet()) {
            if (f1 < 0 && e.getValue().equals("partagée")) f1 = e.getKey();
        }
        // relation rare : celle qui a reçu un réplica, sinon la première
        for (Result r : seedRes) for (Map<String, Object> e : r.mgr.events) {
            if (f20 < 0 && "rare".equals(w0.roles.get((Integer) e.get("rel")))) f20 = (Integer) e.get("rel");
        }
        if (f20 < 0) for (var e : w0.roles.entrySet()) if (f20 < 0 && e.getValue().equals("rare")) f20 = e.getKey();
        String l1 = fr.tcdrm.workload.Workloads.fLabel(c, f1) + " (partagée)", l20 = fr.tcdrm.workload.Workloads.fLabel(c, f20) + " (rare)";
        XYChart ch = xy(1300, 380, "", "Période (" + periodQueries + " requêtes)", "Réplicas actifs");
        ch.getStyler().setLegendPosition(Styler.LegendPosition.InsideNE);
        ch.getStyler().setXAxisMin(0.0);
        ch.getStyler().setXAxisMax((double) nPeriods);
        ch.getStyler().setYAxisMin(0.0);
        ch.getStyler().setYAxisMax(4.5);
        for (String st : new String[]{"TCDRM", "TCDRM-Predictive"}) {
            Result r = find(seedRes, st);
            for (int rel : new int[]{f1, f20}) {
                List<double[]> ev = new ArrayList<>();
                for (Map<String, Object> e : r.mgr.events) {
                    if (!e.get("rel").equals(rel)) continue;
                    ev.add(new double[]{(Double) e.get("t"), "create".equals(e.get("type")) ? 1 : -1});
                }
                ev.sort((a, b) -> Double.compare(a[0], b[0]));
                double[] x = new double[2 * ev.size() + 2], y = new double[2 * ev.size() + 2];
                int k = 0;
                double cur = 0;
                x[k] = 0; y[k++] = 0;
                for (double[] e : ev) {
                    x[k] = e[0]; y[k++] = cur;
                    cur += e[1];
                    x[k] = e[0] + 1e-6; y[k++] = cur;
                }
                x[k] = nPeriods; y[k] = cur;
                XYSeries sr = ch.addSeries(st + " — " + (rel == f1 ? l1 : l20), x, y);
                sr.setLineColor(COL.get(st));
                sr.setMarker(SeriesMarkers.NONE);
                sr.setLineStyle(rel == f1 ? new BasicStroke(2.4f) : new BasicStroke(2.4f, BasicStroke.CAP_BUTT,
                        BasicStroke.JOIN_MITER, 10f, new float[]{7f, 5f}, 0f));
            }
        }
        ch.addAnnotation(new AnnotationLine(w0.burstStart, true, false));
        ch.addAnnotation(new AnnotationLine(w0.burstEnd, true, false));
        ch.addAnnotation(new AnnotationText("rafale de la requête rare", (w0.burstStart + w0.burstEnd) / 2.0, 4.2, false));
        compose(dyn + "_lifecycle", dynName + " — réplicas actifs de la relation partagée (trait plein) et d'une relation rare (tirets), graine " + seed, 1,
                img(ch));
    }

    // S5 : charge non stationnaire et variation des prix
    /** Part de chaque modèle de requête par période (graine montrée), avec la variation des prix. */
    public void s5Workload(fr.tcdrm.workload.Workloads.Workload w, fr.tcdrm.config.SimConfig c, int priceHour, int seed) throws IOException {
        Color[] pal = {new Color(0x2a78d6), new Color(0xeb6834), new Color(0x1baf7a), new Color(0x6250d6), new Color(0x8a8984)};
        int K = w.templates.length, T = w.nPeriods;
        double[][] cnt = new double[K][T];
        double[] tot = new double[T];
        for (fr.tcdrm.workload.Query q : w.queries) {
            int p = (int) q.tHours;
            if (p >= T || q.template < 0) continue;
            cnt[q.template][p]++;
            tot[p]++;
        }
        XYChart ch = xy(1300, 420, "", "Période (" + periodQueries + " requêtes)", "Part des requêtes (%)");
        ch.getStyler().setLegendPosition(Styler.LegendPosition.OutsideE);
        ch.getStyler().setXAxisMin(0.0);
        ch.getStyler().setXAxisMax((double) T);
        ch.getStyler().setYAxisMin(0.0);
        for (int j = 0; j < K; j++) {
            double[] y = new double[T];
            for (int p = 0; p < T; p++) y[p] = tot[p] > 0 ? 100 * cnt[j][p] / tot[p] : 0;
            StringBuilder rel = new StringBuilder();
            for (int r : w.templates[j]) rel.append(rel.length() > 0 ? " " : "").append(fr.tcdrm.workload.Workloads.fLabel(c, r));
            XYSeries sr = ch.addSeries("Q" + (j + 1) + (j == w.rareTemplate ? " (rare)" : "") + " = {" + rel + "}", seq(T, 0.5), y);
            sr.setLineColor(pal[j % pal.length]);
            sr.setMarker(SeriesMarkers.NONE);
            sr.setLineStyle(new BasicStroke(2.2f));
        }
        if (priceHour > 0) {
            ch.addAnnotation(new AnnotationLine(priceHour, true, false));
            ch.addAnnotation(new AnnotationText("variation des prix", priceHour + 2.5, 5, false));
        }
        compose("s5_workload", "S5 — part de chaque modèle de requête par période (graine " + seed + ")", 1, img(ch));
    }

    /** S5 : variantes v2 (anti-thrashing, poids dynamiques) sur la charge non stationnaire. */
    public void s5V2(List<Map<String, Object>> sums) throws IOException {
        int n = (int) sums.stream().map(m -> m.get("seed")).distinct().count();
        panels("s5_v2", "S5 — variantes v2 de TCDRM-Predictive (moyenne ± écart-type sur " + n + " graines)", sums,
                List.of("TCDRM", "TCDRM-Predictive", "P-poids dynamiques", "P-anti-thrashing", "TCDRM-Predictive v2"),
                new String[][]{
                        {"rt_mean", "Temps de réponse moyen (ms)", "0.0"},
                        {"cost_placement", "Coût lié au placement ($)", "0.0"},
                        {"replicas_deleted", "Réplicas supprimés", "0.0"},
                        {"thrash", "Réplicas recréés moins d'un cycle après suppression", "0.0"}}, 2);
    }

    /** Sensibilité aux choix de modélisation (facteur du CSLA, échelle des latences) : S3 simple, S3 complexe, S5. */
    public void modelSensitivity(List<Map<String, Object>> rows, String sweep, String file, String title, String xl,
                                 double[] xs) throws IOException {
        String[] lines = {"NoRepLc", "TCDRM", "Replicate-All", "TCDRM-Predictive"};
        String[][] cols = {{"S3", "simple", "S3, requêtes simples"}, {"S3", "complex", "S3, requêtes complexes"}, {"S5", "simple", "S5"}};
        String[][] metrics = {{"rt_mean", "Temps de réponse moyen (ms)"}, {"cost_pct_of_norep", "Coût lié au placement (% de NoRepLc)"}};
        List<BufferedImage> imgs = new ArrayList<>();
        for (String[] m : metrics) {
            for (String[] col : cols) {
                XYChart c = xy(520, 380, col[2] + " — " + m[1], xl, null);
                c.getStyler().setMarkerSize(8);
                c.getStyler().setYAxisMin(0.0);
                c.getStyler().setLegendVisible(m[0].equals("rt_mean") && col[0].equals("S5"));
                c.getStyler().setLegendPosition(Styler.LegendPosition.InsideNE);
                c.getStyler().setXAxisDecimalPattern("0.#");
                for (String st : lines) {
                    double[] y = new double[xs.length];
                    boolean any = false;
                    for (int i = 0; i < xs.length; i++) {
                        double t = 0;
                        int k = 0;
                        for (Map<String, Object> r : rows) {
                            if (!sweep.equals(r.get("sweep")) || !col[0].equals(r.get("scen")) || !col[1].equals(r.get("scenario"))
                                    || !st.equals(r.get("strategy")) || Math.abs(((Number) r.get("factor")).doubleValue() - xs[i]) > 1e-9) continue;
                            if (r.get(m[0]) instanceof Number v) { t += v.doubleValue(); k++; }
                        }
                        y[i] = k > 0 ? t / k : Double.NaN;
                        any |= k > 0;
                    }
                    if (any) mark(line(c, st, xs, y, 2.2f));
                }
                imgs.add(img(c));
            }
        }
        compose(file, title, 3, imgs.toArray(new BufferedImage[0]));
    }
}
