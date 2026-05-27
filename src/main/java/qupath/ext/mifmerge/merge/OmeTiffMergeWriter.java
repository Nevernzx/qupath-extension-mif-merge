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
        public int tileSize = 512;
        /** If null, use dyadic (1x, 2x, 4x, ...) downsamples; otherwise explicit list. */
        public double[] downsamples = null;
        public OMEPyramidWriter.CompressionType compression = OMEPyramidWriter.CompressionType.LZW;
        public boolean parallelize = true;
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
        if (opts.parallelize) {
            builder.parallelize();
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
