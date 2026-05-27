package qupath.ext.mifmerge.core;

import org.bytedeco.javacpp.indexer.DoubleIndexer;
import org.bytedeco.javacpp.indexer.FloatIndexer;
import org.bytedeco.javacpp.indexer.UByteIndexer;
import org.bytedeco.opencv.global.opencv_calib3d;
import org.bytedeco.opencv.opencv_core.DMatch;
import org.bytedeco.opencv.opencv_core.DMatchVector;
import org.bytedeco.opencv.opencv_core.DMatchVectorVector;
import org.bytedeco.opencv.opencv_core.KeyPoint;
import org.bytedeco.opencv.opencv_core.KeyPointVector;
import org.bytedeco.opencv.opencv_core.Mat;
import org.bytedeco.opencv.opencv_features2d.BFMatcher;
import org.bytedeco.opencv.opencv_features2d.SIFT;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.image.BufferedImage;
import java.awt.image.DataBufferByte;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.Consumer;

import static org.bytedeco.opencv.global.opencv_core.CV_32F;
import static org.bytedeco.opencv.global.opencv_core.CV_32FC2;
import static org.bytedeco.opencv.global.opencv_core.CV_8UC1;
import static org.bytedeco.opencv.global.opencv_core.NORM_L2;

/**
 * Stage 3: full-resolution refinement using small windowed reads.
 *
 * <p>Stage 1 + Stage 2 give us a moderately accurate affine matrix (~4-8 px
 * @ full-res). Running SIFT on the entire full-resolution image to push
 * precision below 1 px is impractical: a Vectra Polaris qptiff full-res
 * plane is ~3 GB and OpenCV SIFT's scale-space blows up to tens of GB.
 *
 * <p>This stage runs SIFT only on a few small (1-2k px) windows of the
 * full-resolution image:
 * <ol>
 *   <li>Find tissue-rich regions by thresholding the stage-1 fixed DAPI
 *       thumbnail.</li>
 *   <li>Sample {@code numWindows} window centres distributed across the
 *       tissue regions.</li>
 *   <li>For each fixed window: project to moving coordinates via the
 *       Stage 2 matrix; read both windows at full resolution; run SIFT on
 *       this small pair; keep inlier point correspondences (translated to
 *       global full-res coordinates).</li>
 *   <li>Run a final RANSAC affine on all collected global points.</li>
 * </ol>
 *
 * <p>Peak memory per window is ~50-100 MB native (SIFT scale-space on a
 * 1-2k image), bounded regardless of full-res image size. Total runtime
 * scales linearly with {@code numWindows}.
 */
public final class WindowedRefinement {

    private static final Logger logger = LoggerFactory.getLogger(WindowedRefinement.class);

    public static final class Config {
        /** Number of windows to sample. More windows = more precise + slower. */
        public int numWindows = 16;
        /** Window edge length in full-res pixels. ~2000 is enough for ~50-200 inliers per window. */
        public int windowSizePx = 2048;
        /** Minimum tissue fraction (0..1) inside a candidate window required to keep it. */
        public double minTissueFraction = 0.10;
        /** Tissue mask threshold on autocontrasted DAPI (0..255). */
        public int tissueMaskThreshold = 16;
        /** Padding (in full-res px) when reading the moving window — extra room for stage 2 error. */
        public int movingPaddingPx = 200;
        /** SIFT keypoints per window. */
        public int siftNFeaturesPerWindow = 5000;
        /** Lowe ratio for window-level matching. */
        public double loweRatio = 0.75;
        /** Final RANSAC reprojection threshold (full-res pixels). */
        public double ransacReprojThresholdPx = 3.0;
        /** Final RANSAC max iterations. */
        public int ransacMaxIters = 5000;
    }

    public static final class Result {
        public final double[][] refinedMatrixFullRes;
        public final int totalPointPairs;
        public final int finalInliers;
        public final double inlierRatio;
        public final double reprojMedianPx;
        public final double reprojP95Px;
        public final int windowsAttempted;
        public final int windowsSucceeded;
        public final long elapsedMillis;

        Result(double[][] m, int total, int inliers, double ratio,
               double median, double p95, int attempted, int succeeded, long elapsed) {
            this.refinedMatrixFullRes = m;
            this.totalPointPairs = total;
            this.finalInliers = inliers;
            this.inlierRatio = ratio;
            this.reprojMedianPx = median;
            this.reprojP95Px = p95;
            this.windowsAttempted = attempted;
            this.windowsSucceeded = succeeded;
            this.elapsedMillis = elapsed;
        }
    }

    private WindowedRefinement() {}

    /**
     * Run windowed refinement.
     *
     * @param fixed         the fixed source (level 0 = full resolution)
     * @param moving        the moving source
     * @param fdc           fixed DAPI channel index
     * @param mdc           moving DAPI channel index
     * @param initialMatrixFullRes  the result of Stage 1+2, in full-res coords
     * @param stage1FixedDapi  the fixed DAPI thumbnail from Stage 1 (used for tissue mask)
     * @param cfg           configuration
     * @param uiLog         optional progress callback (e.g. GUI text area)
     */
    public static Result run(MifImageSource fixed, MifImageSource moving,
                             int fdc, int mdc,
                             double[][] initialMatrixFullRes,
                             BufferedImage stage1FixedDapi,
                             Config cfg,
                             Consumer<String> uiLog) {
        if (cfg == null) cfg = new Config();
        Consumer<String> say = uiLog != null ? uiLog : s -> {};
        long t0 = System.currentTimeMillis();

        int fullW = fixed.getFullWidth();
        int fullH = fixed.getFullHeight();
        int movFullW = moving.getFullWidth();
        int movFullH = moving.getFullHeight();
        say.accept(String.format("stage3: fixed full %dx%d, moving full %dx%d",
                fullW, fullH, movFullW, movFullH));

        // 1. Sample tissue-rich window centres from stage-1 thumbnail.
        List<int[]> centres = sampleWindowCentres(stage1FixedDapi, fullW, fullH,
                cfg.numWindows, cfg.minTissueFraction, cfg.tissueMaskThreshold,
                cfg.windowSizePx);
        say.accept(String.format("stage3: sampled %d window centres (target %d)",
                centres.size(), cfg.numWindows));
        if (centres.isEmpty()) {
            throw new RuntimeException(
                    "Stage 3 found no tissue-rich windows; lower minTissueFraction or skip Stage 3.");
        }

        // 2. For each window, read full-res patches + SIFT + collect global points.
        List<double[]> srcPts = new ArrayList<>();   // moving full-res
        List<double[]> dstPts = new ArrayList<>();   // fixed  full-res
        int succeeded = 0;
        for (int i = 0; i < centres.size(); i++) {
            int[] c = centres.get(i);
            int cx = c[0];
            int cy = c[1];
            int half = cfg.windowSizePx / 2;
            int fx0 = clamp(cx - half, 0, fullW - cfg.windowSizePx);
            int fy0 = clamp(cy - half, 0, fullH - cfg.windowSizePx);
            int fxw = Math.min(cfg.windowSizePx, fullW - fx0);
            int fyh = Math.min(cfg.windowSizePx, fullH - fy0);

            // Project fixed window corners through initial matrix^-1 to get moving region.
            // matrix maps moving -> fixed, so we need its inverse.
            double[][] mInv = inverseAffine(initialMatrixFullRes);
            double[] mc1 = applyAffine(mInv, fx0,         fy0);
            double[] mc2 = applyAffine(mInv, fx0 + fxw,   fy0);
            double[] mc3 = applyAffine(mInv, fx0,         fy0 + fyh);
            double[] mc4 = applyAffine(mInv, fx0 + fxw,   fy0 + fyh);
            double minX = min4(mc1[0], mc2[0], mc3[0], mc4[0]) - cfg.movingPaddingPx;
            double minY = min4(mc1[1], mc2[1], mc3[1], mc4[1]) - cfg.movingPaddingPx;
            double maxX = max4(mc1[0], mc2[0], mc3[0], mc4[0]) + cfg.movingPaddingPx;
            double maxY = max4(mc1[1], mc2[1], mc3[1], mc4[1]) + cfg.movingPaddingPx;
            int mx0 = clamp((int) Math.floor(minX), 0, movFullW - 1);
            int my0 = clamp((int) Math.floor(minY), 0, movFullH - 1);
            int mxw = Math.min(movFullW - mx0, (int) Math.ceil(maxX - mx0));
            int myh = Math.min(movFullH - my0, (int) Math.ceil(maxY - my0));
            if (mxw <= 50 || myh <= 50) {
                say.accept(String.format("stage3:   window %d/%d: moving region too small after projection, skipping",
                        i + 1, centres.size()));
                continue;
            }

            say.accept(String.format(
                    "stage3:   window %d/%d: fixed[%d,%d %dx%d] moving[%d,%d %dx%d]",
                    i + 1, centres.size(), fx0, fy0, fxw, fyh, mx0, my0, mxw, myh));

            BufferedImage fixWin;
            BufferedImage movWin;
            try {
                fixWin = fixed.readRegionAtLevel(fdc, 0, fx0, fy0, fxw, fyh);
                movWin = moving.readRegionAtLevel(mdc, 0, mx0, my0, mxw, myh);
            } catch (Exception e) {
                say.accept("stage3:     read failed: " + e.getMessage() + " — skipping");
                continue;
            }

            // Run SIFT on this small pair, return inlier pairs in window-local coords.
            List<double[][]> pairs;
            try {
                pairs = siftAndMatch(fixWin, movWin, cfg);
            } catch (Exception e) {
                say.accept("stage3:     SIFT failed: " + e.getMessage() + " — skipping");
                continue;
            }
            // Translate window-local coords to global full-res coords.
            for (double[][] p : pairs) {
                srcPts.add(new double[] { p[0][0] + mx0, p[0][1] + my0 });   // moving global
                dstPts.add(new double[] { p[1][0] + fx0, p[1][1] + fy0 });   // fixed  global
            }
            say.accept(String.format("stage3:     +%d point pairs (total %d)", pairs.size(), srcPts.size()));
            succeeded++;
        }

        if (srcPts.size() < 10) {
            throw new RuntimeException(
                    "Stage 3 collected only " + srcPts.size() + " point pairs across "
                            + succeeded + " successful windows — not enough for RANSAC refinement.");
        }

        // 3. Final RANSAC affine on all global points.
        say.accept(String.format("stage3: final RANSAC on %d point pairs (across %d windows)",
                srcPts.size(), succeeded));
        double[][] refined = ransacAffineFinal(srcPts, dstPts, cfg);
        double[] errs = reprojErrors(refined, srcPts, dstPts);
        Arrays.sort(errs);
        double median = errs.length == 0 ? 0 : errs[errs.length / 2];
        double p95    = errs.length == 0 ? 0 : errs[(int) (errs.length * 0.95)];

        long elapsed = System.currentTimeMillis() - t0;
        say.accept(String.format(
                "stage3: refined matrix found. inliers %d/%d (%.1f%%), reproj median %.2fpx p95 %.2fpx @full-res, %.1fs",
                errs.length, srcPts.size(), 100.0 * errs.length / srcPts.size(),
                median, p95, elapsed / 1000.0));

        return new Result(refined, srcPts.size(), errs.length,
                (double) errs.length / Math.max(1, srcPts.size()),
                median, p95, centres.size(), succeeded, elapsed);
    }

    // -------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------

    /**
     * Sample window centres on a regular grid filtered to tissue-rich cells.
     * Coordinates are in FULL-RES pixels.
     */
    private static List<int[]> sampleWindowCentres(BufferedImage thumb,
                                                   int fullW, int fullH,
                                                   int numWindows, double minTissueFraction,
                                                   int threshold, int windowSizeFullRes) {
        byte[] data = ((DataBufferByte) thumb.getRaster().getDataBuffer()).getData();
        int tw = thumb.getWidth();
        int th = thumb.getHeight();
        // Pick a grid that yields roughly numWindows cells (more, since some will fail tissue test)
        int gridN = (int) Math.ceil(Math.sqrt(numWindows * 1.5));
        double cellW = (double) tw / gridN;
        double cellH = (double) th / gridN;

        List<double[]> ranked = new ArrayList<>();   // [centerX_thumb, centerY_thumb, tissueFrac]
        for (int gy = 0; gy < gridN; gy++) {
            for (int gx = 0; gx < gridN; gx++) {
                int x0 = (int) (gx * cellW);
                int y0 = (int) (gy * cellH);
                int x1 = (int) ((gx + 1) * cellW);
                int y1 = (int) ((gy + 1) * cellH);
                int cx = (x0 + x1) / 2;
                int cy = (y0 + y1) / 2;
                int tissue = 0;
                int total = 0;
                for (int y = y0; y < y1; y++) {
                    int row = y * tw;
                    for (int x = x0; x < x1; x++) {
                        if ((data[row + x] & 0xFF) > threshold) tissue++;
                        total++;
                    }
                }
                double frac = total == 0 ? 0 : (double) tissue / total;
                if (frac >= minTissueFraction) {
                    ranked.add(new double[] { cx, cy, frac });
                }
            }
        }
        // Sort by tissue density descending, take top numWindows
        ranked.sort((a, b) -> Double.compare(b[2], a[2]));
        int take = Math.min(numWindows, ranked.size());

        // Scale thumbnail-coord centres to full-res coords
        double sx = (double) fullW / tw;
        double sy = (double) fullH / th;
        List<int[]> out = new ArrayList<>();
        for (int i = 0; i < take; i++) {
            double[] c = ranked.get(i);
            int fx = (int) (c[0] * sx);
            int fy = (int) (c[1] * sy);
            out.add(new int[] { fx, fy });
        }
        return out;
    }

    /**
     * SIFT + Lowe + RANSAC on a small pair of grayscale BufferedImages.
     * Returns list of inlier point pairs [{movingXY, fixedXY}, ...] in window-local coords.
     */
    private static List<double[][]> siftAndMatch(BufferedImage fixed, BufferedImage moving, Config cfg) {
        Mat fixMat = toGrayMat(fixed);
        Mat movMat = toGrayMat(moving);
        try (SIFT sift = SIFT.create(cfg.siftNFeaturesPerWindow, 3, 0.04, 10.0, 1.6, CV_32F)) {
            KeyPointVector kpF = new KeyPointVector();
            KeyPointVector kpM = new KeyPointVector();
            Mat desF = new Mat();
            Mat desM = new Mat();
            sift.detectAndCompute(fixMat, new Mat(), kpF, desF);
            sift.detectAndCompute(movMat, new Mat(), kpM, desM);
            if (kpF.size() < 10 || kpM.size() < 10) {
                desF.release(); desM.release();
                return new ArrayList<>();
            }
            DMatchVectorVector knn = new DMatchVectorVector();
            try (BFMatcher matcher = new BFMatcher(NORM_L2, false)) {
                matcher.knnMatch(desM, desF, knn, 2);
            }
            List<float[]> srcList = new ArrayList<>();   // moving
            List<float[]> dstList = new ArrayList<>();   // fixed
            for (long i = 0; i < knn.size(); i++) {
                DMatchVector pair = knn.get(i);
                if (pair.size() < 2) continue;
                DMatch m1 = pair.get(0);
                DMatch m2 = pair.get(1);
                if (m1.distance() > cfg.loweRatio * m2.distance()) continue;
                KeyPoint kM = kpM.get(m1.queryIdx());
                KeyPoint kF = kpF.get(m1.trainIdx());
                srcList.add(new float[] { kM.pt().x(), kM.pt().y() });
                dstList.add(new float[] { kF.pt().x(), kF.pt().y() });
            }
            desF.release(); desM.release();

            if (srcList.size() < 10) return new ArrayList<>();

            Mat srcMat = pointsToMat(srcList);
            Mat dstMat = pointsToMat(dstList);
            Mat inlierMask = new Mat();
            Mat affine = opencv_calib3d.estimateAffine2D(srcMat, dstMat, inlierMask,
                    opencv_calib3d.RANSAC, cfg.ransacReprojThresholdPx, cfg.ransacMaxIters, 0.999, 10);
            srcMat.release(); dstMat.release();

            List<double[][]> result = new ArrayList<>();
            if (affine != null && !affine.empty()) {
                try (UByteIndexer idx = inlierMask.createIndexer()) {
                    int n = (int) Math.min(inlierMask.rows(), srcList.size());
                    for (int i = 0; i < n; i++) {
                        if (idx.get(i, 0) != 0) {
                            float[] s = srcList.get(i);
                            float[] d = dstList.get(i);
                            result.add(new double[][] {
                                    new double[] { s[0], s[1] },
                                    new double[] { d[0], d[1] }
                            });
                        }
                    }
                } catch (Exception e) {
                    // fallthrough — return whatever we have
                }
                affine.release();
            }
            inlierMask.release();
            return result;
        } finally {
            fixMat.release();
            movMat.release();
        }
    }

    /** Final RANSAC affine across all collected global points. */
    private static double[][] ransacAffineFinal(List<double[]> src, List<double[]> dst, Config cfg) {
        Mat srcMat = doublesPointsToMat(src);
        Mat dstMat = doublesPointsToMat(dst);
        Mat inlierMask = new Mat();
        Mat affine = opencv_calib3d.estimateAffine2D(srcMat, dstMat, inlierMask,
                opencv_calib3d.RANSAC, cfg.ransacReprojThresholdPx, cfg.ransacMaxIters, 0.999, 10);
        if (affine == null || affine.empty()) {
            srcMat.release(); dstMat.release(); inlierMask.release();
            throw new RuntimeException("Stage 3 final RANSAC failed");
        }
        double[][] result;
        try (DoubleIndexer idx = affine.createIndexer()) {
            result = new double[][] {
                    { idx.get(0, 0), idx.get(0, 1), idx.get(0, 2) },
                    { idx.get(1, 0), idx.get(1, 1), idx.get(1, 2) },
                    { 0,             0,             1            }
            };
        } catch (Exception e) {
            throw new RuntimeException("Failed reading refined affine", e);
        }
        srcMat.release(); dstMat.release(); affine.release(); inlierMask.release();
        return result;
    }

    private static double[] reprojErrors(double[][] m, List<double[]> src, List<double[]> dst) {
        // Re-evaluate inliers on the final model
        List<Double> errs = new ArrayList<>();
        double thr = 5.0;   // a bit looser than RANSAC threshold to capture "near-inliers"
        for (int i = 0; i < src.size(); i++) {
            double[] s = src.get(i);
            double[] d = dst.get(i);
            double xPred = m[0][0] * s[0] + m[0][1] * s[1] + m[0][2];
            double yPred = m[1][0] * s[0] + m[1][1] * s[1] + m[1][2];
            double dx = xPred - d[0];
            double dy = yPred - d[1];
            double e = Math.sqrt(dx * dx + dy * dy);
            if (e <= thr) errs.add(e);
        }
        double[] out = new double[errs.size()];
        for (int i = 0; i < out.length; i++) out[i] = errs.get(i);
        return out;
    }

    private static Mat toGrayMat(BufferedImage img) {
        BufferedImage gray = img;
        if (img.getType() != BufferedImage.TYPE_BYTE_GRAY) {
            gray = new BufferedImage(img.getWidth(), img.getHeight(), BufferedImage.TYPE_BYTE_GRAY);
            var g = gray.createGraphics();
            try { g.drawImage(img, 0, 0, null); } finally { g.dispose(); }
        }
        byte[] data = ((DataBufferByte) gray.getRaster().getDataBuffer()).getData();
        Mat mat = new Mat(gray.getHeight(), gray.getWidth(), CV_8UC1);
        mat.data().put(data);
        return mat;
    }

    private static Mat pointsToMat(List<float[]> pts) {
        Mat mat = new Mat(pts.size(), 1, CV_32FC2);
        try (FloatIndexer idx = mat.createIndexer()) {
            for (int i = 0; i < pts.size(); i++) {
                idx.put(i, 0, 0, pts.get(i)[0]);
                idx.put(i, 0, 1, pts.get(i)[1]);
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        return mat;
    }

    private static Mat doublesPointsToMat(List<double[]> pts) {
        Mat mat = new Mat(pts.size(), 1, CV_32FC2);
        try (FloatIndexer idx = mat.createIndexer()) {
            for (int i = 0; i < pts.size(); i++) {
                idx.put(i, 0, 0, (float) pts.get(i)[0]);
                idx.put(i, 0, 1, (float) pts.get(i)[1]);
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        return mat;
    }

    private static double[][] inverseAffine(double[][] m) {
        double a = m[0][0], b = m[0][1], tx = m[0][2];
        double c = m[1][0], d = m[1][1], ty = m[1][2];
        double det = a * d - b * c;
        if (Math.abs(det) < 1e-12) throw new RuntimeException("Affine is singular");
        double inv = 1.0 / det;
        return new double[][] {
                {  d * inv, -b * inv, (b * ty - d * tx) * inv },
                { -c * inv,  a * inv, (c * tx - a * ty) * inv },
                {  0,        0,        1                       }
        };
    }

    private static double[] applyAffine(double[][] m, double x, double y) {
        return new double[] {
                m[0][0] * x + m[0][1] * y + m[0][2],
                m[1][0] * x + m[1][1] * y + m[1][2]
        };
    }

    private static int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    private static double min4(double a, double b, double c, double d) {
        return Math.min(Math.min(a, b), Math.min(c, d));
    }

    private static double max4(double a, double b, double c, double d) {
        return Math.max(Math.max(a, b), Math.max(c, d));
    }
}
