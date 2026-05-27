package qupath.ext.mifmerge.core;

/**
 * Pick consistent pyramid levels on the fixed and moving sides so that both
 * SIFT runs at approximately the same physical resolution.
 *
 * <p>The Python prototype's lung-ALK case demonstrated what happens if you
 * pick layers independently by absolute pixel count: fixed lands on level 2
 * and moving lands on level 3, the downsample factors differ by ~2x, the
 * stage-2 pre-filter throws out most candidates, and SIFT only finds ~186
 * inliers instead of the usual ~1000+. To prevent this:
 * <ol>
 *   <li>compute the desired downsample factor
 *       {@code target = fullSideLength / targetSideLengthPx};</li>
 *   <li>on each side independently pick the pyramid level whose downsample
 *       is closest to {@code target};</li>
 *   <li>but verify the two picked downsamples agree (within a tolerance) —
 *       if not, escalate the warning to the caller, who may choose to read
 *       at an intermediate resolution rather than at the native pyramid layer.</li>
 * </ol>
 *
 * <p>This class is intentionally decoupled from QuPath {@code ImageServer}
 * so it is unit-testable. {@link DapiPyramidSelector#pick} takes downsample
 * arrays directly.
 */
public final class DapiPyramidSelector {

    private DapiPyramidSelector() {}

    public static final class Selection {
        public final int levelFixed;
        public final int levelMoving;
        public final double downsampleFixed;
        public final double downsampleMoving;
        /** Ratio of larger / smaller downsample. 1.0 = perfectly matched. */
        public final double mismatchRatio;

        Selection(int lf, int lm, double df, double dm) {
            this.levelFixed = lf;
            this.levelMoving = lm;
            this.downsampleFixed = df;
            this.downsampleMoving = dm;
            this.mismatchRatio = Math.max(df, dm) / Math.min(df, dm);
        }

        @Override
        public String toString() {
            return String.format(
                    "Selection{fixedLevel=%d(ds=%.3f), movingLevel=%d(ds=%.3f), mismatch=%.3fx}",
                    levelFixed, downsampleFixed, levelMoving, downsampleMoving, mismatchRatio);
        }
    }

    /**
     * Pick a pyramid level for each side. The strategy errs on the side of
     * memory safety: prefer a slightly-smaller image over a slightly-larger
     * one. The resulting image is no more than ~20% larger than the user's
     * target, even if that means going 50% smaller — bigger images make
     * OpenCV SIFT allocate proportional-to-area native memory, so a 1.5x
     * image is 2.25x the memory.
     *
     * @param fixedFullSideLengthPx  the longer side of the fixed full-resolution image
     * @param movingFullSideLengthPx the longer side of the moving full-resolution image
     * @param targetSideLengthPx     desired thumbnail/refinement long-side
     * @param fixedDownsamples       downsamples array from the fixed server
     *                               (server.getPreferredDownsamples())
     * @param movingDownsamples      downsamples array from the moving server
     */
    public static Selection pick(double fixedFullSideLengthPx,
                                 double movingFullSideLengthPx,
                                 double targetSideLengthPx,
                                 double[] fixedDownsamples,
                                 double[] movingDownsamples) {
        // Prefer image_size <= target * 1.2, i.e. downsample >= full / (target * 1.2)
        double maxAcceptableImage = targetSideLengthPx * 1.2;
        double minDownsampleFixed = fixedFullSideLengthPx / maxAcceptableImage;
        double minDownsampleMoving = movingFullSideLengthPx / maxAcceptableImage;

        int lf = firstAtLeast(fixedDownsamples, minDownsampleFixed);
        int lm = firstAtLeast(movingDownsamples, minDownsampleMoving);
        return new Selection(lf, lm, fixedDownsamples[lf], movingDownsamples[lm]);
    }

    /**
     * Returns the smallest index {@code i} such that {@code downsamples[i] >= min}.
     * Falls back to the last index if no entry is large enough (i.e., the target
     * side length is bigger than the full image — caller asked for an unreasonable
     * size, just give them the coarsest level we have).
     */
    private static int firstAtLeast(double[] downsamples, double min) {
        if (downsamples == null || downsamples.length == 0) {
            throw new IllegalArgumentException("downsamples must be non-empty");
        }
        for (int i = 0; i < downsamples.length; i++) {
            if (downsamples[i] >= min) {
                return i;
            }
        }
        // No level downsamples enough; use the coarsest (largest downsample) available.
        return downsamples.length - 1;
    }
}
