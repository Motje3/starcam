import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Step 1 of the dark-sky map (see Skyglow.java): the world's artificial light, summed into the
 * map's grid.
 *
 * Input: "The global NPP-VIIRS-like nighttime light data (Version 2)" for 2025, Chen, Yu et al.,
 * Harvard Dataverse doi:10.7910/DVN/YGIVCD, CC0 1.0 (2025_Version2.zip, 101.7 MB; inputs from
 * the Earth Observation Group, Colorado School of Mines). A zipped BigTIFF: float32 radiance in
 * nW/cm2/sr on a 0.0044916 deg grid (about 500 m), uncompressed 128x128 tiles, which javax.imageio
 * cannot read, so it is streamed tile by tile straight out of the zip.
 *
 * Output: little-endian float32 "light" per map cell - radiance times the pixel's area in km2,
 * summed over the pixels whose centres fall in the cell - for latitudes [LAT_MIN, LAT_MAX) at
 * 1/CELLS_PER_DEG deg, west to east from -180, north to south.
 *
 *   java -Xmx4g tools\darksky\Lights.java <2025_Version2.zip> <lights.f32>
 */
public class Lights {
    static final int CELLS_PER_DEG = 30;
    static final double LAT_MAX = 75.0;
    static final double LAT_MIN = -60.0;

    static int readFully(InputStream in, byte[] b, int off, int len) throws IOException {
        int n = 0;
        while (n < len) {
            int k = in.read(b, off + n, len - n);
            if (k < 0) break;
            n += k;
        }
        return n;
    }

    static void skipFully(InputStream in, long n) throws IOException {
        while (n > 0) {
            long k = in.skip(n);
            if (k <= 0) throw new EOFException();
            n -= k;
        }
    }

    public static void main(String[] args) throws Exception {
        ZipInputStream zis = new ZipInputStream(new BufferedInputStream(new FileInputStream(args[0]), 1 << 22));
        ZipEntry e;
        while ((e = zis.getNextEntry()) != null && !e.getName().toLowerCase().endsWith(".tif")) { }
        if (e == null) throw new IllegalStateException("no .tif in the zip");
        InputStream in = zis;

        // The BigTIFF header and the first IFD (a copy of it sits right after the header).
        byte[] head = new byte[1 << 16];
        readFully(in, head, 0, head.length);
        ByteBuffer h = ByteBuffer.wrap(head).order(ByteOrder.LITTLE_ENDIAN);
        if (h.getShort(2) != 43) throw new IllegalStateException("not a BigTIFF");
        int ifd = 16;
        long n = h.getLong(ifd);
        int w = 0, ht = 0, tw = 0, th = 0;
        long tileOffsets = 0, tileCount = 0, scaleAt = 0, tieAt = 0;
        for (int i = 0; i < n; i++) {
            int p = ifd + 8 + i * 20;
            int tag = h.getShort(p) & 0xffff;
            int type = h.getShort(p + 2) & 0xffff;
            long count = h.getLong(p + 4);
            long v = type == 3 ? (h.getShort(p + 12) & 0xffff) : type == 4 ? (h.getInt(p + 12) & 0xffffffffL) : h.getLong(p + 12);
            switch (tag) {
                case 256: w = (int) v; break;
                case 257: ht = (int) v; break;
                case 322: tw = (int) v; break;
                case 323: th = (int) v; break;
                case 324: tileOffsets = v; tileCount = count; break;
                case 33550: scaleAt = v; break;
                case 33922: tieAt = v; break;
                default: break;
            }
        }
        long arraysEnd = Math.max(tileOffsets + tileCount * 8, Math.max(scaleAt + 24, tieAt + 48));
        byte[] all = new byte[(int) arraysEnd];
        System.arraycopy(head, 0, all, 0, head.length);
        readFully(in, all, head.length, all.length - head.length);
        ByteBuffer a = ByteBuffer.wrap(all).order(ByteOrder.LITTLE_ENDIAN);
        double sx = a.getDouble((int) scaleAt);
        double sy = a.getDouble((int) scaleAt + 8);
        double lon0 = a.getDouble((int) tieAt + 24);
        double lat0 = a.getDouble((int) tieAt + 32);
        long firstTile = a.getLong((int) tileOffsets);
        System.out.printf("source %dx%d px, tiles %dx%d, cell %.10f deg, origin %.6f %.6f%n", w, ht, tw, th, sx, lon0, lat0);
        skipFully(in, firstTile - arraysEnd);

        int gw = 360 * CELLS_PER_DEG;
        int gh = (int) Math.round((LAT_MAX - LAT_MIN) * CELLS_PER_DEG);
        float[] grid = new float[gw * gh];
        // A source pixel's area in km2 at each source row (cos(lat) shrinks it towards the poles).
        double kmPerDeg = 111.32;
        double pxKm2 = (sx * kmPerDeg) * (sy * kmPerDeg);
        int tilesAcross = (w + tw - 1) / tw;
        int tilesDown = (ht + th - 1) / th;
        byte[] tile = new byte[tw * th * 4];
        double total = 0;
        long t0 = System.currentTimeMillis();
        for (int tr = 0; tr < tilesDown; tr++) {
            for (int tc = 0; tc < tilesAcross; tc++) {
                if (readFully(in, tile, 0, tile.length) != tile.length) throw new EOFException("tile " + tr + "," + tc);
                FloatBuffer fb = ByteBuffer.wrap(tile).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer();
                for (int r = 0; r < th; r++) {
                    int y = tr * th + r;
                    if (y >= ht) break;
                    double lat = lat0 - (y + 0.5) * sy;
                    if (lat >= LAT_MAX || lat < LAT_MIN) continue;
                    int gy = (int) Math.floor((LAT_MAX - lat) * CELLS_PER_DEG);
                    double area = pxKm2 * Math.cos(Math.toRadians(lat));
                    int rowBase = gy * gw;
                    for (int c = 0; c < tw; c++) {
                        int x = tc * tw + c;
                        if (x >= w) break;
                        float v = fb.get(r * tw + c);
                        if (!(v > 0f)) continue;  // 0 (dark or water), and the NoData value
                        double lon = lon0 + (x + 0.5) * sx;
                        int gx = (int) Math.floor((lon + 180.0) * CELLS_PER_DEG);
                        if (gx >= gw) gx -= gw;
                        if (gx < 0) gx += gw;
                        double l = v * area;
                        grid[rowBase + gx] += (float) l;
                        total += l;
                    }
                }
            }
            if (tr % 50 == 0) System.out.printf("  row of tiles %d/%d, %.0f s%n", tr, tilesDown, (System.currentTimeMillis() - t0) / 1000.0);
        }
        long lit = 0;
        for (float v : grid) if (v > 0) lit++;
        System.out.printf("grid %dx%d (lat %.0f..%.0f), %d lit cells, total light %.4e%n", gw, gh, LAT_MAX, LAT_MIN, lit, total);
        try (DataOutputStream out = new DataOutputStream(new BufferedOutputStream(new FileOutputStream(args[1]), 1 << 20))) {
            ByteBuffer b = ByteBuffer.allocate(gw * 4).order(ByteOrder.LITTLE_ENDIAN);
            for (int y = 0; y < gh; y++) {
                b.clear();
                for (int x = 0; x < gw; x++) b.putFloat(grid[y * gw + x]);
                out.write(b.array());
            }
        }
        System.out.println("written " + args[1]);
    }
}
