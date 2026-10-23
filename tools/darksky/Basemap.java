import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.Deflater;
import java.util.zip.DeflaterOutputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Step 3 of the dark-sky map: the base map the light is drawn under, from Natural Earth 1:10m
 * (public domain, naturalearthdata.com) and the dark-sky places on Wikidata (CC0).
 *
 *   java tools\darksky\Basemap.java <ne zip folder> <wikidata dark-sky csv> <out folder>
 *
 * The dark-sky places CSV comes from query.wikidata.org/sparql (Accept: text/csv):
 *
 *   SELECT ?item ?itemLabel (SAMPLE(?coord) AS ?coord)
 *          (GROUP_CONCAT(DISTINCT ?typeLabel;separator="|") AS ?types) WHERE {
 *     VALUES ?type { wd:Q52216504 wd:Q72114283 wd:Q72114299 wd:Q72114249 wd:Q72114320 wd:Q3457162 }
 *     { ?item wdt:P1435 ?type } UNION { ?item wdt:P31/wdt:P279* ?type }
 *     ?item wdt:P625 ?coord . ?type rdfs:label ?typeLabel . FILTER(LANG(?typeLabel)="en")
 *     SERVICE wikibase:label { bd:serviceParam wikibase:language "en,mul,nl,de,fr,es,it,pt,pl". }
 *   } GROUP BY ?item ?itemLabel
 *
 * Writes, each deflated: coast.bin, lakes.bin, borders.bin, roads.bin (polylines, simplified to
 * about 150 m), places.bin (towns and cities, largest first) and darksites.bin.
 *
 * Polyline file: "SCPL", version 1, line count; per line its class (byte), point count (varint),
 * the first point as int32 longitude and latitude in 1e-5 deg, then zigzag varint differences.
 * Place file: "SCPT", version 1, count; per place int32 lon, lat (1e-5 deg), int32 population
 * (or a type code for dark-sky places) and a UTF-8 name (unsigned short length first).
 */
public class Basemap {

    public static void main(String[] a) throws Exception {
        String ne = a[0], out = a[2];
        Files.createDirectories(Paths.get(out));
        lines(ne + "/ne_10m_coastline.zip", out + "/coast.bin", 0.0015, r -> 0);
        lines(ne + "/ne_10m_lakes.zip", out + "/lakes.bin", 0.0015, r -> 0);
        lines(ne + "/ne_10m_admin_0_boundary_lines_land.zip", out + "/borders.bin", 0.002, r -> 0);
        lines(ne + "/ne_10m_roads.zip", out + "/roads.bin", 0.002, r -> {
            String type = r.getOrDefault("type", "");
            switch (type) {
                case "Major Highway": return 1;
                case "Secondary Highway":
                case "Beltway": return 2;
                case "Road": return 3;
                default: return -1;  // ferries and the unclassified
            }
        });
        places(ne + "/ne_10m_populated_places_simple.zip", out + "/places.bin");
        darkSites(a[1], out + "/darksites.bin");
    }

    interface Classifier { int classOf(Map<String, String> record); }

    // ------------------------------------------------------------------------ shapefiles

    static byte[] entry(String zip, String ext) throws IOException {
        try (ZipFile z = new ZipFile(zip)) {
            for (ZipEntry e : Collections.list(z.entries())) {
                if (e.getName().toLowerCase().endsWith(ext)) {
                    try (InputStream in = z.getInputStream(e)) { return in.readAllBytes(); }
                }
            }
        }
        throw new IOException("no " + ext + " in " + zip);
    }

    /** The .dbf's records as field name -> trimmed text. */
    static List<Map<String, String>> dbf(byte[] d) {
        ByteBuffer b = ByteBuffer.wrap(d).order(ByteOrder.LITTLE_ENDIAN);
        int n = b.getInt(4), hlen = b.getShort(8) & 0xffff, rlen = b.getShort(10) & 0xffff;
        List<String> names = new ArrayList<>();
        List<int[]> pos = new ArrayList<>();
        int off = 1;
        for (int p = 32; d[p] != 0x0d; p += 32) {
            names.add(new String(d, p, 11, StandardCharsets.ISO_8859_1).replace("\0", "").trim().toLowerCase());
            int len = d[p + 16] & 0xff;
            pos.add(new int[]{off, len});
            off += len;
        }
        List<Map<String, String>> out = new ArrayList<>(n);
        for (int r = 0; r < n; r++) {
            Map<String, String> m = new HashMap<>();
            for (int i = 0; i < names.size(); i++) {
                int[] pl = pos.get(i);
                m.put(names.get(i), new String(d, hlen + r * rlen + pl[0], pl[1], StandardCharsets.UTF_8).trim());
            }
            out.add(m);
        }
        return out;
    }

    /** Every part of every polyline or polygon record, as [x0, y0, x1, y1, ...], with its record. */
    static void shapes(byte[] shp, java.util.function.BiConsumer<Integer, double[]> part) {
        ByteBuffer b = ByteBuffer.wrap(shp);
        int pos = 100, rec = 0;
        while (pos + 8 <= shp.length) {
            b.order(ByteOrder.BIG_ENDIAN);
            int lenWords = b.getInt(pos + 4);
            int at = pos + 8;
            b.order(ByteOrder.LITTLE_ENDIAN);
            int type = b.getInt(at);
            if (type == 3 || type == 5) {
                int numParts = b.getInt(at + 36), numPoints = b.getInt(at + 40);
                int partsAt = at + 44, pointsAt = partsAt + 4 * numParts;
                for (int p = 0; p < numParts; p++) {
                    int s = b.getInt(partsAt + 4 * p);
                    int e = p + 1 < numParts ? b.getInt(partsAt + 4 * (p + 1)) : numPoints;
                    double[] xy = new double[2 * (e - s)];
                    for (int i = s; i < e; i++) {
                        xy[2 * (i - s)] = b.getDouble(pointsAt + 16 * i);
                        xy[2 * (i - s) + 1] = b.getDouble(pointsAt + 16 * i + 8);
                    }
                    part.accept(rec, xy);
                }
            }
            pos = at + lenWords * 2;
            rec++;
        }
    }

    /** Douglas-Peucker with [tol] degrees (longitude scaled by the latitude's cosine). */
    static double[] simplify(double[] xy, double tol) {
        int n = xy.length / 2;
        if (n <= 2) return xy;
        boolean[] keep = new boolean[n];
        keep[0] = keep[n - 1] = true;
        java.util.ArrayDeque<int[]> stack = new java.util.ArrayDeque<>();
        stack.push(new int[]{0, n - 1});
        while (!stack.isEmpty()) {
            int[] s = stack.pop();
            int i0 = s[0], i1 = s[1];
            if (i1 <= i0 + 1) continue;
            double k = Math.cos(Math.toRadians(0.5 * (xy[2 * i0 + 1] + xy[2 * i1 + 1])));
            double ax = xy[2 * i0] * k, ay = xy[2 * i0 + 1], bx = xy[2 * i1] * k, by = xy[2 * i1 + 1];
            double dx = bx - ax, dy = by - ay, len2 = dx * dx + dy * dy;
            double best = -1;
            int bi = -1;
            for (int i = i0 + 1; i < i1; i++) {
                double px = xy[2 * i] * k, py = xy[2 * i + 1];
                double t = len2 > 0 ? ((px - ax) * dx + (py - ay) * dy) / len2 : 0;
                t = Math.max(0, Math.min(1, t));
                double ex = ax + t * dx - px, ey = ay + t * dy - py;
                double d2 = ex * ex + ey * ey;
                if (d2 > best) { best = d2; bi = i; }
            }
            if (best > tol * tol) {
                keep[bi] = true;
                stack.push(new int[]{i0, bi});
                stack.push(new int[]{bi, i1});
            }
        }
        int m = 0;
        for (boolean kp : keep) if (kp) m++;
        double[] o = new double[2 * m];
        int j = 0;
        for (int i = 0; i < n; i++) if (keep[i]) { o[2 * j] = xy[2 * i]; o[2 * j + 1] = xy[2 * i + 1]; j++; }
        return o;
    }

    static void varint(OutputStream o, long v) throws IOException {
        while ((v & ~0x7FL) != 0) { o.write((int) ((v & 0x7F) | 0x80)); v >>>= 7; }
        o.write((int) v);
    }

    static long zigzag(long v) { return (v << 1) ^ (v >> 63); }

    static void lines(String zip, String out, double tol, Classifier classify) throws IOException {
        byte[] shp = entry(zip, ".shp");
        List<Map<String, String>> recs = dbf(entry(zip, ".dbf"));
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        int[] count = {0};
        long[] points = {0, 0};
        shapes(shp, (rec, xy) -> {
            int cls = classify.classOf(recs.get(rec));
            if (cls < 0) return;
            double[] s = simplify(xy, tol);
            int n = s.length / 2;
            if (n < 2) return;
            try {
                body.write(cls);
                varint(body, n);
                long px = Math.round(s[0] * 1e5), py = Math.round(s[1] * 1e5);
                DataOutputStream d = new DataOutputStream(body);
                d.writeInt((int) px);
                d.writeInt((int) py);
                for (int i = 1; i < n; i++) {
                    long x = Math.round(s[2 * i] * 1e5), y = Math.round(s[2 * i + 1] * 1e5);
                    varint(body, zigzag(x - px));
                    varint(body, zigzag(y - py));
                    px = x;
                    py = y;
                }
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
            count[0]++;
            points[0] += xy.length / 2;
            points[1] += n;
        });
        try (DataOutputStream o = new DataOutputStream(new DeflaterOutputStream(new FileOutputStream(out), new Deflater(9)))) {
            o.writeBytes("SCPL");
            o.writeInt(1);
            o.writeInt(count[0]);
            body.writeTo(o);
        }
        System.out.printf("%s: %d lines, %d -> %d points, %.2f MB%n", out, count[0], points[0], points[1],
            Files.size(Paths.get(out)) / 1e6);
    }

    // --------------------------------------------------------------------------- points

    static void writePlaces(String out, List<Object[]> rows) throws IOException {
        try (DataOutputStream o = new DataOutputStream(new DeflaterOutputStream(new FileOutputStream(out), new Deflater(9)))) {
            o.writeBytes("SCPT");
            o.writeInt(1);
            o.writeInt(rows.size());
            for (Object[] r : rows) {
                o.writeInt((int) Math.round((Double) r[0] * 1e5));
                o.writeInt((int) Math.round((Double) r[1] * 1e5));
                o.writeInt((Integer) r[2]);
                byte[] name = ((String) r[3]).getBytes(StandardCharsets.UTF_8);
                o.writeShort(name.length);
                o.write(name);
            }
        }
        System.out.printf("%s: %d places, %.2f MB%n", out, rows.size(), Files.size(Paths.get(out)) / 1e6);
    }

    static void places(String zip, String out) throws IOException {
        List<Map<String, String>> recs = dbf(entry(zip, ".dbf"));
        List<Object[]> rows = new ArrayList<>();
        for (Map<String, String> r : recs) {
            try {
                double lon = Double.parseDouble(r.get("longitude")), lat = Double.parseDouble(r.get("latitude"));
                int pop = (int) Math.max(0, Math.min(Integer.MAX_VALUE, Double.parseDouble(r.getOrDefault("pop_max", "0"))));
                String name = r.getOrDefault("name", "").trim();
                if (name.isEmpty()) continue;
                rows.add(new Object[]{lon, lat, pop, name});
            } catch (NumberFormatException e) {
                // a record without coordinates
            }
        }
        rows.sort((x, y) -> Integer.compare((Integer) y[2], (Integer) x[2]));
        writePlaces(out, rows);
    }

    /** Wikidata dark-sky places (CC0); type: 1 park, 2 reserve, 3 sanctuary, 4 community, 5 other. */
    static void darkSites(String csv, String out) throws IOException {
        List<String> lines = Files.readAllLines(Paths.get(csv), StandardCharsets.UTF_8);
        Pattern p = Pattern.compile("^[^,]*,(\"[^\"]*\"|[^,]*),Point\\((-?[0-9.]+) (-?[0-9.]+)\\),(.*)$");
        List<Object[]> rows = new ArrayList<>();
        for (String line : lines.subList(1, lines.size())) {
            Matcher m = p.matcher(line);
            if (!m.find()) continue;
            String name = m.group(1).replace("\"", "").trim();
            if (name.matches("Q[0-9]+")) continue;
            double lon = Double.parseDouble(m.group(2)), lat = Double.parseDouble(m.group(3));
            String types = m.group(4).toLowerCase();
            int type = types.contains("park") ? 1 : types.contains("reserve") ? 2 : types.contains("sanctuary") ? 3
                : types.contains("community") ? 4 : 5;
            // The same place listed twice (a park and its dark-sky designation): keep one.
            boolean dup = false;
            for (Object[] r : rows) {
                double dLat = (Double) r[1] - lat, dLon = ((Double) r[0] - lon) * Math.cos(Math.toRadians(lat));
                if (Math.hypot(dLat, dLon) * 111.32 < 5.0) { dup = true; break; }
            }
            if (!dup) rows.add(new Object[]{lon, lat, type, name});
        }
        writePlaces(out, rows);
    }
}
