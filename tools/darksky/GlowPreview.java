import java.awt.image.BufferedImage;
import java.io.DataInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.zip.Inflater;
import javax.imageio.ImageIO;

/**
 * Renders a region of glow.bin (Skyglow.java) to a PNG in the app's colours, reading the file
 * the way the app does - a check of the format and of the map itself.
 *
 *   java tools\darksky\GlowPreview.java <glow.bin> <out.png> <latMin> <latMax> <lonMin> <lonMax>
 */
public class GlowPreview {
    public static void main(String[] a) throws Exception {
        byte[] file = Files.readAllBytes(Paths.get(a[0]));
        DataInputStream in = new DataInputStream(new java.io.ByteArrayInputStream(file));
        byte[] magic = new byte[4];
        in.readFully(magic);
        if (!new String(magic, "US-ASCII").equals("SCGL")) throw new IOException("not a glow file");
        int version = in.readInt();
        int cpd = in.readInt();
        float latMax = in.readFloat(), latMin = in.readFloat();
        int tile = in.readInt(), tx = in.readInt(), ty = in.readInt();
        float qLogMin = in.readFloat(), qPerDecade = in.readFloat();
        int[] offs = new int[tx * ty], lens = new int[tx * ty];
        for (int k = 0; k < tx * ty; k++) { offs[k] = in.readInt(); lens[k] = in.readInt(); }
        int dataStart = 4 + 4 * 9 + 8 * tx * ty;
        double la0 = Double.parseDouble(a[2]), la1 = Double.parseDouble(a[3]);
        double lo0 = Double.parseDouble(a[4]), lo1 = Double.parseDouble(a[5]);
        int y0 = (int) Math.floor((latMax - la1) * cpd), y1 = (int) Math.ceil((latMax - la0) * cpd);
        int x0 = (int) Math.floor((lo0 + 180) * cpd), x1 = (int) Math.ceil((lo1 + 180) * cpd);
        BufferedImage img = new BufferedImage(x1 - x0, y1 - y0, BufferedImage.TYPE_INT_RGB);
        byte[][] cache = new byte[tx * ty][];
        for (int y = y0; y < y1; y++) {
            for (int x = x0; x < x1; x++) {
                int ti = (y / tile) * tx + (x / tile);
                int v = 0;
                if (lens[ti] > 0) {
                    if (cache[ti] == null) cache[ti] = inflateTile(file, dataStart + offs[ti], lens[ti], tile);
                    v = cache[ti][(y % tile) * tile + (x % tile)] & 0xff;
                }
                img.setRGB(x - x0, y - y0, colour(v, qLogMin, qPerDecade));
            }
        }
        ImageIO.write(img, "png", new File(a[1]));
        System.out.printf("%dx%d px written (version %d)%n", x1 - x0, y1 - y0, version);
    }

    static byte[] inflateTile(byte[] file, int off, int len, int tile) throws Exception {
        Inflater inf = new Inflater();
        inf.setInput(file, off, len);
        byte[] d = new byte[tile * tile];
        int n = inf.inflate(d);
        inf.end();
        if (n != d.length) throw new IOException("short tile");
        // Undo the running differences.
        int prev = 0;
        for (int i = 0; i < d.length; i++) {
            prev = (prev + d[i]) & 0xff;
            d[i] = (byte) prev;
        }
        return d;
    }

    /** Water dark blue; land by Bortle class from the estimated zenith brightness. */
    static int colour(int v, float qLogMin, float qPerDecade) {
        if (v == 0) return 0x0B1B33;
        double alr = Math.pow(10, (v - 1) / qPerDecade + qLogMin);
        double sqm = 22.0 - 2.5 * Math.log10(1 + 0.5 * alr);
        int[] ramp = {0x101010, 0x2A2A40, 0x24366B, 0x1F6F4A, 0x6E9F2C, 0xD8C83A, 0xE58A2E, 0xD9412B, 0xF2F2F2};
        double[] lim = {21.99, 21.89, 21.69, 20.49, 19.50, 18.94, 18.38, 17.80};
        int cls = 8;
        for (int i = 0; i < lim.length; i++) if (sqm >= lim[i]) { cls = i; break; }
        return ramp[cls];
    }
}
