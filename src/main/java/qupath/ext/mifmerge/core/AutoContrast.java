package qupath.ext.mifmerge.core;

import java.awt.image.BufferedImage;
import java.awt.image.DataBufferByte;

/**
 * Simple per-image autocontrast that maps the [p_lo, p_hi] percentile range
 * of a 1- or 2-channel raw array into 0..255. Mirrors the
 * {@code autocontrast()} helper in register_batch.py so the input to SIFT
 * has the same dynamic range characteristics on both sides.
 */
public final class AutoContrast {

    private AutoContrast() {}

    /**
     * Stretch {@code data} into an 8-bit {@link BufferedImage} of size
     * {@code width x height}.
     */
    public static BufferedImage stretchToByteGray(short[] data, int width, int height,
                                                  double pLo, double pHi) {
        double[] range = percentileRangeUnsigned(data, pLo, pHi);
        double lo = range[0];
        double hi = range[1];
        if (hi <= lo) hi = lo + 1;
        double scale = 255.0 / (hi - lo);

        BufferedImage img = new BufferedImage(width, height, BufferedImage.TYPE_BYTE_GRAY);
        byte[] out = ((DataBufferByte) img.getRaster().getDataBuffer()).getData();
        for (int i = 0; i < data.length; i++) {
            int v = data[i] & 0xFFFF;     // qptiff uint16
            int b = (int) Math.round((v - lo) * scale);
            if (b < 0) b = 0;
            else if (b > 255) b = 255;
            out[i] = (byte) b;
        }
        return img;
    }

    /** Same but for byte input (uint8 channel). */
    public static BufferedImage stretchToByteGray(byte[] data, int width, int height,
                                                  double pLo, double pHi) {
        double[] range = percentileRangeUnsigned(data, pLo, pHi);
        double lo = range[0];
        double hi = range[1];
        if (hi <= lo) hi = lo + 1;
        double scale = 255.0 / (hi - lo);

        BufferedImage img = new BufferedImage(width, height, BufferedImage.TYPE_BYTE_GRAY);
        byte[] out = ((DataBufferByte) img.getRaster().getDataBuffer()).getData();
        for (int i = 0; i < data.length; i++) {
            int v = data[i] & 0xFF;
            int b = (int) Math.round((v - lo) * scale);
            if (b < 0) b = 0;
            else if (b > 255) b = 255;
            out[i] = (byte) b;
        }
        return img;
    }

    /**
     * Compute the lo/hi percentile values via a histogram (O(N + bins)).
     * Treats values as unsigned (0..65535 for short[], 0..255 for byte[]).
     */
    private static double[] percentileRangeUnsigned(short[] data, double pLo, double pHi) {
        int[] hist = new int[65536];
        for (short s : data) hist[s & 0xFFFF]++;
        return histPercentiles(hist, data.length, pLo, pHi);
    }

    private static double[] percentileRangeUnsigned(byte[] data, double pLo, double pHi) {
        int[] hist = new int[256];
        for (byte b : data) hist[b & 0xFF]++;
        return histPercentiles(hist, data.length, pLo, pHi);
    }

    private static double[] histPercentiles(int[] hist, int total, double pLo, double pHi) {
        long thrLo = (long) Math.floor(pLo / 100.0 * total);
        long thrHi = (long) Math.floor(pHi / 100.0 * total);
        long c = 0;
        int lo = 0;
        int hi = hist.length - 1;
        for (int i = 0; i < hist.length; i++) {
            c += hist[i];
            if (c >= thrLo) { lo = i; break; }
        }
        c = 0;
        for (int i = 0; i < hist.length; i++) {
            c += hist[i];
            if (c >= thrHi) { hi = i; break; }
        }
        return new double[] {lo, hi};
    }
}
