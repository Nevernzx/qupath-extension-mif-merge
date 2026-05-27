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

import static org.bytedeco.opencv.global.opencv_core.CV_32F;
import static org.bytedeco.opencv.global.opencv_core.CV_32FC2;
import static org.bytedeco.opencv.global.opencv_core.CV_8UC1;
import static org.bytedeco.opencv.global.opencv_core.NORM_L2;

/**
 * Port of {@code sift_ransac_affine_cv2} from register_batch.py.
 *
 * <p>Pipeline:
 * <ol>
 *   <li>SIFT keypoint+descriptor detection on both images.</li>
 *   <li>{@link BFMatcher} L2 kNN match (k=2) descriptors_moving -> descriptors_fixed.</li>
 *   <li>Lowe ratio test (default 0.75).</li>
 *   <li>Optional pre-filter: project each moving keypoint through
 *       {@link Params#mInit} and discard pairs whose distance to the matched
 *       fixed keypoint exceeds {@link Params#prefilterRadiusPx}.</li>
 *   <li>OpenCV {@code estimateAffine2D} (full 6-DOF RANSAC),
 *       then re-evaluate inliers and reprojection error.</li>
 * </ol>
 */
public final class SiftRansacAffine {

    private static final Logger logger = LoggerFactory.getLogger(SiftRansacAffine.class);

    private SiftRansacAffine() {}

    public static final class Params {
        public int nFeatures = 60_000;
        /** OpenCV SIFT defaults — match cv2.SIFT_create() Python defaults. */
        public int nOctaveLayers = 3;
        public double contrastThreshold = 0.04;
        public double edgeThreshold = 10.0;
        public double sigma = 1.6;

        public double loweRatio = 0.75;
        /** If non-null, used to pre-filter matches by predicted reprojection distance. */
        public double[][] mInit = null;
        public double prefilterRadiusPx = 30.0;
        public double ransacReprojThresholdPx = 3.0;
        public int ransacMaxIters = 5000;
        public double ransacConfidence = 0.999;

        public Params nFeatures(int n) { this.nFeatures = n; return this; }
        public Params loweRatio(double r) { this.loweRatio = r; return this; }
        public Params mInit(double[][] m) { this.mInit = m; return this; }
        public Params prefilterRadiusPx(double r) { this.prefilterRadiusPx = r; return this; }
        public Params ransacReprojThresholdPx(double t) { this.ransacReprojThresholdPx = t; return this; }
        public Params ransacMaxIters(int n) { this.ransacMaxIters = n; return this; }
    }

    public static RegistrationResult run(BufferedImage fixed, BufferedImage moving, Params params) {
        Mat fixedMat = toGrayMat(fixed);
        Mat movingMat = toGrayMat(moving);
        try {
            return run(fixedMat, movingMat, params);
        } finally {
            fixedMat.release();
            movingMat.release();
        }
    }

    public static RegistrationResult run(Mat fixed, Mat moving, Params params) {
        if (params == null) {
            params = new Params();
        }
        // 6-arg create signature (nfeatures, nOctaveLayers, contrastThreshold,
        // edgeThreshold, sigma, descriptorType) is the most portable variant
        // — present in OpenCV 4.5 through 4.13. The 5-arg version was removed
        // in 4.13. CV_32F is the default descriptor type (32-bit float).
        try (SIFT sift = SIFT.create(
                params.nFeatures,
                params.nOctaveLayers,
                params.contrastThreshold,
                params.edgeThreshold,
                params.sigma,
                CV_32F)) {
            long t0 = System.currentTimeMillis();
            KeyPointVector kpFixed = new KeyPointVector();
            KeyPointVector kpMoving = new KeyPointVector();
            Mat desFixed = new Mat();
            Mat desMoving = new Mat();
            sift.detectAndCompute(fixed, new Mat(), kpFixed, desFixed);
            sift.detectAndCompute(moving, new Mat(), kpMoving, desMoving);
            long tDetect = System.currentTimeMillis() - t0;

            int nKpF = (int) kpFixed.size();
            int nKpM = (int) kpMoving.size();
            if (nKpF < 10 || nKpM < 10) {
                throw new IllegalStateException(
                        "insufficient keypoints (fixed=" + nKpF + ", moving=" + nKpM + ")");
            }

            t0 = System.currentTimeMillis();
            DMatchVectorVector knn = new DMatchVectorVector();
            try (BFMatcher matcher = new BFMatcher(NORM_L2, false)) {
                matcher.knnMatch(desMoving, desFixed, knn, 2);
            }
            long tMatch = System.currentTimeMillis() - t0;

            List<float[]> srcList = new ArrayList<>();
            List<float[]> dstList = new ArrayList<>();
            int nAfterLowe = 0;
            int nAfterPrefilter = 0;
            double[][] mInit = params.mInit;
            double radiusSq = params.prefilterRadiusPx * params.prefilterRadiusPx;

            for (long i = 0; i < knn.size(); i++) {
                DMatchVector pair = knn.get(i);
                if (pair.size() < 2) {
                    continue;
                }
                DMatch m1 = pair.get(0);
                DMatch m2 = pair.get(1);
                if (m1.distance() > params.loweRatio * m2.distance()) {
                    continue;
                }
                nAfterLowe++;

                KeyPoint kM = kpMoving.get(m1.queryIdx());
                KeyPoint kF = kpFixed.get(m1.trainIdx());
                float xm = kM.pt().x();
                float ym = kM.pt().y();
                float xf = kF.pt().x();
                float yf = kF.pt().y();

                if (mInit != null) {
                    double xPred = mInit[0][0] * xm + mInit[0][1] * ym + mInit[0][2];
                    double yPred = mInit[1][0] * xm + mInit[1][1] * ym + mInit[1][2];
                    double dx = xPred - xf;
                    double dy = yPred - yf;
                    if (dx * dx + dy * dy > radiusSq) {
                        continue;
                    }
                }
                nAfterPrefilter++;
                srcList.add(new float[] {xm, ym});
                dstList.add(new float[] {xf, yf});
            }

            if (srcList.size() < 10) {
                throw new IllegalStateException(
                        "too few matches after filtering: " + srcList.size());
            }

            t0 = System.currentTimeMillis();
            Mat srcMat = pointsToMat(srcList);
            Mat dstMat = pointsToMat(dstList);
            Mat inlierMask = new Mat();
            Mat affine = opencv_calib3d.estimateAffine2D(
                    srcMat, dstMat, inlierMask,
                    opencv_calib3d.RANSAC,
                    params.ransacReprojThresholdPx,
                    params.ransacMaxIters,
                    params.ransacConfidence,
                    10);
            long tRansac = System.currentTimeMillis() - t0;

            if (affine == null || affine.empty()) {
                throw new IllegalStateException("RANSAC failed to estimate affine");
            }

            double[][] m3x3 = read2x3As3x3(affine);
            int[] inliers = readInlierMask(inlierMask, srcList.size());
            double[] errs = reprojErrors(m3x3, srcList, dstList, inliers);
            Arrays.sort(errs);
            double median = percentileSorted(errs, 50);
            double p95 = percentileSorted(errs, 95);
            double max = errs.length == 0 ? 0.0 : errs[errs.length - 1];
            int nInliers = errs.length;
            double inlierRatio = (double) nInliers / Math.max(1, srcList.size());

            logger.info("SIFT kp f={}/m={} | Lowe-> {} | prefilter-> {} | RANSAC inliers {}/{} ({}%) "
                            + "| reproj median {} p95 {} px | detect {}ms match {}ms ransac {}ms",
                    nKpF, nKpM, nAfterLowe, nAfterPrefilter, nInliers, srcList.size(),
                    String.format("%.1f", 100.0 * inlierRatio),
                    String.format("%.2f", median), String.format("%.2f", p95),
                    tDetect, tMatch, tRansac);

            srcMat.release();
            dstMat.release();
            inlierMask.release();
            affine.release();
            desFixed.release();
            desMoving.release();

            return new RegistrationResult(
                    MatrixRescaler.toAffineTransform(m3x3),
                    m3x3,
                    nKpF, nKpM,
                    nAfterLowe, nAfterPrefilter,
                    nInliers, inlierRatio,
                    median, p95, max,
                    tDetect, tMatch, tRansac);
        }
    }

    public static Mat toGrayMat(BufferedImage img) {
        BufferedImage gray = img;
        if (img.getType() != BufferedImage.TYPE_BYTE_GRAY) {
            gray = new BufferedImage(img.getWidth(), img.getHeight(),
                    BufferedImage.TYPE_BYTE_GRAY);
            var g = gray.createGraphics();
            try {
                g.drawImage(img, 0, 0, null);
            } finally {
                g.dispose();
            }
        }
        byte[] data = ((DataBufferByte) gray.getRaster().getDataBuffer()).getData();
        Mat mat = new Mat(gray.getHeight(), gray.getWidth(), CV_8UC1);
        mat.data().put(data);
        return mat;
    }

    /**
     * Convert N (x, y) pairs to an Nx1 CV_32FC2 matrix, the input form
     * expected by {@link opencv_calib3d#estimateAffine2D}.
     */
    private static Mat pointsToMat(List<float[]> pts) {
        Mat mat = new Mat(pts.size(), 1, CV_32FC2);
        try (FloatIndexer idx = mat.createIndexer()) {
            for (int i = 0; i < pts.size(); i++) {
                idx.put(i, 0, 0, pts.get(i)[0]);
                idx.put(i, 0, 1, pts.get(i)[1]);
            }
        } catch (Exception e) {
            throw new RuntimeException("Failed to populate point matrix", e);
        }
        return mat;
    }

    private static double[][] read2x3As3x3(Mat affine2x3) {
        try (DoubleIndexer idx = affine2x3.createIndexer()) {
            return new double[][] {
                    { idx.get(0, 0), idx.get(0, 1), idx.get(0, 2) },
                    { idx.get(1, 0), idx.get(1, 1), idx.get(1, 2) },
                    {  0,             0,             1            }
            };
        } catch (Exception e) {
            throw new RuntimeException("Failed to read 2x3 affine matrix", e);
        }
    }

    private static int[] readInlierMask(Mat mask, int expectedSize) {
        try (UByteIndexer idx = mask.createIndexer()) {
            int n = (int) Math.min(mask.rows(), expectedSize);
            int count = 0;
            boolean[] keep = new boolean[n];
            for (int i = 0; i < n; i++) {
                boolean inlier = idx.get(i, 0) != 0;
                keep[i] = inlier;
                if (inlier) count++;
            }
            int[] inlierIdx = new int[count];
            int j = 0;
            for (int i = 0; i < n; i++) {
                if (keep[i]) inlierIdx[j++] = i;
            }
            return inlierIdx;
        } catch (Exception e) {
            throw new RuntimeException("Failed to read inlier mask", e);
        }
    }

    private static double[] reprojErrors(double[][] m,
                                         List<float[]> src,
                                         List<float[]> dst,
                                         int[] inlierIdx) {
        double[] errs = new double[inlierIdx.length];
        for (int i = 0; i < inlierIdx.length; i++) {
            float[] s = src.get(inlierIdx[i]);
            float[] d = dst.get(inlierIdx[i]);
            double xPred = m[0][0] * s[0] + m[0][1] * s[1] + m[0][2];
            double yPred = m[1][0] * s[0] + m[1][1] * s[1] + m[1][2];
            double dx = xPred - d[0];
            double dy = yPred - d[1];
            errs[i] = Math.sqrt(dx * dx + dy * dy);
        }
        return errs;
    }

    private static double percentileSorted(double[] sorted, double p) {
        if (sorted.length == 0) return 0;
        double rank = (p / 100.0) * (sorted.length - 1);
        int lo = (int) Math.floor(rank);
        int hi = (int) Math.ceil(rank);
        if (lo == hi) return sorted[lo];
        return sorted[lo] + (rank - lo) * (sorted[hi] - sorted[lo]);
    }
}
