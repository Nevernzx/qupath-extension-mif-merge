package qupath.ext.mifmerge.qc;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.imageio.ImageIO;
import java.awt.geom.AffineTransform;
import java.awt.image.AffineTransformOp;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferByte;
import java.io.File;
import java.io.IOException;
import java.nio.file.Path;

/**
 * Port of the {@code save_qc_viz()} routines from register_batch.py.
 *
 * <p>Given two 8-bit gray images (fixed + moving) and the affine that maps
 * moving-pixel coordinates into fixed-pixel coordinates at this resolution,
 * write three QC PNGs into {@code outDir}:
 * <ul>
 *   <li>{@code qc_checkerboard.png} — 80-px alternating tiles of fixed vs
 *       warped(moving). Misalignment shows as discontinuities at tile
 *       boundaries.</li>
 *   <li>{@code qc_abs_diff.png} — |fixed − warped|, scaled 4x. Bright pixels
 *       mark badly aligned tissue.</li>
 *   <li>{@code qc_overlay.png} — magenta (fixed) + green (warped) channels;
 *       white = perfect agreement.</li>
 * </ul>
 *
 * <p>The error-keypoint visualisations (qc_matches / qc_error_histogram /
 * qc_error_heatmap / qc_residual_quiver) from the Python prototype are
 * deferred — they would need {@link qupath.ext.mifmerge.core.SiftRansacAffine}
 * to optionally retain inlier coordinates, which is a separate change.
 */
public final class QcVisualizer {

    private static final Logger logger = LoggerFactory.getLogger(QcVisualizer.class);
    private static final int CHECKER_TILE = 80;

    private QcVisualizer() {}

    /**
     * @param fixed  8-bit gray, shape ({@code H}, {@code W})
     * @param moving 8-bit gray (any shape)
     * @param affine maps moving pixel coords into fixed pixel coords at the
     *               SAME resolution as {@code fixed} and {@code moving}
     */
    public static void write(BufferedImage fixed, BufferedImage moving,
                             AffineTransform affine, Path outDir) throws IOException {
        write(fixed, moving, affine, outDir, "");
    }

    /**
     * Same as {@link #write(BufferedImage, BufferedImage, AffineTransform, Path)}
     * but each filename is prefixed with {@code prefix} (so multiple pairs can
     * share an output directory).
     */
    public static void write(BufferedImage fixed, BufferedImage moving,
                             AffineTransform affine, Path outDir,
                             String prefix) throws IOException {
        outDir.toFile().mkdirs();

        // Warp moving into fixed frame at fixed resolution
        BufferedImage warped = new BufferedImage(
                fixed.getWidth(), fixed.getHeight(), BufferedImage.TYPE_BYTE_GRAY);
        AffineTransformOp op = new AffineTransformOp(affine, AffineTransformOp.TYPE_BILINEAR);
        op.filter(moving, warped);

        String p = prefix == null ? "" : prefix;
        write(outDir.resolve(p + "qc_checkerboard.png").toFile(),
                checkerboard(fixed, warped));
        write(outDir.resolve(p + "qc_abs_diff.png").toFile(),
                absDiff(fixed, warped, 4.0));
        write(outDir.resolve(p + "qc_overlay.png").toFile(),
                magentaGreenOverlay(fixed, warped));

        logger.info("Wrote QC: {}{qc_checkerboard,qc_abs_diff,qc_overlay}.png", outDir.resolve(p));
    }

    static BufferedImage checkerboard(BufferedImage fixed, BufferedImage warped) {
        int w = fixed.getWidth();
        int h = fixed.getHeight();
        byte[] f = ((DataBufferByte) fixed.getRaster().getDataBuffer()).getData();
        byte[] m = ((DataBufferByte) warped.getRaster().getDataBuffer()).getData();
        BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_BYTE_GRAY);
        byte[] o = ((DataBufferByte) out.getRaster().getDataBuffer()).getData();
        for (int y = 0; y < h; y++) {
            int yTile = y / CHECKER_TILE;
            for (int x = 0; x < w; x++) {
                int xTile = x / CHECKER_TILE;
                int idx = y * w + x;
                o[idx] = ((xTile + yTile) & 1) == 0 ? f[idx] : m[idx];
            }
        }
        return out;
    }

    static BufferedImage absDiff(BufferedImage fixed, BufferedImage warped, double gain) {
        int w = fixed.getWidth();
        int h = fixed.getHeight();
        byte[] f = ((DataBufferByte) fixed.getRaster().getDataBuffer()).getData();
        byte[] m = ((DataBufferByte) warped.getRaster().getDataBuffer()).getData();
        BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_BYTE_GRAY);
        byte[] o = ((DataBufferByte) out.getRaster().getDataBuffer()).getData();
        for (int i = 0; i < o.length; i++) {
            int diff = Math.abs((f[i] & 0xFF) - (m[i] & 0xFF));
            int scaled = (int) Math.round(diff * gain);
            if (scaled > 255) scaled = 255;
            o[i] = (byte) scaled;
        }
        return out;
    }

    static BufferedImage magentaGreenOverlay(BufferedImage fixed, BufferedImage warped) {
        int w = fixed.getWidth();
        int h = fixed.getHeight();
        byte[] f = ((DataBufferByte) fixed.getRaster().getDataBuffer()).getData();
        byte[] m = ((DataBufferByte) warped.getRaster().getDataBuffer()).getData();
        BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_3BYTE_BGR);
        byte[] o = ((DataBufferByte) out.getRaster().getDataBuffer()).getData();
        // TYPE_3BYTE_BGR stores [B, G, R] per pixel
        for (int i = 0, j = 0; i < f.length; i++, j += 3) {
            int vf = f[i] & 0xFF;
            int vm = m[i] & 0xFF;
            o[j]     = (byte) vf;  // B  = fixed
            o[j + 1] = (byte) vm;  // G  = warped
            o[j + 2] = (byte) vf;  // R  = fixed → magenta + green = white where they agree
        }
        return out;
    }

    private static void write(File file, BufferedImage img) throws IOException {
        ImageIO.write(img, "PNG", file);
    }
}
