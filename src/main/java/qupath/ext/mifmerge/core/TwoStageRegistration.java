package qupath.ext.mifmerge.core;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.image.BufferedImage;

/**
 * Two-stage hierarchical SIFT registration on DAPI thumbnails, mirroring
 * {@code register_pair} in register_batch.py.
 *
 * <p>Stage 1 runs SIFT+RANSAC on a coarse thumbnail (e.g. long side ~4000 px)
 * to obtain an initial moving->fixed affine. Stage 2 re-runs SIFT+RANSAC on a
 * finer level (e.g. long side ~14000 px) using the rescaled stage-1 matrix to
 * pre-filter candidate matches within a small radius (30 px at the stage-2
 * resolution). The stage-2 result is then rescaled to whatever target
 * resolution the caller cares about (typically full-res for warping).
 *
 * <p>The caller is responsible for selecting a consistent downsample factor on
 * fixed and moving sides for each stage (see {@link DapiPyramidSelector}).
 * Picking different pyramid layers by absolute pixel count on the two sides
 * causes the prefilter step to throw out good matches; see the lung-ALK case
 * called out in the Python prototype.
 */
public final class TwoStageRegistration {

    private static final Logger logger = LoggerFactory.getLogger(TwoStageRegistration.class);

    private TwoStageRegistration() {}

    public static final class Inputs {
        public final BufferedImage stage1Fixed;
        public final BufferedImage stage1Moving;
        public final BufferedImage stage2Fixed;
        public final BufferedImage stage2Moving;

        /** Scale factors: stage2_size / stage1_size, on the fixed side. */
        public final double stage1ToStage2_sxFixed;
        public final double stage1ToStage2_syFixed;
        /** Same on the moving side. */
        public final double stage1ToStage2_sxMoving;
        public final double stage1ToStage2_syMoving;

        public Inputs(BufferedImage stage1Fixed, BufferedImage stage1Moving,
                      BufferedImage stage2Fixed, BufferedImage stage2Moving,
                      double sxFixedS1ToS2, double syFixedS1ToS2,
                      double sxMovingS1ToS2, double syMovingS1ToS2) {
            this.stage1Fixed = stage1Fixed;
            this.stage1Moving = stage1Moving;
            this.stage2Fixed = stage2Fixed;
            this.stage2Moving = stage2Moving;
            this.stage1ToStage2_sxFixed = sxFixedS1ToS2;
            this.stage1ToStage2_syFixed = syFixedS1ToS2;
            this.stage1ToStage2_sxMoving = sxMovingS1ToS2;
            this.stage1ToStage2_syMoving = syMovingS1ToS2;
        }
    }

    public static final class Output {
        public final RegistrationResult stage1;
        public final RegistrationResult stage2;
        /** stage-2 matrix in stage-1 coordinates (rescaled back). */
        public final double[][] matrixAtStage1Resolution;
        /** stage-2 matrix in stage-2 coordinates (native). */
        public final double[][] matrixAtStage2Resolution;

        Output(RegistrationResult stage1, RegistrationResult stage2,
               double[][] matrixAtStage1Resolution, double[][] matrixAtStage2Resolution) {
            this.stage1 = stage1;
            this.stage2 = stage2;
            this.matrixAtStage1Resolution = matrixAtStage1Resolution;
            this.matrixAtStage2Resolution = matrixAtStage2Resolution;
        }
    }

    public static Output run(Inputs in, SiftRansacAffine.Params stage1Params, SiftRansacAffine.Params stage2Params) {
        if (stage1Params == null) stage1Params = new SiftRansacAffine.Params();
        if (stage2Params == null) {
            stage2Params = new SiftRansacAffine.Params().prefilterRadiusPx(30.0).ransacReprojThresholdPx(3.0);
        }

        logger.info("Stage 1: SIFT on {}x{} / {}x{}",
                in.stage1Fixed.getWidth(), in.stage1Fixed.getHeight(),
                in.stage1Moving.getWidth(), in.stage1Moving.getHeight());
        // Stage 1 ignores any caller-provided mInit; it has no prior.
        stage1Params.mInit(null);
        RegistrationResult s1 = SiftRansacAffine.run(in.stage1Fixed, in.stage1Moving, stage1Params);

        double[][] mInitStage2 = MatrixRescaler.rescale(
                s1.matrix,
                in.stage1ToStage2_sxFixed,  in.stage1ToStage2_syFixed,
                in.stage1ToStage2_sxMoving, in.stage1ToStage2_syMoving);

        logger.info("Stage 2: SIFT on {}x{} / {}x{}  (with stage-1 prior, radius={}px)",
                in.stage2Fixed.getWidth(), in.stage2Fixed.getHeight(),
                in.stage2Moving.getWidth(), in.stage2Moving.getHeight(),
                stage2Params.prefilterRadiusPx);
        stage2Params.mInit(mInitStage2);
        RegistrationResult s2 = SiftRansacAffine.run(in.stage2Fixed, in.stage2Moving, stage2Params);

        // Rescale stage-2 matrix back to stage-1 coordinates for diagnostics / drop-in replacement
        double[][] s2InStage1 = MatrixRescaler.rescale(
                s2.matrix,
                1.0 / in.stage1ToStage2_sxFixed,  1.0 / in.stage1ToStage2_syFixed,
                1.0 / in.stage1ToStage2_sxMoving, 1.0 / in.stage1ToStage2_syMoving);

        return new Output(s1, s2, s2InStage1, s2.matrix);
    }
}
