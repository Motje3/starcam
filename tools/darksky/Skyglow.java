import java.io.BufferedInputStream;
import java.io.DataOutputStream;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.IntStream;
import java.util.zip.Deflater;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Step 2 of the dark-sky map: artificial sky brightness everywhere, from the light grid that
 * Lights.java made, written as the app's asset (assets/darksky/glow.bin).
 *
 * The model is Duriscoe et al. 2018, "A simplified model of all-sky artificial sky glow derived
 * from VIIRS Day/Night band data", JQSRT 214:133-145 (doi:10.1016/j.jqsrt.2018.04.028, open
 * access, CC BY 4.0): the all-sky average artificial luminance ratio
 *
 *   ALR = sum_i r_i * d_i^-beta(d_i) / 562.72,   beta(d) = 2.3 * (d / 350 km)^0.28,
 *
 * r_i the VIIRS radiance (nW/cm2/sr) of 450 m cells within 300 km at distance d_i (km). ALR is the
 * artificial all-sky luminance over 250 ucd/m2 (0.3 good, 3 poor; the Milky Way goes at about
 * 10). Here the light comes summed per map cell (radiance times km2), so each cell counts as
 * (light / 0.2025 km2) of the model's cells.
 *
 * The sum runs in two parts so the whole world takes minutes: near the observer (up to 45 km)
 * cell by cell at the map's 1/30 deg; further out (20-300 km) on a 0.1 deg grid of summed light,
 * computed at 0.1 deg and interpolated; a smoothstep from 20 to 45 km hands one over to the other.
 * Water (Natural Earth 10m land minus lakes) is stored as 0, land as 1 + 40 * (log10 ALR + 2.5),
 * in 5 x 5 deg tiles, each deflated on its own so the app reads only the ones it shows.
 *
 *   java -Xmx10g tools\darksky\Skyglow.java <lights.f32> <ne_10m_land.zip> <ne_10m_lakes.zip>
 *        <glow.bin> [nsdmap.geojson]
 *
 * With the NPS night-sky measurements (nps.gov/subjects/nightskies/nsdmap.geojson) it also
 * prints the model against 656 measured nights: ALR and the zenith brightness.
 */
public class Skyglow {
    static final int CPD = 30;                 // map cells per degree
    static final double LAT_MAX = 75.0, LAT_MIN = -60.0;
    static final int W = 360 * CPD;
    static final int H = (int) Math.round((LAT_MAX - LAT_MIN) * CPD);
    static final double KM_PER_DEG = 111.32;

    static final int COARSE_PER_DEG = 10;      // far-field grid
    static final int CW = 360 * COARSE_PER_DEG;
    static final int CH = (int) Math.round((LAT_MAX - LAT_MIN) * COARSE_PER_DEG);
    static final int FACTOR = CPD / COARSE_PER_DEG;

    static final double NEAR_KM = 45.0, BLEND_FROM_KM = 20.0, FAR_KM = 300.0;
    static final double MODEL_CELL_KM2 = 0.45 * 0.45;
    static final double MODEL_NORM = 562.72;

    static final int TILE_DEG = 5;
    static final int TILE = TILE_DEG * CPD;
    static final double Q_LOG_MIN = -2.5;      // log10 ALR at quantized value 1
    static final double Q_PER_DECADE = 40.0;

    static double beta(double d) { return 2.3 * Math.pow(d / 350.0, 0.28); }

    /** Model weight of light at d km, per unit of (radiance x km2). */
    static double kernel(double d) { return Math.pow(d, -beta(d)) / (MODEL_CELL_KM2 * MODEL_NORM); }

    /** 0 below BLEND_FROM_KM, 1 from NEAR_KM: the far field's share of the kernel. */
    static double blend(double d) {
        if (d <= BLEND_FROM_KM) return 0;
        if (d >= NEAR_KM) return 1;
        double t = (d - BLEND_FROM_KM) / (NEAR_KM - BLEND_FROM_KM);
        return t * t * (3 - 2 * t);
    }

    static double cellLat(int y) { return LAT_MAX - (y + 0.5) / CPD; }
    static double coarseLat(int y) { return LAT_MAX - (y + 0.5) / COARSE_PER_DEG; }

    public static void main(String[] args) throws Exception {
        long t0 = System.currentTimeMillis();
        float[] light = readLights(args[0]);
        System.out.printf("lights loaded (%.0f s)%n", (System.currentTimeMillis() - t0) / 1000.0);

        boolean[] land = new boolean[W * H];
        rasterize(args[1], land, true);
        rasterize(args[2], land, false);
        long landCells = 0;
        for (boolean b : land) if (b) landCells++;
        System.out.printf("land mask: %d cells (%.0f s)%n", landCells, (System.currentTimeMillis() - t0) / 1000.0);

        float[] far = farField(light);
        System.out.printf("far field done (%.0f s)%n", (System.currentTimeMillis() - t0) / 1000.0);
        float[] alr = nearField(light, land, far);
        System.out.printf("near field done (%.0f s)%n", (System.currentTimeMillis() - t0) / 1000.0);

        writeTiles(alr, land, args[3]);
        System.out.printf("written %s (%.0f s)%n", args[3], (System.currentTimeMillis() - t0) / 1000.0);
        if (args.length > 4) calibrate(alr, args[4]);
        if (args.length > 5) calibrateCsv(alr, args[5]);
        report(alr);
    }

    /**
     * Globe at Night SQM readings (CC BY 4.0, globeatnight.org): citizen measurements, noisy
     * (moonlight, twilight, cloud), but they reach into the cities the NPS parks never measure.
     * Prints the median reading per band of model ALR beside the zenith formula's prediction.
     */
    static void calibrateCsv(float[] alr, String csv) throws IOException {
        List<String> lines = Files.readAllLines(Paths.get(csv), StandardCharsets.UTF_8);
        String[] head = lines.get(0).split(",");
        int iLat = Arrays.asList(head).indexOf("Latitude"), iLon = Arrays.asList(head).indexOf("Longitude");
        int iSqm = Arrays.asList(head).indexOf("SQMReading"), iCloud = Arrays.asList(head).indexOf("CloudCover");
        double[] edges = {0, 0.03, 0.1, 0.3, 1, 3, 10, 30, 100, 1e9};
        List<List<Double>> bins = new ArrayList<>();
        for (int i = 0; i + 1 < edges.length; i++) bins.add(new ArrayList<>());
        List<double[]> pairs = new ArrayList<>();
        for (String line : lines.subList(1, lines.size())) {
            String[] f = line.split(",", -1);
            if (f.length <= Math.max(iSqm, iCloud)) continue;
            if (!"clear".equals(f[iCloud].trim())) continue;
            double lat, lon, sqm;
            try {
                lat = Double.parseDouble(f[iLat]); lon = Double.parseDouble(f[iLon]); sqm = Double.parseDouble(f[iSqm]);
            } catch (NumberFormatException e) { continue; }
            if (lat == 0 && lon == 0) continue;
            if (sqm < 15 || sqm > 22.4) continue;
            double a = at(alr, lat, lon);
            if (Double.isNaN(a)) continue;
            for (int i = 0; i + 1 < edges.length; i++) if (a >= edges[i] && a < edges[i + 1]) bins.get(i).add(sqm);
            pairs.add(new double[]{a, sqm});
        }
        System.out.printf("Globe at Night clear-sky SQM readings matched: %d%n", pairs.size());
        for (int i = 0; i + 1 < edges.length; i++) {
            List<Double> b = bins.get(i);
            if (b.isEmpty()) continue;
            b.sort(null);
            double mid = Math.sqrt(Math.max(edges[i], 0.01) * Math.min(edges[i + 1], 300));
            System.out.printf("  ALR %6.2f-%-6.2f n=%4d  median SQM %.2f (quartiles %.2f..%.2f)  formula(k=0.44) %.2f%n",
                edges[i], edges[i + 1], b.size(), b.get(b.size() / 2), b.get(b.size() / 4), b.get(3 * b.size() / 4),
                22.0 - 2.5 * Math.log10(1 + 0.44 * mid));
        }
    }

    static float[] readLights(String path) throws IOException {
        byte[] b = Files.readAllBytes(Paths.get(path));
        if (b.length != W * H * 4) throw new IllegalStateException("lights grid size " + b.length);
        float[] f = new float[W * H];
        ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().get(f);
        return f;
    }

    // ---------------------------------------------------------------------------- far field

    /** Light within 20-300 km, on the 0.1 deg grid: the value at each coarse cell centre. */
    static float[] farField(float[] light) {
        float[] coarse = new float[CW * CH];
        for (int y = 0; y < H; y++) {
            int cy = y / FACTOR;
            for (int x = 0; x < W; x++) {
                float v = light[y * W + x];
                if (v > 0) coarse[cy * CW + x / FACTOR] += v;
            }
        }
        // Rows of lit coarse cells, to skip the dark ones fast.
        int[][] litX = new int[CH][];
        for (int y = 0; y < CH; y++) {
            int n = 0;
            for (int x = 0; x < CW; x++) if (coarse[y * CW + x] > 0) n++;
            int[] xs = new int[n];
            n = 0;
            for (int x = 0; x < CW; x++) if (coarse[y * CW + x] > 0) xs[n++] = x;
            litX[y] = xs;
        }
        float[] out = new float[CW * CH];
        int ry = (int) Math.ceil(FAR_KM / (KM_PER_DEG / COARSE_PER_DEG));
        AtomicInteger done = new AtomicInteger();
        IntStream.range(0, CH).parallel().forEach(ty -> {
            double tLat = coarseLat(ty);
            double[] acc = new double[CW];
            for (int sy = Math.max(0, ty - ry); sy <= Math.min(CH - 1, ty + ry); sy++) {
                int[] xs = litX[sy];
                if (xs.length == 0) continue;
                double sLat = coarseLat(sy);
                double dyKm = (tLat - sLat) * KM_PER_DEG;
                if (Math.abs(dyKm) > FAR_KM) continue;
                double kx = Math.cos(Math.toRadians(0.5 * (tLat + sLat))) * KM_PER_DEG / COARSE_PER_DEG;
                int rx = kx > 1e-6 ? (int) Math.min(CW / 2 - 1, Math.ceil(FAR_KM / kx)) : CW / 2 - 1;
                // The weights of this source row for every column offset, once.
                double[] wrow = new double[2 * rx + 1];
                for (int dx = -rx; dx <= rx; dx++) {
                    double dxKm = dx * kx;
                    double d = Math.sqrt(dxKm * dxKm + dyKm * dyKm);
                    wrow[dx + rx] = (d > FAR_KM || d <= BLEND_FROM_KM) ? 0 : kernel(d) * blend(d);
                }
                for (int sx : xs) {
                    double l = coarse[sy * CW + sx];
                    for (int dx = -rx; dx <= rx; dx++) {
                        double wgt = wrow[dx + rx];
                        if (wgt == 0) continue;
                        int tx = sx + dx;
                        if (tx < 0) tx += CW; else if (tx >= CW) tx -= CW;
                        acc[tx] += l * wgt;
                    }
                }
            }
            for (int tx = 0; tx < CW; tx++) out[ty * CW + tx] = (float) acc[tx];
            int k = done.incrementAndGet();
            if (k % 135 == 0) System.out.printf("  far field %d/%d rows%n", k, CH);
        });
        return out;
    }

    /** Bilinear far field at a map cell's centre. */
    static double farAt(float[] far, int x, int y) {
        double fy = (y + 0.5) / FACTOR - 0.5;
        double fx = (x + 0.5) / FACTOR - 0.5;
        int y0 = (int) Math.floor(fy), x0 = (int) Math.floor(fx);
        double wy = fy - y0, wx = fx - x0;
        int y1 = Math.min(CH - 1, y0 + 1);
        y0 = Math.max(0, y0);
        int xa = Math.floorMod(x0, CW), xb = Math.floorMod(x0 + 1, CW);
        double top = far[y0 * CW + xa] * (1 - wx) + far[y0 * CW + xb] * wx;
        double bottom = far[y1 * CW + xa] * (1 - wx) + far[y1 * CW + xb] * wx;
        return top * (1 - wy) + bottom * wy;
    }

    // --------------------------------------------------------------------------- near field

    static float[] nearField(float[] light, boolean[] land, float[] far) {
        float[] alr = new float[W * H];
        // Rows of lit cells, as for the far field.
        int[][] litX = new int[H][];
        for (int y = 0; y < H; y++) {
            int n = 0;
            for (int x = 0; x < W; x++) if (light[y * W + x] > 0) n++;
            int[] xs = new int[n];
            n = 0;
            for (int x = 0; x < W; x++) if (light[y * W + x] > 0) xs[n++] = x;
            litX[y] = xs;
        }
        double cellKmY = KM_PER_DEG / CPD;
        int ry = (int) Math.ceil(NEAR_KM / cellKmY);
        AtomicInteger done = new AtomicInteger();
        IntStream.range(0, H).parallel().forEach(ty -> {
            double tLat = cellLat(ty);
            // Each target row: for every source row in reach, the lit cells within NEAR_KM.
            double[] acc = new double[W];
            for (int sy = Math.max(0, ty - ry); sy <= Math.min(H - 1, ty + ry); sy++) {
                int[] xs = litX[sy];
                if (xs.length == 0) continue;
                double sLat = cellLat(sy);
                double dyKm = (tLat - sLat) * KM_PER_DEG;
                double kx = Math.cos(Math.toRadians(0.5 * (tLat + sLat))) * KM_PER_DEG / CPD;
                int rx = (int) Math.min(W / 2 - 1, Math.ceil(NEAR_KM / Math.max(kx, 1e-6)));
                // The cell itself: its light spread over the cell, on average 0.38 of a cell away.
                double self = 0.38 * Math.sqrt(kx * cellKmY);
                double[] wrow = new double[2 * rx + 1];
                for (int dx = -rx; dx <= rx; dx++) {
                    double dxKm = dx * kx;
                    double d = Math.sqrt(dxKm * dxKm + dyKm * dyKm);
                    if (d > NEAR_KM) continue;
                    if (d < self) d = self;
                    wrow[dx + rx] = kernel(d) * (1 - blend(d));
                }
                for (int sx : xs) {
                    double l = light[sy * W + sx];
                    for (int dx = -rx; dx <= rx; dx++) {
                        double wgt = wrow[dx + rx];
                        if (wgt == 0) continue;
                        int tx = sx + dx;
                        if (tx < 0) tx += W; else if (tx >= W) tx -= W;
                        acc[tx] += l * wgt;
                    }
                }
            }
            for (int tx = 0; tx < W; tx++) {
                int i = ty * W + tx;
                alr[i] = (float) (acc[tx] + farAt(far, tx, ty));
            }
            int k = done.incrementAndGet();
            if (k % 405 == 0) System.out.printf("  near field %d/%d rows%n", k, H);
        });
        return alr;
    }

    // ------------------------------------------------------------------------- land mask

    /** Fills ([value] true) or clears the cells whose centres lie inside the shapefile's polygons. */
    static void rasterize(String zipPath, boolean[] mask, boolean value) throws IOException {
        byte[] shp = null;
        try (ZipFile z = new ZipFile(zipPath)) {
            for (ZipEntry e : java.util.Collections.list(z.entries())) {
                if (e.getName().toLowerCase().endsWith(".shp")) {
                    try (InputStream in = z.getInputStream(e)) { shp = in.readAllBytes(); }
                }
            }
        }
        if (shp == null) throw new IllegalStateException("no .shp in " + zipPath);
        ByteBuffer b = ByteBuffer.wrap(shp);
        int pos = 100;
        // Edges bucketed by the map rows they cross; filled polygon by polygon (even-odd).
        while (pos + 8 <= shp.length) {
            b.order(ByteOrder.BIG_ENDIAN);
            int lenWords = b.getInt(pos + 4);
            int rec = pos + 8;
            b.order(ByteOrder.LITTLE_ENDIAN);
            int type = b.getInt(rec);
            if (type == 5) {
                int numParts = b.getInt(rec + 36);
                int numPoints = b.getInt(rec + 40);
                int partsAt = rec + 44;
                int pointsAt = partsAt + 4 * numParts;
                double[] px = new double[numPoints], py = new double[numPoints];
                for (int i = 0; i < numPoints; i++) {
                    px[i] = b.getDouble(pointsAt + 16 * i);
                    py[i] = b.getDouble(pointsAt + 16 * i + 8);
                }
                int[] starts = new int[numParts + 1];
                for (int p = 0; p < numParts; p++) starts[p] = b.getInt(partsAt + 4 * p);
                starts[numParts] = numPoints;
                fillPolygon(px, py, starts, mask, value);
            }
            pos = rec + lenWords * 2;
        }
    }

    static void fillPolygon(double[] px, double[] py, int[] starts, boolean[] mask, boolean value) {
        // Crossings of every edge with the row centres it spans.
        java.util.Map<Integer, List<Double>> cross = new java.util.HashMap<>();
        for (int p = 0; p + 1 < starts.length; p++) {
            for (int i = starts[p]; i < starts[p + 1] - 1; i++) {
                double x1 = px[i], y1 = py[i], x2 = px[i + 1], y2 = py[i + 1];
                if (y1 == y2) continue;
                double lo = Math.min(y1, y2), hi = Math.max(y1, y2);
                // Row centres within (lo, hi]: lat = LAT_MAX - (y + 0.5)/CPD.
                int rTop = (int) Math.ceil((LAT_MAX - hi) * CPD - 0.5);
                int rBot = (int) Math.floor((LAT_MAX - lo) * CPD - 0.5);
                for (int r = Math.max(0, rTop); r <= Math.min(H - 1, rBot); r++) {
                    double lat = cellLat(r);
                    if (lat <= lo || lat > hi) continue;
                    double x = x1 + (lat - y1) * (x2 - x1) / (y2 - y1);
                    cross.computeIfAbsent(r, k -> new ArrayList<>()).add(x);
                }
            }
        }
        for (java.util.Map.Entry<Integer, List<Double>> e : cross.entrySet()) {
            int r = e.getKey();
            double[] xs = e.getValue().stream().mapToDouble(Double::doubleValue).toArray();
            Arrays.sort(xs);
            for (int k = 0; k + 1 < xs.length; k += 2) {
                // Cells whose centres lie between the two crossings.
                int c0 = (int) Math.ceil((xs[k] + 180.0) * CPD - 0.5);
                int c1 = (int) Math.floor((xs[k + 1] + 180.0) * CPD - 0.5);
                for (int c = Math.max(0, c0); c <= Math.min(W - 1, c1); c++) mask[r * W + c] = value;
            }
        }
    }

    // ---------------------------------------------------------------------------- output

    static int quantize(float alr) {
        if (!(alr > 0)) return 1;
        double q = 1 + Q_PER_DECADE * (Math.log10(alr) - Q_LOG_MIN);
        return (int) Math.max(1, Math.min(255, Math.round(q)));
    }

    /**
     * File: "SCGL", version 1, cells per degree, lat max, lat min, tile cells, tiles across,
     * tiles down, log10 ALR at value 1, values per decade; then per tile (row-major) the offset
     * and length of its deflated bytes (0 length: no land); then the tiles. A tile's bytes are
     * its cells row by row, each the difference from the cell before it (mod 256), which
     * deflate packs far better than the values themselves.
     */
    static void writeTiles(float[] alr, boolean[] land, String path) throws IOException {
        int tx = W / TILE, ty = (H + TILE - 1) / TILE;
        int[] offs = new int[tx * ty], lens = new int[tx * ty];
        ByteArrayOutputStream blobs = new ByteArrayOutputStream();
        int stored = 0;
        byte[] raw = new byte[TILE * TILE];
        byte[] buf = new byte[TILE * TILE * 2 + 1024];
        for (int j = 0; j < ty; j++) {
            for (int i = 0; i < tx; i++) {
                boolean any = false;
                int prev = 0;
                for (int r = 0; r < TILE; r++) {
                    int y = j * TILE + r;
                    for (int c = 0; c < TILE; c++) {
                        int x = i * TILE + c;
                        int v = 0;
                        if (y < H && land[y * W + x]) {
                            v = quantize(alr[y * W + x]);
                            any = true;
                        }
                        raw[r * TILE + c] = (byte) (v - prev);
                        prev = v;
                    }
                }
                if (!any) continue;
                Deflater d = new Deflater(9);
                d.setInput(raw);
                d.finish();
                int n = d.deflate(buf);
                d.end();
                offs[j * tx + i] = blobs.size();
                lens[j * tx + i] = n;
                blobs.write(buf, 0, n);
                stored++;
            }
        }
        try (DataOutputStream out = new DataOutputStream(new FileOutputStream(path))) {
            out.writeBytes("SCGL");
            out.writeInt(1);
            out.writeInt(CPD);
            out.writeFloat((float) LAT_MAX);
            out.writeFloat((float) LAT_MIN);
            out.writeInt(TILE);
            out.writeInt(tx);
            out.writeInt(ty);
            out.writeFloat((float) Q_LOG_MIN);
            out.writeFloat((float) Q_PER_DECADE);
            for (int k = 0; k < tx * ty; k++) {
                out.writeInt(offs[k]);
                out.writeInt(lens[k]);
            }
            blobs.writeTo(out);
        }
        System.out.printf("%d of %d tiles stored, %.2f MB%n", stored, tx * ty, (blobs.size() + 40 + 8.0 * tx * ty) / 1e6);
    }

    // ------------------------------------------------------------------------- checking

    static double at(float[] alr, double lat, double lon) {
        int y = (int) Math.floor((LAT_MAX - lat) * CPD);
        int x = (int) Math.floor((lon + 180.0) * CPD);
        if (y < 0 || y >= H) return Double.NaN;
        return alr[y * W + Math.floorMod(x, W)];
    }

    static void report(float[] alr) {
        String[][] pts = {
            {"Times Square", "40.758", "-73.985"}, {"Las Vegas Strip", "36.115", "-115.173"},
            {"Paris centre", "48.857", "2.352"}, {"Amsterdam", "52.373", "4.893"},
            {"Utrecht", "52.090", "5.121"}, {"Lauwersmeer DSP (NL)", "53.380", "6.220"},
            {"Boschplaat, Terschelling", "53.430", "5.430"}, {"Westhavelland DSR", "52.700", "12.300"},
            {"Galloway DSP", "55.100", "-4.400"}, {"Cherry Springs SP", "41.663", "-77.823"},
            {"Death Valley (Furnace Creek)", "36.457", "-116.866"}, {"Natural Bridges NM", "37.601", "-110.010"},
            {"Big Bend (Chisos)", "29.270", "-103.300"}, {"Paranal", "-24.627", "-70.404"},
            {"Mauna Kea", "19.820", "-155.468"}, {"Namib (NamibRand)", "-25.000", "16.000"},
        };
        System.out.println("model ALR at known places:");
        for (String[] p : pts) {
            double a = at(alr, Double.parseDouble(p[1]), Double.parseDouble(p[2]));
            System.out.printf("  %-30s ALR %8.3f%n", p[0], a);
        }
    }

    /** The model against the NPS measurements: measured all-sky ALR and zenith brightness. */
    static void calibrate(float[] alr, String geojson) throws IOException {
        String t = new String(Files.readAllBytes(Paths.get(geojson)), StandardCharsets.UTF_8);
        Pattern feat = Pattern.compile("\"coordinates\":\\s*\\[\\s*(-?[0-9.]+),\\s*(-?[0-9.]+)\\s*\\][\\s\\S]*?\"SITE_NAME\":\\s*\"([^\"]*)\"[\\s\\S]*?\"ZENITH_LUM_MSA\":\\s*\"?([^,\"]*)\"?,[\\s\\S]*?\"ALR_POS\":\\s*\"([^\"]*)\"");
        Matcher m = feat.matcher(t);
        List<double[]> rows = new ArrayList<>();
        while (m.find()) {
            double lon = Double.parseDouble(m.group(1)), lat = Double.parseDouble(m.group(2));
            double zen = parse(m.group(4)), alrMeas = parse(m.group(5));
            double model = at(alr, lat, lon);
            if (Double.isNaN(model)) continue;
            rows.add(new double[]{model, alrMeas, zen});
        }
        System.out.printf("NPS nights matched: %d%n", rows.size());
        // log10 ratio model/measured, where both are measurable.
        List<Double> logRatio = new ArrayList<>();
        for (double[] r : rows) if (r[1] > 0.05 && r[0] > 0) logRatio.add(Math.log10(r[0] / r[1]));
        logRatio.sort(null);
        if (!logRatio.isEmpty()) {
            double med = logRatio.get(logRatio.size() / 2);
            double q1 = logRatio.get(logRatio.size() / 4), q3 = logRatio.get(3 * logRatio.size() / 4);
            System.out.printf("  model/measured ALR: median x%.2f, quartiles x%.2f .. x%.2f (n=%d)%n",
                Math.pow(10, med), Math.pow(10, q1), Math.pow(10, q3), logRatio.size());
        }
        // Zenith brightness: m = m0 - 2.5 log10(1 + k ALR), fitted for k with m0 = 22.0.
        double bestK = 0, bestErr = Double.MAX_VALUE;
        for (double k = 0.02; k < 2.0; k *= 1.02) {
            double err = 0;
            int n = 0;
            for (double[] r : rows) {
                if (Double.isNaN(r[2]) || r[2] < 15 || r[2] > 22.5) continue;
                double pred = 22.0 - 2.5 * Math.log10(1 + k * r[0]);
                err += (pred - r[2]) * (pred - r[2]);
                n++;
            }
            if (n > 0 && err / n < bestErr) { bestErr = err / n; bestK = k; }
        }
        System.out.printf("  zenith mag = 22.0 - 2.5 log10(1 + %.3f ALR): rms %.2f mag%n", bestK, Math.sqrt(bestErr));
        // The same with the measured ALR, to see how much of the scatter is the model's.
        double bestKm = 0, bestErrM = Double.MAX_VALUE;
        for (double k = 0.02; k < 2.0; k *= 1.02) {
            double err = 0;
            int n = 0;
            for (double[] r : rows) {
                if (Double.isNaN(r[2]) || r[2] < 15 || r[2] > 22.5 || Double.isNaN(r[1])) continue;
                double pred = 22.0 - 2.5 * Math.log10(1 + k * r[1]);
                err += (pred - r[2]) * (pred - r[2]);
                n++;
            }
            if (n > 0 && err / n < bestErrM) { bestErrM = err / n; bestKm = k; }
        }
        System.out.printf("  (with the measured ALR: k %.3f, rms %.2f mag)%n", bestKm, Math.sqrt(bestErrM));
    }

    /** "0.36", "< 0.01", "> 24.5": a number, the bound standing in for the value. */
    static double parse(String s) {
        String v = s.replace("<", "").replace(">", "").trim();
        try { return Double.parseDouble(v); } catch (NumberFormatException e) { return Double.NaN; }
    }
}
