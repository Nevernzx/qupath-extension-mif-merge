package qupath.ext.mifmerge.merge;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.lib.images.servers.ImageServer;
import qupath.lib.images.writers.ome.OMEPyramidWriter;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Thin wrapper around QuPath's {@link OMEPyramidWriter.Builder} with defaults
 * tuned for multi-channel mIF WSIs: 512-px tiles, dyadic pyramid, LZW
 * compression, parallel writes.
 *
 * <p>Output is a single pyramidal OME-TIFF that QuPath (and ImageJ/Bio-Formats
 * downstream) can open directly.
 */
public final class OmeTiffMergeWriter {

    private static final Logger logger = LoggerFactory.getLogger(OmeTiffMergeWriter.class);

    /**
     * Controls how many pyramid levels are emitted. More levels = smoother
     * QuPath zoom navigation but more work to write.
     */
    public enum PyramidMode {
        /** 1x, 2x, 4x, 8x, 16x, 32x — full dyadic pyramid, total work ~133% of level 0. */
        DYADIC,
        /** 1x, 4x, 16x — sparse, total work ~107% of level 0. Zoom in QuPath has some lag. */
        SPARSE,
        /** Only full resolution (1x), total work ~75% of dyadic. QuPath zoom feels sluggish. */
        SINGLE
    }

    /**
     * Which library actually compresses + writes the final OME-TIFF.
     */
    public enum WriterBackend {
        /**
         * Use libvips ({@code vips tiffsave}) for the final compression + pyramid
         * generation. Java side writes a single-level uncompressed BigTIFF
         * intermediate, then shells out to vips to produce the final file.
         *
         * <p>Native code throughout — typically 5-10x faster than going through
         * QuPath's Bio-Formats writer end-to-end. Requires {@code vips} on PATH
         * and ~60 GB free disk for the intermediate.
         *
         * <p>Falls back to {@link #BIO_FORMATS} if {@code vips} is not found.
         */
        LIBVIPS,
        /**
         * Use QuPath's {@link OMEPyramidWriter} for everything. No external
         * dependencies. Slower for big files but always works.
         */
        BIO_FORMATS
    }

    public static final class Options {
        /**
         * Output tile size. 512 was the previous default; in practice that caused
         * the write to be dominated by per-tile coordination overhead (a 50000 px
         * WSI has 10000+ tiles × N channels × ~5 pyramid levels = millions of
         * tile coordinate calls into Bio-Formats + TransformedServerBuilder).
         * 1024 is a much better default — 4x fewer tiles, similar per-pixel work.
         */
        public int tileSize = 1024;
        /** Explicit pyramid downsamples; if non-null, overrides {@link #pyramidMode}. */
        public double[] downsamples = null;
        public PyramidMode pyramidMode = PyramidMode.DYADIC;
        public OMEPyramidWriter.CompressionType compression = OMEPyramidWriter.CompressionType.LZW;
        /**
         * Number of writer threads.
         *  - 1: serial, lowest memory
         *  - 2-4: typical sweet spot for desktop machines (each thread holds tile
         *    buffers for fixed + all moving channels, so cost scales linearly)
         *  - 0 or negative: use {@link Runtime#availableProcessors()}, the old
         *    'parallelize()' default — on 8-core systems that can hold ~1-2 GB of
         *    tile data in flight at any time.
         */
        public int nWriteThreads = 2;
        public boolean bigTiff = true;
        /** Which library actually compresses + writes the final OME-TIFF. */
        public WriterBackend backend = WriterBackend.LIBVIPS;
        /**
         * Where to put the intermediate uncompressed BigTIFF when {@link #backend}
         * is {@link WriterBackend#LIBVIPS}. Null = same directory as the output.
         */
        public String libvipsIntermediateDir = null;
        /**
         * If true, keep the intermediate file even after vips finishes (for debugging).
         */
        public boolean keepLibvipsIntermediate = false;
    }

    private OmeTiffMergeWriter() {}

    public static void write(ImageServer<BufferedImage> server, String outputPath, Options opts)
            throws IOException {
        write(server, outputPath, opts, null);
    }

    /**
     * @param progressLog optional callback (typically the GUI text area) used
     *                    to surface intermediate progress messages, especially
     *                    when the libvips backend is in use.
     */
    public static void write(ImageServer<BufferedImage> server, String outputPath, Options opts,
                             java.util.function.Consumer<String> progressLog)
            throws IOException {
        if (opts == null) opts = new Options();
        java.util.function.Consumer<String> log = progressLog != null ? progressLog : s -> {};

        // Decide which backend to actually use. LIBVIPS requires vips on PATH;
        // fall back to BIO_FORMATS with a warning if not.
        WriterBackend backend = opts.backend;
        if (backend == WriterBackend.LIBVIPS) {
            String version = LibVipsWriter.detectVipsVersion();
            if (version == null) {
                log.accept("  libvips not found on PATH — falling back to Bio-Formats writer.");
                logger.warn("Requested libvips backend but vips is not available; falling back to Bio-Formats");
                backend = WriterBackend.BIO_FORMATS;
            } else {
                log.accept("  Using libvips backend: " + version);
            }
        }

        if (backend == WriterBackend.LIBVIPS) {
            writeWithLibVips(server, outputPath, opts, log);
        } else {
            writeWithBioFormats(server, outputPath, opts);
        }
    }

    /**
     * Legacy path: let OMEPyramidWriter do everything (read → warp → compress → pyramid → disk).
     * Same as the original write() implementation, ~5 hours on a Vectra qptiff pair.
     */
    private static void writeWithBioFormats(ImageServer<BufferedImage> server, String outputPath,
                                            Options opts) throws IOException {
        logger.info("Writing OME-TIFF (Bio-Formats backend): {} ({}x{} px, {} channels, {} pyramid levels)",
                outputPath, server.getWidth(), server.getHeight(),
                server.nChannels(), server.nResolutions());

        OMEPyramidWriter.Builder builder = new OMEPyramidWriter.Builder(server)
                .tileSize(opts.tileSize)
                .compression(opts.compression);

        if (opts.downsamples != null) {
            builder.downsamples(opts.downsamples);
            logger.info("Pyramid: explicit downsamples = {}", java.util.Arrays.toString(opts.downsamples));
        } else {
            switch (opts.pyramidMode) {
                case DYADIC:
                    builder.dyadicDownsampling();
                    logger.info("Pyramid: dyadic (1, 2, 4, 8, 16, 32, ...)");
                    break;
                case SPARSE:
                    builder.downsamples(1.0, 4.0, 16.0);
                    logger.info("Pyramid: sparse (1, 4, 16)");
                    break;
                case SINGLE:
                    builder.downsamples(1.0);
                    logger.info("Pyramid: single level (full-res only, no pyramid)");
                    break;
            }
        }
        applyWriterThreads(builder, opts);
        if (opts.bigTiff) {
            builder.bigTiff();
        }

        long t0 = System.currentTimeMillis();
        OMEPyramidWriter.createWriter(builder.build()).writeImage(outputPath);
        long elapsed = System.currentTimeMillis() - t0;
        logger.info("Wrote OME-TIFF in {} ms ({}s)", elapsed, elapsed / 1000.0);
    }

    /**
     * Two-step path: write a single-level uncompressed BigTIFF intermediate
     * via Bio-Formats (cheap — no pyramid, no compression), then let
     * {@code vips tiffsave} produce the final pyramidal compressed OME-TIFF
     * using native libjpeg-turbo / libopenjp2 / SIMD. Typical wall-clock
     * speedup vs straight Bio-Formats: 5-10x.
     */
    private static void writeWithLibVips(ImageServer<BufferedImage> server, String outputPath,
                                         Options opts,
                                         java.util.function.Consumer<String> log) throws IOException {
        Path output = Path.of(outputPath).toAbsolutePath();
        Path intermediateDir = opts.libvipsIntermediateDir != null
                ? Path.of(opts.libvipsIntermediateDir)
                : output.getParent();
        if (intermediateDir == null) {
            intermediateDir = Path.of(".");
        }
        Files.createDirectories(intermediateDir);
        String stem = output.getFileName().toString();
        if (stem.toLowerCase().endsWith(".ome.tif")) stem = stem.substring(0, stem.length() - 8);
        else if (stem.toLowerCase().endsWith(".ome.tiff")) stem = stem.substring(0, stem.length() - 9);
        else if (stem.toLowerCase().endsWith(".tif")) stem = stem.substring(0, stem.length() - 4);
        else if (stem.toLowerCase().endsWith(".tiff")) stem = stem.substring(0, stem.length() - 5);
        Path intermediate = intermediateDir.resolve(stem + ".intermediate.tif");

        // Step 1: write uncompressed single-level intermediate
        log.accept(String.format("  libvips step 1/2: writing uncompressed intermediate to %s",
                intermediate.getFileName()));
        long checkpointMb = freeMb(intermediateDir);
        log.accept(String.format("  intermediate dir has %.1f GB free", checkpointMb / 1024.0));

        Options intermediateOpts = new Options();
        intermediateOpts.tileSize = opts.tileSize;
        intermediateOpts.pyramidMode = PyramidMode.SINGLE;
        intermediateOpts.compression = OMEPyramidWriter.CompressionType.UNCOMPRESSED;
        intermediateOpts.nWriteThreads = opts.nWriteThreads;
        intermediateOpts.bigTiff = true;
        intermediateOpts.backend = WriterBackend.BIO_FORMATS;

        long t0 = System.currentTimeMillis();
        writeWithBioFormats(server, intermediate.toString(), intermediateOpts);
        long step1 = (System.currentTimeMillis() - t0) / 1000;
        log.accept(String.format("  libvips step 1/2 done in %d s (intermediate is %.2f GB)",
                step1, intermediate.toFile().length() / 1024.0 / 1024.0 / 1024.0));

        // Step 2: vips tiffsave for final compression + pyramid
        log.accept("  libvips step 2/2: vips tiffsave (compression + pyramid)…");
        long t1 = System.currentTimeMillis();
        try {
            LibVipsWriter.runVipsTiffSave(intermediate, output, opts, log);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while waiting for vips", ie);
        }
        long step2 = (System.currentTimeMillis() - t1) / 1000;
        log.accept(String.format("  libvips step 2/2 done in %d s", step2));

        // Step 3: cleanup
        if (!opts.keepLibvipsIntermediate) {
            try {
                Files.deleteIfExists(intermediate);
                log.accept("  cleaned up intermediate file");
            } catch (IOException e) {
                log.accept("  warning: could not delete intermediate " + intermediate + " — " + e.getMessage());
            }
        }

        long total = step1 + step2;
        logger.info("libvips write complete: step1={}s, step2={}s, total={}s", step1, step2, total);
        log.accept(String.format("  libvips total: %d s (step1=%d s, step2=%d s)", total, step1, step2));
    }

    private static void applyWriterThreads(OMEPyramidWriter.Builder builder, Options opts) {
        if (opts.nWriteThreads <= 0) {
            builder.parallelize();
        } else if (opts.nWriteThreads == 1) {
            builder.parallelize(false);
        } else {
            builder.parallelize(opts.nWriteThreads);
        }
    }

    private static long freeMb(Path dir) {
        try {
            long bytes = Files.getFileStore(dir).getUsableSpace();
            return bytes / 1024 / 1024;
        } catch (Exception e) {
            return -1;
        }
    }
}
