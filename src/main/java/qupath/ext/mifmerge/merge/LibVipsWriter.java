package qupath.ext.mifmerge.merge;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.lib.images.writers.ome.OMEPyramidWriter;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Final-stage OME-TIFF writer that delegates compression + pyramid building to
 * {@code vips tiffsave} (a libvips CLI command).
 *
 * <p>The Java side first writes a single-level uncompressed BigTIFF intermediate
 * (cheap — no pyramid work, no compression work, just raw tiles + warp). Then
 * we shell out to vips which uses libjpeg-turbo, libopenjp2 etc. to do the
 * pyramid + compression at native speed. For a typical Vectra qptiff pair this
 * is roughly 5-10x faster end-to-end than letting Bio-Formats do everything.
 *
 * <p>This class does NOT decide whether to use libvips — see
 * {@link OmeTiffMergeWriter}. Call {@link #isVipsAvailable()} before relying on
 * this writer.
 */
public final class LibVipsWriter {

    private static final Logger logger = LoggerFactory.getLogger(LibVipsWriter.class);

    private LibVipsWriter() {}

    /**
     * Check whether {@code vips} is on the system PATH and reports a usable
     * version. Cheap (~50 ms); call once at startup or before each write.
     *
     * @return version string (e.g. "8.16.0") on success; null if vips is not
     *         found or doesn't respond
     */
    public static String detectVipsVersion() {
        try {
            ProcessBuilder pb = new ProcessBuilder("vips", "--version");
            pb.redirectErrorStream(true);
            Process p = pb.start();
            String line;
            try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
                line = r.readLine();
            }
            boolean done = p.waitFor(5, TimeUnit.SECONDS);
            if (!done) {
                p.destroyForcibly();
                return null;
            }
            if (p.exitValue() != 0) {
                return null;
            }
            // Expected: "vips-8.16.0" or similar
            if (line != null && line.toLowerCase().contains("vips")) {
                return line.trim();
            }
            return null;
        } catch (Exception e) {
            return null;
        }
    }

    public static boolean isVipsAvailable() {
        return detectVipsVersion() != null;
    }

    /**
     * Convert an intermediate single-level uncompressed BigTIFF into the final
     * pyramidal compressed OME-TIFF using {@code vips tiffsave}.
     *
     * @param intermediatePath path to the uncompressed BigTIFF the Java side wrote
     * @param outputPath       desired final .ome.tif path
     * @param opts             options from {@link OmeTiffMergeWriter.Options} —
     *                         we map {@code compression}, {@code tileSize},
     *                         {@code pyramidMode} onto the corresponding vips flags
     * @param logSink          optional callback receiving every line vips emits
     * @param processRegistrar optional callback called once with the live
     *                         {@link Process} as soon as vips starts; allows
     *                         the caller to {@code destroy()} it on cancel
     */
    public static void runVipsTiffSave(Path intermediatePath, Path outputPath,
                                       OmeTiffMergeWriter.Options opts,
                                       Consumer<String> logSink,
                                       Consumer<Process> processRegistrar)
            throws IOException, InterruptedException {
        if (!Files.exists(intermediatePath)) {
            throw new IOException("Intermediate file does not exist: " + intermediatePath);
        }

        List<String> cmd = new ArrayList<>();
        cmd.add("vips");
        cmd.add("tiffsave");
        cmd.add(intermediatePath.toAbsolutePath().toString());
        cmd.add(outputPath.toAbsolutePath().toString());

        // --tile / tile-width / tile-height
        cmd.add("--tile");
        cmd.add("--tile-width=" + opts.tileSize);
        cmd.add("--tile-height=" + opts.tileSize);

        // Pyramid
        switch (opts.pyramidMode) {
            case DYADIC:
            case SPARSE:   // vips tiffsave only supports dyadic; SPARSE → DYADIC
                cmd.add("--pyramid");
                break;
            case SINGLE:
                // No --pyramid flag = single-level output
                break;
        }

        // Compression
        cmd.add("--compression=" + mapCompression(opts.compression));
        // Quality for lossy modes
        if (opts.compression == OMEPyramidWriter.CompressionType.J2K_LOSSY
                || opts.compression == OMEPyramidWriter.CompressionType.JPEG) {
            cmd.add("--Q=85");
        }

        if (opts.bigTiff) {
            cmd.add("--bigtiff");
        }

        // Don't strip OME-XML metadata
        // (vips by default strips most metadata; we rely on the intermediate
        // file's OME-XML to survive)
        cmd.add("--keep=all");

        logSink.accept("    vips command: " + String.join(" ", cmd));

        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.redirectErrorStream(true);
        long t0 = System.currentTimeMillis();
        Process p = pb.start();
        if (processRegistrar != null) {
            processRegistrar.accept(p);
        }

        // Stream vips output to the log sink
        try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
            String line;
            while ((line = r.readLine()) != null) {
                logSink.accept("    vips: " + line);
            }
        }

        int exit = p.waitFor();
        long elapsed = (System.currentTimeMillis() - t0) / 1000;
        if (exit != 0) {
            // 137 = killed, 143 = SIGTERM, others = real error
            if (exit == 137 || exit == 143 || Thread.currentThread().isInterrupted()) {
                throw new InterruptedException("vips was killed (exit " + exit + ")");
            }
            throw new IOException("vips tiffsave failed with exit code " + exit
                    + " after " + elapsed + "s");
        }
        logger.info("vips tiffsave finished in {} s ({} → {})", elapsed,
                intermediatePath.getFileName(), outputPath.getFileName());
        logSink.accept(String.format("    vips finished in %d s", elapsed));
    }

    /**
     * Map QuPath's {@link OMEPyramidWriter.CompressionType} to vips' compression names.
     * See https://www.libvips.org/API/current/VipsForeignSave.html for vips options.
     */
    private static String mapCompression(OMEPyramidWriter.CompressionType c) {
        if (c == null) return "lzw";
        switch (c) {
            case UNCOMPRESSED:
                return "none";
            case LZW:
                return "lzw";
            case ZLIB:
                return "deflate";
            case JPEG:
                return "jpeg";
            case J2K:
            case J2K_LOSSY:
                return "jp2k";
            case DEFAULT:
            default:
                return "lzw";
        }
    }
}
