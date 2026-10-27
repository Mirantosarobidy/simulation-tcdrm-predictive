package fr.tcdrm.util;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Écriture CSV minimale (séparateur virgule, point décimal). */
public final class Csv {
    private Csv() { }

    public static String fmt(Object v) {
        if (v == null) return "";
        if (v instanceof Double d) return String.format(Locale.ROOT, "%.6g", d);
        if (v instanceof Float f) return String.format(Locale.ROOT, "%.6g", f);
        String s = v.toString();
        return s.contains(",") ? "\"" + s + "\"" : s;
    }

    public static void writeMaps(Path p, List<Map<String, Object>> rows) throws IOException {
        Set<String> cols = new LinkedHashSet<>();
        for (Map<String, Object> r : rows) cols.addAll(r.keySet());
        List<String> cl = new ArrayList<>(cols);
        Files.createDirectories(p.getParent());
        try (PrintWriter w = new PrintWriter(Files.newBufferedWriter(p, StandardCharsets.UTF_8))) {
            w.println(String.join(",", cl));
            for (Map<String, Object> r : rows) {
                StringBuilder sb = new StringBuilder();
                for (int i = 0; i < cl.size(); i++) {
                    if (i > 0) sb.append(',');
                    sb.append(fmt(r.get(cl.get(i))));
                }
                w.println(sb);
            }
        }
    }

    public static void writeRows(Path p, String[] header, List<Object[]> rows) throws IOException {
        Files.createDirectories(p.getParent());
        try (PrintWriter w = new PrintWriter(Files.newBufferedWriter(p, StandardCharsets.UTF_8))) {
            w.println(String.join(",", header));
            for (Object[] r : rows) {
                StringBuilder sb = new StringBuilder();
                for (int i = 0; i < r.length; i++) {
                    if (i > 0) sb.append(',');
                    sb.append(fmt(r[i]));
                }
                w.println(sb);
            }
        }
    }
}
