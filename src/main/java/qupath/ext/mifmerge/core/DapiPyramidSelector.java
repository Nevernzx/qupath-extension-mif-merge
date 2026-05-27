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
     * @param fixedFullSideLengthPx  the longer side of the fixed full-resolution image
     * @param movingFullSideLengthPx the longer side of the moving full-resolution image
     * @param targetSideLengthPx     desired thumbnail/refinement long-side
     * @param fixedDownsamples       downsamples array from the fixed server (server.getPreferredDownsamples())
     * @param movingDownsamples      downsamples array from the moving server
     */
    public static Selection pick(double fixedFullSideLengthPx,
                                 double movingFullSideLengthPx,
                                 double targetSideLengthPx,
                                 double[] fixedDownsamples,
                                 double[] movingDownsamples) {
        double targetDownsampleFixed = fixedFullSideLengthPx / targetSideLengthPx;
        double targetDownsampleMoving = movingFullSideLengthPx / targetSideLengthPx;

        int lf = nearest(fixedDownsamples, targetDownsampleFixed);
        int lm = nearest(movingDownsamples, targetDownsampleMoving);
        return new Selection(lf, lm, fixedDownsamples[lf], movingDownsamples[lm]);
    }

    private static int nearest(double[] downsamples, double target) {
        if (downsamples == null || downsamples.length == 0) {
            throw new IllegalArgumentException("downsamples must be non-empty");
        }
        int best = 0;
        double bestDiff = Math.abs(downsamples[0] - target);
        for (int i = 1; i < downsamples.length; i++) {
            double d = Math.abs(downsamples[i] - target);
            if (d < bestDiff) {
                bestDiff = d;
                best = i;
            }
        }
        return best;
    }
}
