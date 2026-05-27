package qupath.ext.mifmerge.core;

import java.awt.geom.AffineTransform;

/**
 * Pure-math helpers for moving an affine matrix between pyramid levels.
 *
 * <p>Given a matrix {@code M} that maps moving-image coordinates at one
 * resolution to fixed-image coordinates at the same resolution, the matrix
 * at a different pair of resolutions is
 * <pre>{@code
 *   M' = S_fixed @ M @ inv(S_moving)
 * }</pre>
 * where {@code S_fixed} scales fixed coords from "old" to "new" resolution
 * and likewise for moving. This mirrors the rescaling done in
 * register_batch.py (search for "Sf_up" / "S_m_inv_up").
 */
public final class MatrixRescaler {

    private MatrixRescaler() {}

    /**
     * Rescale a 3x3 affine.
     *
     * @param matrix 3x3 row-major matrix mapping moving_old -> fixed_old
     * @param sFixedX  newFixedWidth  / oldFixedWidth
     * @param sFixedY  newFixedHeight / oldFixedHeight
     * @param sMovingX newMovingWidth / oldMovingWidth
     * @param sMovingY newMovingHeight/ oldMovingHeight
     * @return matrix mapping moving_new -> fixed_new
     */
    public static double[][] rescale(double[][] matrix,
                                     double sFixedX, double sFixedY,
                                     double sMovingX, double sMovingY) {
        double[][] sF  = diag(sFixedX, sFixedY);
        double[][] sMi = diag(1.0 / sMovingX, 1.0 / sMovingY);
        return mul(sF, mul(matrix, sMi));
    }

    public static AffineTransform toAffineTransform(double[][] m) {
        return new AffineTransform(
                m[0][0], m[1][0],   // m00, m10
                m[0][1], m[1][1],   // m01, m11
                m[0][2], m[1][2]);  // m02, m12
    }

    public static double[][] fromAffineTransform(AffineTransform t) {
        double[] flat = new double[6];
        t.getMatrix(flat);
        // flat = [m00, m10, m01, m11, m02, m12]
        return new double[][] {
                { flat[0], flat[2], flat[4] },
                { flat[1], flat[3], flat[5] },
                { 0.0,     0.0,     1.0 }
        };
    }

    private static double[][] diag(double sx, double sy) {
        return new double[][] {
                { sx,  0,  0 },
                {  0, sy,  0 },
                {  0,  0,  1 }
        };
    }

    private static double[][] mul(double[][] a, double[][] b) {
        double[][] r = new double[3][3];
        for (int i = 0; i < 3; i++) {
            for (int j = 0; j < 3; j++) {
                double s = 0;
                for (int k = 0; k < 3; k++) {
                    s += a[i][k] * b[k][j];
                }
                r[i][j] = s;
            }
        }
        return r;
    }
}
