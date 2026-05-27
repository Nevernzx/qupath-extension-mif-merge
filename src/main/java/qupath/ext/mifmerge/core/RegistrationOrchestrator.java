package qupath.ext.mifmerge.core;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.ext.mifmerge.io.BioFormatsMifSource;

import java.awt.image.BufferedImage;
import java.util.List;
import java.util.function.Consumer;

/**
 * High-level entry point that takes two {@link MifImageSource} (fixed + moving),
 * auto-identifies the DAPI channel on each, picks consistent pyramid levels
 * for stage 1 (coarse) and stage 2 (refine), runs {@link TwoStageRegistration},
 * and returns the final affine matrix at full resolution.
 *
 * <p>This is the per-pair driver — the analog of {@code register_pair()} in
 * register_batch.py, but with input/output decoupled from any specific reader
 * (so the same code can be invoked from a CLI or from a QuPath GUI command).
 */
public final class RegistrationOrchestrator {

    private static final Logger logger = LoggerFactory.getLogger(RegistrationOrchestrator.class);

    public static final class Config {
        /** Substring matched (case-insensitive) against channel names to find DAPI. */
        public String dapiNameMatch = "DAPI";
        /** Target long-side (px) for stage 1 coarse SIFT. */
        public int stage1TargetLongPx = 4000;
        /** Target long-side (px) for stage 2 refinement SIFT. */
        public int stage2TargetLongPx = 14000;
        /**
         * If the picked fixed/moving downsamples differ by more than this ratio,
         * log a warning. The lung-ALK case in the Python prototype had a 2x
         * mismatch and SIFT inliers dropped from ~1000 to ~186.
         */
        public double allowedDownsampleMismatchRatio = 1.5;
        public SiftRansacAffine.Params stage1Params = new SiftRansacAffine.Params();
        public SiftRansacAffine.Params stage2Params = new SiftRansacAffine.Params()
                .prefilterRadiusPx(30.0).ransacReprojThresholdPx(3.0);
        /**
         * Enable Stage 3 windowed full-resolution refinement. Bounded memory
         * (~100 MB native per window) regardless of full-res image size.
         * Refines the Stage 2 matrix from ~4-8 px @ full-res down to ~1 px.
         */
        public boolean enableStage3 = false;
        public WindowedRefinement.Config stage3 = new WindowedRefinement.Config();
    }

    public static final class Result {
        public final int fixedDapiChannel;
        public final int movingDapiChannel;
        public final DapiPyramidSelector.Selection stage1Levels;
        public final DapiPyramidSelector.Selection stage2Levels;
        public final TwoStageRegistration.Output stages;
        /** Final affine at FULL resolution, moving_full -> fixed_full. */
        public final double[][] matrixFullRes;
        /** Stage 3 result if enabled, else null. */
        public final WindowedRefinement.Result stage3;

        Result(int fdc, int mdc,
               DapiPyramidSelector.Selection s1Lvl, DapiPyramidSelector.Selection s2Lvl,
               TwoStageRegistration.Output stages, double[][] matrixFullRes,
               WindowedRefinement.Result stage3) {
            this.fixedDapiChannel = fdc;
            this.movingDapiChannel = mdc;
            this.stage1Levels = s1Lvl;
            this.stage2Levels = s2Lvl;
            this.stages = stages;
            this.matrixFullRes = matrixFullRes;
            this.stage3 = stage3;
        }
    }

    private RegistrationOrchestrator() {}

    public static Result run(MifImageSource fixed, MifImageSource moving, Config cfg) {
        return run(fixed, moving, cfg, null);
    }

    /**
     * Same as {@link #run(MifImageSource, MifImageSource, Config)} but also pushes
     * step-by-step progress messages to the given consumer (typically the GUI's
     * log TextArea). Useful for diagnosing where things go wrong if memory blows
     * up or a step hangs.
     */
    public static Result run(MifImageSource fixed, MifImageSource moving, Config cfg,
                             Consumer<String> uiLog) {
        if (cfg == null) cfg = new Config();
        Consumer<String> say = uiLog != null ? uiLog : s -> {};

        say.accept("orchestrator: find DAPI channels");
        int fdc = BioFormatsMifSource.findChannelIndex(fixed.getChannelNames(), cfg.dapiNameMatch);
        int mdc = BioFormatsMifSource.findChannelIndex(moving.getChannelNames(), cfg.dapiNameMatch);
        if (fdc < 0) {
            throw new IllegalStateException(
                    "No channel matching '" + cfg.dapiNameMatch + "' found in fixed "
                            + fixed.getDisplayName() + ": " + fixed.getChannelNames());
        }
        if (mdc < 0) {
            throw new IllegalStateException(
                    "No channel matching '" + cfg.dapiNameMatch + "' found in moving "
                            + moving.getDisplayName() + ": " + moving.getChannelNames());
        }
        logger.info("DAPI channels: fixed[{}]={} | moving[{}]={}",
                fdc, fixed.getChannelNames().get(fdc),
                mdc, moving.getChannelNames().get(mdc));
        say.accept(String.format("orchestrator: DAPI fixed[%d]=%s | moving[%d]=%s",
                fdc, fixed.getChannelNames().get(fdc),
                mdc, moving.getChannelNames().get(mdc)));

        say.accept("orchestrator: read pyramid metadata");
        double fxFull = Math.max(fixed.getFullWidth(), fixed.getFullHeight());
        double mvFull = Math.max(moving.getFullWidth(), moving.getFullHeight());
        double[] fxDs = fixed.getDownsamples();
        double[] mvDs = moving.getDownsamples();
        say.accept(String.format("orchestrator: fixed full long=%d, ds=%s", (int) fxFull, arrToStr(fxDs)));
        say.accept(String.format("orchestrator: moving full long=%d, ds=%s", (int) mvFull, arrToStr(mvDs)));

        DapiPyramidSelector.Selection s1 = DapiPyramidSelector.pick(
                fxFull, mvFull, cfg.stage1TargetLongPx, fxDs, mvDs);
        DapiPyramidSelector.Selection s2 = DapiPyramidSelector.pick(
                fxFull, mvFull, cfg.stage2TargetLongPx, fxDs, mvDs);
        logger.info("Stage 1 levels: {}", s1);
        logger.info("Stage 2 levels: {}", s2);
        say.accept("orchestrator: stage 1 " + s1);
        say.accept("orchestrator: stage 2 " + s2);
        if (s1.mismatchRatio > cfg.allowedDownsampleMismatchRatio) {
            logger.warn("Stage 1 downsample mismatch {}x exceeds allowed {}x — "
                    + "expect degraded SIFT inlier count. Consider different target_long.",
                    String.format("%.2f", s1.mismatchRatio),
                    cfg.allowedDownsampleMismatchRatio);
        }
        if (s2.mismatchRatio > cfg.allowedDownsampleMismatchRatio) {
            logger.warn("Stage 2 downsample mismatch {}x exceeds allowed {}x — "
                    + "this is the lung-ALK failure mode.",
                    String.format("%.2f", s2.mismatchRatio),
                    cfg.allowedDownsampleMismatchRatio);
        }

        say.accept("orchestrator: reading stage 1 DAPI fixed (level " + s1.levelFixed + ")");
        BufferedImage s1Fixed = fixed.readChannelAtLevel(fdc, s1.levelFixed);
        say.accept(String.format("orchestrator: stage 1 DAPI fixed read: %dx%d", s1Fixed.getWidth(), s1Fixed.getHeight()));

        say.accept("orchestrator: reading stage 1 DAPI moving (level " + s1.levelMoving + ")");
        BufferedImage s1Moving = moving.readChannelAtLevel(mdc, s1.levelMoving);
        say.accept(String.format("orchestrator: stage 1 DAPI moving read: %dx%d", s1Moving.getWidth(), s1Moving.getHeight()));

        say.accept("orchestrator: reading stage 2 DAPI fixed (level " + s2.levelFixed + ")");
        BufferedImage s2Fixed = fixed.readChannelAtLevel(fdc, s2.levelFixed);
        say.accept(String.format("orchestrator: stage 2 DAPI fixed read: %dx%d", s2Fixed.getWidth(), s2Fixed.getHeight()));

        say.accept("orchestrator: reading stage 2 DAPI moving (level " + s2.levelMoving + ")");
        BufferedImage s2Moving = moving.readChannelAtLevel(mdc, s2.levelMoving);
        say.accept(String.format("orchestrator: stage 2 DAPI moving read: %dx%d", s2Moving.getWidth(), s2Moving.getHeight()));

        logger.info("Read DAPI: stage1 fixed {}x{} / moving {}x{} | stage2 fixed {}x{} / moving {}x{}",
                s1Fixed.getWidth(), s1Fixed.getHeight(),
                s1Moving.getWidth(), s1Moving.getHeight(),
                s2Fixed.getWidth(), s2Fixed.getHeight(),
                s2Moving.getWidth(), s2Moving.getHeight());

        // Scale factor between stage-1 and stage-2 resolutions, per side.
        double s1ToS2_sxF = (double) s2Fixed.getWidth()  / s1Fixed.getWidth();
        double s1ToS2_syF = (double) s2Fixed.getHeight() / s1Fixed.getHeight();
        double s1ToS2_sxM = (double) s2Moving.getWidth()  / s1Moving.getWidth();
        double s1ToS2_syM = (double) s2Moving.getHeight() / s1Moving.getHeight();

        TwoStageRegistration.Inputs ins = new TwoStageRegistration.Inputs(
                s1Fixed, s1Moving, s2Fixed, s2Moving,
                s1ToS2_sxF, s1ToS2_syF, s1ToS2_sxM, s1ToS2_syM);

        say.accept("orchestrator: starting stage 1 SIFT");
        TwoStageRegistration.Output stages = TwoStageRegistration.run(
                ins, cfg.stage1Params, cfg.stage2Params);
        say.accept("orchestrator: SIFT done");

        // Scale stage-2 matrix to full resolution: M_full = S_fixed * M_l2 * inv(S_moving)
        double sxF_full = (double) fixed.getFullWidth()  / s2Fixed.getWidth();
        double syF_full = (double) fixed.getFullHeight() / s2Fixed.getHeight();
        double sxM_full = (double) moving.getFullWidth()  / s2Moving.getWidth();
        double syM_full = (double) moving.getFullHeight() / s2Moving.getHeight();
        double[][] matrixFullRes = MatrixRescaler.rescale(
                stages.matrixAtStage2Resolution,
                sxF_full, syF_full, sxM_full, syM_full);
        logger.info("Full-res affine (moving -> fixed):");
        for (int i = 0; i < 3; i++) {
            logger.info("  [{}, {}, {}]",
                    String.format("%+.6e", matrixFullRes[i][0]),
                    String.format("%+.6e", matrixFullRes[i][1]),
                    String.format("%+.6e", matrixFullRes[i][2]));
        }

        // Optional Stage 3: windowed full-resolution refinement
        WindowedRefinement.Result stage3 = null;
        double[][] finalMatrix = matrixFullRes;
        if (cfg.enableStage3) {
            say.accept("orchestrator: starting Stage 3 windowed refinement");
            try {
                stage3 = WindowedRefinement.run(
                        fixed, moving, fdc, mdc, matrixFullRes, s1Fixed, cfg.stage3, uiLog);
                finalMatrix = stage3.refinedMatrixFullRes;
                say.accept(String.format(
                        "orchestrator: Stage 3 done — refined %d inliers, median %.2fpx @full-res",
                        stage3.finalInliers, stage3.reprojMedianPx));
            } catch (Throwable t) {
                say.accept("orchestrator: Stage 3 failed: " + t.getMessage()
                        + " — keeping Stage 2 matrix");
                logger.warn("Stage 3 failed, keeping Stage 2 matrix", t);
            }
        }

        return new Result(fdc, mdc, s1, s2, stages, finalMatrix, stage3);
    }

    private static String arrToStr(double[] arr) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < arr.length; i++) {
            if (i > 0) sb.append(", ");
            sb.append(String.format("%.2f", arr[i]));
        }
        sb.append("]");
        return sb.toString();
    }
}
