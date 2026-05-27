package qupath.ext.mifmerge.core;

import java.awt.geom.AffineTransform;

/**
 * Output of {@link SiftRansacAffine#run(java.awt.image.BufferedImage, java.awt.image.BufferedImage, double[][], SiftRansacAffine.Params)}.
 *
 * <p>The {@link #affine} field maps moving-image pixel coordinates into
 * fixed-image pixel coordinates, at the resolution at which SIFT was run.
 * Callers wanting a different resolution should rescale via
 * {@link MatrixRescaler#rescale(double[][], double, double, double, double)}.
 *
 * <p>Diagnostic counts mirror the {@code info} dict produced by the Python
 * prototype's {@code sift_ransac_affine_cv2}.
 */
public final class RegistrationResult {

    /** moving -> fixed affine at the level at which SIFT was run. */
    public final AffineTransform affine;

    /** Row-major 3x3 representation of {@link #affine}. */
    public final double[][] matrix;

    public final int nKeypointsFixed;
    public final int nKeypointsMoving;
    public final int nMatchesPostLowe;
    public final int nMatchesPostPrefilter;
    public final int nInliers;
    public final double inlierRatio;
    public final double medianReprojErrPx;
    public final double p95ReprojErrPx;
    public final double maxReprojErrPx;

    public final long tDetectMillis;
    public final long tMatchMillis;
    public final long tRansacMillis;

    RegistrationResult(AffineTransform affine,
                       double[][] matrix,
                       int nKeypointsFixed, int nKeypointsMoving,
                       int nMatchesPostLowe, int nMatchesPostPrefilter,
                       int nInliers, double inlierRatio,
                       double medianReprojErrPx, double p95ReprojErrPx, double maxReprojErrPx,
                       long tDetectMillis, long tMatchMillis, long tRansacMillis) {
        this.affine = affine;
        this.matrix = matrix;
        this.nKeypointsFixed = nKeypointsFixed;
        this.nKeypointsMoving = nKeypointsMoving;
        this.nMatchesPostLowe = nMatchesPostLowe;
        this.nMatchesPostPrefilter = nMatchesPostPrefilter;
        this.nInliers = nInliers;
        this.inlierRatio = inlierRatio;
        this.medianReprojErrPx = medianReprojErrPx;
        this.p95ReprojErrPx = p95ReprojErrPx;
        this.maxReprojErrPx = maxReprojErrPx;
        this.tDetectMillis = tDetectMillis;
        this.tMatchMillis = tMatchMillis;
        this.tRansacMillis = tRansacMillis;
    }

    @Override
    public String toString() {
        return String.format(
                "RegistrationResult{kp=%d/%d, lowe=%d, prefilter=%d, inliers=%d (%.1f%%), "
                + "reproj median=%.2fpx p95=%.2fpx max=%.2fpx, "
                + "detect=%dms match=%dms ransac=%dms}",
                nKeypointsFixed, nKeypointsMoving, nMatchesPostLowe, nMatchesPostPrefilter,
                nInliers, 100.0 * inlierRatio,
                medianReprojErrPx, p95ReprojErrPx, maxReprojErrPx,
                tDetectMillis, tMatchMillis, tRansacMillis);
    }
}
