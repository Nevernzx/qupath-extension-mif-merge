package qupath.ext.mifmerge.verify;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.ext.mifmerge.core.RegistrationResult;
import qupath.ext.mifmerge.core.SiftRansacAffine;
import qupath.ext.mifmerge.io.NumpyReader;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.file.Path;
import java.util.Locale;

/**
 * Loads the {@code dapi_fixed.png} / {@code dapi_moving.png} pair produced by
 * register_batch.py at thumbnail resolution, runs the Java port of stage-1
 * SIFT+RANSAC, and prints a side-by-side comparison against the Python
 * golden matrix in {@code matrix_thumbnail_initial_sift_l4.npy}.
 *
 * <p>The Java port uses OpenCV SIFT, whereas the Python stage-1 used
 * scikit-image SIFT — so exact bit-for-bit agreement is not expected.
 * "Close" means translation agrees within ~1 px, rotation within ~0.01 deg,
 * scale within ~0.001.
 *
 * <p>Run with:
 * <pre>{@code
 *   ./gradlew run --args="/path/to/registrations_batch/<case>"
 * }</pre>
 * or via {@code java -cp <fat-jar> qupath.ext.mifmerge.verify.VerifyAgainstPython <case-dir>}.
 */
public final class VerifyAgainstPython {

    private static final Logger logger = LoggerFactory.getLogger(VerifyAgainstPython.class);

    public static void main(String[] args) throws Exception {
        if (args.length == 0) {
            System.err.println("Usage: VerifyAgainstPython <case-dir>");
            System.err.println("  e.g. /home/25_niezhengxin/workplace/CLAM_pre/registrations_batch/1129773_结肠T1");
            System.exit(2);
        }
        Path caseDir = Path.of(args[0]).toAbsolutePath();
        BufferedImage fixed = ImageIO.read(new File(caseDir.resolve("dapi_fixed.png").toString()));
        BufferedImage moving = ImageIO.read(new File(caseDir.resolve("dapi_moving.png").toString()));
        if (fixed == null || moving == null) {
            throw new IllegalStateException("Failed to read DAPI PNGs from " + caseDir);
        }
        logger.info("Loaded fixed {}x{}, moving {}x{}",
                fixed.getWidth(), fixed.getHeight(),
                moving.getWidth(), moving.getHeight());

        SiftRansacAffine.Params params = new SiftRansacAffine.Params()
                .nFeatures(60_000)
                .loweRatio(0.75)
                .ransacReprojThresholdPx(3.0)
                .ransacMaxIters(5000);

        long t0 = System.currentTimeMillis();
        RegistrationResult result = SiftRansacAffine.run(fixed, moving, params);
        long elapsed = System.currentTimeMillis() - t0;
        logger.info("Java pipeline finished in {} ms", elapsed);
        logger.info("Result: {}", result);

        // Compare to Python golden matrices: prefer matrix_thumbnail_initial_sift_l4.npy
        // (skimage SIFT on the same PNGs); fall back to matrix_thumbnail.npy (which is
        // the L2-refined-then-rescaled matrix).
        Path goldenL4 = caseDir.resolve("matrix_thumbnail_initial_sift_l4.npy");
        Path goldenFinal = caseDir.resolve("matrix_thumbnail.npy");
        if (goldenL4.toFile().exists()) {
            compare("Python L4 stage (skimage SIFT)", result.matrix,
                    NumpyReader.readFloat64Matrix(goldenL4).data);
        }
        if (goldenFinal.toFile().exists()) {
            compare("Python final thumb (L2 cv2 SIFT, rescaled)", result.matrix,
                    NumpyReader.readFloat64Matrix(goldenFinal).data);
        }
    }

    private static void compare(String label, double[][] java, double[][] python) {
        System.out.println();
        System.out.println("==== " + label + " ====");
        printMatrix("Java   ", java);
        printMatrix("Python ", python);
        double[][] diff = new double[3][3];
        double maxAbs = 0;
        for (int i = 0; i < 3; i++) {
            for (int j = 0; j < 3; j++) {
                diff[i][j] = java[i][j] - python[i][j];
                maxAbs = Math.max(maxAbs, Math.abs(diff[i][j]));
            }
        }
        printMatrix("Diff   ", diff);
        double[] dJava = decompose(java);
        double[] dPy = decompose(python);
        System.out.printf(Locale.ROOT,
                "Affine decomposition (Java vs Python):%n"
                        + "  rotation deg : %+.4f vs %+.4f  (delta %+.4f)%n"
                        + "  scale x      : %.5f vs %.5f  (delta %+.5f)%n"
                        + "  scale y      : %.5f vs %.5f  (delta %+.5f)%n"
                        + "  tx           : %+.2f vs %+.2f  (delta %+.2f)%n"
                        + "  ty           : %+.2f vs %+.2f  (delta %+.2f)%n"
                        + "  max |diff|   : %.4g%n",
                dJava[0], dPy[0], dJava[0] - dPy[0],
                dJava[1], dPy[1], dJava[1] - dPy[1],
                dJava[2], dPy[2], dJava[2] - dPy[2],
                dJava[3], dPy[3], dJava[3] - dPy[3],
                dJava[4], dPy[4], dJava[4] - dPy[4],
                maxAbs);
    }

    private static void printMatrix(String label, double[][] m) {
        for (int i = 0; i < 3; i++) {
            System.out.printf(Locale.ROOT, "%s  [%14.6e %14.6e %14.6e]%n",
                    i == 0 ? label : " ".repeat(label.length()),
                    m[i][0], m[i][1], m[i][2]);
        }
    }

    /** Returns [rot_deg, scale_x, scale_y, tx, ty]. */
    private static double[] decompose(double[][] m) {
        double a = m[0][0], b = m[0][1], c = m[1][0], d = m[1][1];
        double rot = Math.toDegrees(Math.atan2(c, a));
        double sx = Math.hypot(a, c);
        double sy = Math.hypot(b, d);
        return new double[] { rot, sx, sy, m[0][2], m[1][2] };
    }
}
