package qupath.ext.mifmerge.verify;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.ext.mifmerge.core.MatrixRescaler;
import qupath.ext.mifmerge.core.MifImageSource;
import qupath.ext.mifmerge.core.RegistrationOrchestrator;
import qupath.ext.mifmerge.io.BioFormatsMifSource;
import qupath.ext.mifmerge.qc.QcVisualizer;

import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

/**
 * Standalone CLI: register a moving qptiff to a fixed qptiff, save the
 * resulting full-resolution affine matrix as JSON and a human-readable .txt.
 *
 * <p>Mirrors {@code register_pair()} in register_batch.py but only writes the
 * matrix, not the QC visualisations (those will be added in the QC milestone).
 *
 * <pre>{@code
 *   ./gradlew runRegister \
 *     -PfixedPath=/data/wsi/Merge_Test_Qptiff/<case>-collagen(...).qptiff \
 *     -PmovingPath=/data/wsi/Merge_Test_Qptiff/<case>-CD138(...).qptiff \
 *     -PoutDir=/tmp/mif-merge-out/<case>
 * }</pre>
 */
public final class RegisterCli {

    private static final Logger logger = LoggerFactory.getLogger(RegisterCli.class);

    public static void main(String[] args) throws Exception {
        if (args.length < 3) {
            System.err.println("Usage: RegisterCli <fixed.qptiff> <moving.qptiff> <out-dir>");
            System.exit(2);
        }
        File fixedFile = new File(args[0]);
        File movingFile = new File(args[1]);
        Path outDir = Path.of(args[2]);
        Files.createDirectories(outDir);

        logger.info("Fixed : {}", fixedFile);
        logger.info("Moving: {}", movingFile);
        logger.info("OutDir: {}", outDir);

        try (MifImageSource fixed = new BioFormatsMifSource(fixedFile);
             MifImageSource moving = new BioFormatsMifSource(movingFile)) {

            RegistrationOrchestrator.Config cfg = new RegistrationOrchestrator.Config();
            long t0 = System.currentTimeMillis();
            RegistrationOrchestrator.Result r = RegistrationOrchestrator.run(fixed, moving, cfg);
            long elapsed = System.currentTimeMillis() - t0;
            logger.info("Pipeline finished in {} ms", elapsed);

            writeMatrixJson(outDir.resolve("matrix_full_res.json"), r);
            writeMatrixTxt(outDir.resolve("matrices.txt"), r, fixedFile, movingFile, elapsed);
            logger.info("Wrote: {}", outDir.resolve("matrix_full_res.json"));
            logger.info("Wrote: {}", outDir.resolve("matrices.txt"));

            writeQc(fixed, moving, r, outDir);
        }
    }

    /**
     * Re-read the stage-1 (thumbnail) DAPI on each side and dump
     * checkerboard / abs-diff / overlay PNGs using the stage-1 affine.
     */
    private static void writeQc(MifImageSource fixed, MifImageSource moving,
                                RegistrationOrchestrator.Result r, Path outDir) throws IOException {
        BufferedImage fixS1 = fixed.readChannelAtLevel(r.fixedDapiChannel, r.stage1Levels.levelFixed);
        BufferedImage movS1 = moving.readChannelAtLevel(r.movingDapiChannel, r.stage1Levels.levelMoving);
        AffineTransform affS1 = MatrixRescaler.toAffineTransform(r.stages.stage1.matrix);
        QcVisualizer.write(fixS1, movS1, affS1, outDir);
    }

    private static void writeMatrixJson(Path path, RegistrationOrchestrator.Result r) throws IOException {
        StringBuilder sb = new StringBuilder();
        sb.append("{\n");
        sb.append("  \"matrix_full_res\": [\n");
        for (int i = 0; i < 3; i++) {
            sb.append("    [");
            for (int j = 0; j < 3; j++) {
                sb.append(String.format(Locale.ROOT, "%.10e", r.matrixFullRes[i][j]));
                if (j < 2) sb.append(", ");
            }
            sb.append(i < 2 ? "],\n" : "]\n");
        }
        sb.append("  ],\n");
        sb.append("  \"matrix_stage2\": ").append(matrixJson(r.stages.matrixAtStage2Resolution)).append(",\n");
        sb.append("  \"matrix_stage1\": ").append(matrixJson(r.stages.stage1.matrix)).append(",\n");
        sb.append("  \"stage1\": ").append(statsJson(r.stages.stage1)).append(",\n");
        sb.append("  \"stage2\": ").append(statsJson(r.stages.stage2)).append(",\n");
        sb.append("  \"fixed_dapi_channel\": ").append(r.fixedDapiChannel).append(",\n");
        sb.append("  \"moving_dapi_channel\": ").append(r.movingDapiChannel).append(",\n");
        sb.append("  \"stage1_levels\": ").append(selectionJson(r.stage1Levels)).append(",\n");
        sb.append("  \"stage2_levels\": ").append(selectionJson(r.stage2Levels)).append("\n");
        sb.append("}\n");
        Files.writeString(path, sb.toString());
    }

    private static String matrixJson(double[][] m) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < 3; i++) {
            sb.append("[");
            for (int j = 0; j < 3; j++) {
                sb.append(String.format(Locale.ROOT, "%.10e", m[i][j]));
                if (j < 2) sb.append(", ");
            }
            sb.append(i < 2 ? "], " : "]");
        }
        sb.append("]");
        return sb.toString();
    }

    private static String statsJson(qupath.ext.mifmerge.core.RegistrationResult s) {
        return String.format(Locale.ROOT,
                "{\"n_kp_f\": %d, \"n_kp_m\": %d, \"n_lowe\": %d, \"n_prefilter\": %d, "
                        + "\"n_inliers\": %d, \"inlier_ratio\": %.6f, "
                        + "\"reproj_median_px\": %.4f, \"reproj_p95_px\": %.4f, \"reproj_max_px\": %.4f, "
                        + "\"t_detect_ms\": %d, \"t_match_ms\": %d, \"t_ransac_ms\": %d}",
                s.nKeypointsFixed, s.nKeypointsMoving,
                s.nMatchesPostLowe, s.nMatchesPostPrefilter,
                s.nInliers, s.inlierRatio,
                s.medianReprojErrPx, s.p95ReprojErrPx, s.maxReprojErrPx,
                s.tDetectMillis, s.tMatchMillis, s.tRansacMillis);
    }

    private static String selectionJson(qupath.ext.mifmerge.core.DapiPyramidSelector.Selection s) {
        return String.format(Locale.ROOT,
                "{\"fixed_level\": %d, \"fixed_downsample\": %.4f, "
                        + "\"moving_level\": %d, \"moving_downsample\": %.4f, "
                        + "\"mismatch_ratio\": %.4f}",
                s.levelFixed, s.downsampleFixed,
                s.levelMoving, s.downsampleMoving,
                s.mismatchRatio);
    }

    private static void writeMatrixTxt(Path path, RegistrationOrchestrator.Result r,
                                       File fixed, File moving, long elapsedMs) throws IOException {
        StringWriter sw = new StringWriter();
        try (PrintWriter pw = new PrintWriter(sw)) {
            pw.printf("Fixed : %s%n", fixed.getName());
            pw.printf("Moving: %s%n%n", moving.getName());
            pw.printf("Fixed  DAPI channel : %d (%s)%n", r.fixedDapiChannel,
                    r.stages == null ? "" : "");
            pw.printf("Moving DAPI channel : %d%n%n", r.movingDapiChannel);
            pw.printf("Stage 1 (coarse): %s%n", r.stage1Levels);
            pw.printf("  inliers=%d/%d (%.1f%%), reproj median=%.2fpx p95=%.2fpx%n",
                    r.stages.stage1.nInliers, r.stages.stage1.nMatchesPostPrefilter,
                    100.0 * r.stages.stage1.inlierRatio,
                    r.stages.stage1.medianReprojErrPx, r.stages.stage1.p95ReprojErrPx);
            pw.printf("Stage 2 (refine): %s%n", r.stage2Levels);
            pw.printf("  inliers=%d/%d (%.1f%%), reproj median=%.2fpx p95=%.2fpx%n%n",
                    r.stages.stage2.nInliers, r.stages.stage2.nMatchesPostPrefilter,
                    100.0 * r.stages.stage2.inlierRatio,
                    r.stages.stage2.medianReprojErrPx, r.stages.stage2.p95ReprojErrPx);

            pw.println("FULL-RESOLUTION matrix (moving_full -> fixed_full):");
            for (int i = 0; i < 3; i++) {
                pw.printf("  [%+.6e %+.6e %+.6e]%n",
                        r.matrixFullRes[i][0], r.matrixFullRes[i][1], r.matrixFullRes[i][2]);
            }
            double a = r.matrixFullRes[0][0], b = r.matrixFullRes[0][1];
            double c = r.matrixFullRes[1][0], d = r.matrixFullRes[1][1];
            pw.printf("%nAffine decomposition (full-res):%n");
            pw.printf("  rotation   ~ %+.4f deg%n", Math.toDegrees(Math.atan2(c, a)));
            pw.printf("  scale x/y  ~ %.5f / %.5f%n", Math.hypot(a, c), Math.hypot(b, d));
            pw.printf("  translation~ (%+.1f, %+.1f) px%n",
                    r.matrixFullRes[0][2], r.matrixFullRes[1][2]);
            pw.printf("%nElapsed: %d ms%n", elapsedMs);
        }
        Files.writeString(path, sw.toString());
    }
}
