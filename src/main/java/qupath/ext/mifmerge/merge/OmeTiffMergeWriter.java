package qupath.ext.mifmerge.merge;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.lib.images.servers.ImageServer;
import qupath.lib.images.writers.ome.OMEPyramidWriter;

import java.awt.image.BufferedImage;
import java.io.IOException;

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

    public static final class Options {
        /**
         * Output tile size. 512 was the previous default; in practice that caused
         * the write to be dominated by per-tile coordination overhead (a 50000 px
         * WSI has 10000+ tiles × N channels × ~5 pyramid levels = millions of
         * tile coordinate calls into Bio-Formats + TransformedServerBuilder).
         * 1024 is a much better default — 4x fewer tiles, similar per-pixel work.
         */
        public int tileSize = 1024;
        /** If null, use dyadic (1x, 2x, 4x, ...) downsamples; otherwise explicit list. */
        public double[] downsamples = null;
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
    }

    private OmeTiffMergeWriter() {}

    public static void write(ImageServer<BufferedImage> server, String outputPath, Options opts)
            throws IOException {
        if (opts == null) opts = new Options();

        logger.info("Writing OME-TIFF: {} ({}x{} px, {} channels, {} pyramid levels)",
                outputPath, server.getWidth(), server.getHeight(),
                server.nChannels(), server.nResolutions());

        OMEPyramidWriter.Builder builder = new OMEPyramidWriter.Builder(server)
                .tileSize(opts.tileSize)
                .compression(opts.compression);

        if (opts.downsamples != null) {
            builder.downsamples(opts.downsamples);
        } else {
            builder.dyadicDownsampling();
        }
        if (opts.nWriteThreads <= 0) {
            // Legacy "use all cores" behaviour
            builder.parallelize();
            logger.info("OME-TIFF writer threads: availableProcessors ({})",
                    Runtime.getRuntime().availableProcessors());
        } else if (opts.nWriteThreads == 1) {
            builder.parallelize(false);
            logger.info("OME-TIFF writer threads: 1 (serial)");
        } else {
            builder.parallelize(opts.nWriteThreads);
            logger.info("OME-TIFF writer threads: {}", opts.nWriteThreads);
        }
        if (opts.bigTiff) {
            builder.bigTiff();
        }

        long t0 = System.currentTimeMillis();
        OMEPyramidWriter.createWriter(builder.build()).writeImage(outputPath);
        long elapsed = System.currentTimeMillis() - t0;
        logger.info("Wrote OME-TIFF in {} ms ({}s)", elapsed, elapsed / 1000.0);
    }
}
