package qupath.ext.mifmerge.merge;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.lib.images.writers.ome.OMEPyramidWriter;

import java.awt.geom.AffineTransform;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Writer backend that delegates the entire Phase B (read sources → warp →
 * bandjoin → tiffsave with pyramid + compression) to a pyvips Python script.
 * No intermediate file is materialised on disk: vips streams pixels from
 * libtiff/OpenSlide through its lazy image graph straight into the final
 * OME-TIFF tiles.
 *
 * <p>Requires Python 3 with the {@code pyvips} package and libvips on PATH.
 *
 * <p>Note: this backend completely bypasses QuPath's {@code TransformedServerBuilder}
 * and {@code OMEPyramidWriter}. The Java side only computes the affine matrices
 * (Phase A, unchanged) and constructs the recipe JSON; everything else is
 * native code inside vips.
 */
public final class PyVipsWriter {

    private static final Logger logger = LoggerFactory.getLogger(PyVipsWriter.class);

    private PyVipsWriter() {}

    /**
     * Describes one source channel for the recipe.
     */
    public static final class ChannelSpec {
        public final int page;
        public final String name;
        public final boolean isDapi;
        public final boolean include;
        public final String outputName;
        public ChannelSpec(int page, String name, boolean isDapi, boolean include, String outputName) {
            this.page = page; this.name = name; this.isDapi = isDapi;
            this.include = include; this.outputName = outputName;
        }
    }

    public static final class SourceSpec {
        public final String path;
        public final List<ChannelSpec> channels;
        /** Affine matrix (3x3 row-major), only for moving sources; null for fixed. */
        public final double[][] matrix;
        public SourceSpec(String path, List<ChannelSpec> channels, double[][] matrix) {
            this.path = path; this.channels = channels; this.matrix = matrix;
        }
    }

    public static final class Recipe {
        public final SourceSpec fixed;
        public final List<SourceSpec> movings;
        public final String outputPath;
        public final OMEPyramidWriter.CompressionType compression;
        public final int tileSize;
        public final OmeTiffMergeWriter.PyramidMode pyramidMode;
        public Recipe(SourceSpec fixed, List<SourceSpec> movings, String outputPath,
                      OMEPyramidWriter.CompressionType compression,
                      int tileSize, OmeTiffMergeWriter.PyramidMode pyramidMode) {
            this.fixed = fixed; this.movings = movings; this.outputPath = outputPath;
            this.compression = compression; this.tileSize = tileSize; this.pyramidMode = pyramidMode;
        }
    }

    /** Detect whether {@code python -c "import pyvips"} succeeds. Returns version or null. */
    public static String detectPythonPyVips() {
        try {
            ProcessBuilder pb = new ProcessBuilder(
                    "python", "-c",
                    "import pyvips, sys; sys.stdout.write(pyvips.__version__)");
            pb.redirectErrorStream(true);
            Process p = pb.start();
            String line;
            try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
                line = r.readLine();
            }
            boolean done = p.waitFor(10, TimeUnit.SECONDS);
            if (!done) {
                p.destroyForcibly();
                return null;
            }
            if (p.exitValue() != 0) {
                return null;
            }
            if (line == null) return null;
            return line.trim();
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Run the pyvips merger.
     *
     * @param recipe            full recipe
     * @param logSink           callback for progress lines
     * @param processRegistrar  optional, called once with the live python process
     */
    public static void run(Recipe recipe,
                           Consumer<String> logSink,
                           Consumer<Process> processRegistrar) throws IOException, InterruptedException {
        Path scriptPath = extractScriptToTemp();
        Path recipePath = writeRecipeJson(recipe);
        logSink.accept("  pyvips script: " + scriptPath);
        logSink.accept("  recipe: " + recipePath);

        ProcessBuilder pb = new ProcessBuilder("python",
                scriptPath.toAbsolutePath().toString(),
                recipePath.toAbsolutePath().toString());
        pb.redirectErrorStream(true);
        long t0 = System.currentTimeMillis();
        Process p = pb.start();
        if (processRegistrar != null) {
            processRegistrar.accept(p);
        }

        try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                logSink.accept("    " + line);
            }
        }

        int exit = p.waitFor();
        long elapsed = (System.currentTimeMillis() - t0) / 1000;
        // Best-effort cleanup of recipe (script we keep for inspection)
        try { Files.deleteIfExists(recipePath); } catch (IOException ignored) {}

        if (exit != 0) {
            if (exit == 137 || exit == 143 || Thread.currentThread().isInterrupted()) {
                throw new InterruptedException("python pyvips writer was killed (exit " + exit + ")");
            }
            throw new IOException("python pyvips writer failed with exit code " + exit
                    + " after " + elapsed + "s");
        }
        logger.info("pyvips writer finished in {} s", elapsed);
        logSink.accept(String.format("  pyvips finished in %d s", elapsed));
    }

    private static Path extractScriptToTemp() throws IOException {
        Path tempDir = Files.createTempDirectory("mif-merge-pyvips-");
        Path scriptPath = tempDir.resolve("merge_writer.py");
        try (InputStream in = PyVipsWriter.class.getResourceAsStream("/python/merge_writer.py")) {
            if (in == null) {
                throw new IOException("Couldn't find merge_writer.py in the extension jar");
            }
            Files.copy(in, scriptPath, StandardCopyOption.REPLACE_EXISTING);
        }
        return scriptPath;
    }

    private static Path writeRecipeJson(Recipe recipe) throws IOException {
        Path recipePath = Files.createTempFile("mif-merge-recipe-", ".json");
        StringBuilder sb = new StringBuilder();
        sb.append("{\n");
        sb.append("  \"fixed\": ").append(sourceJson(recipe.fixed)).append(",\n");
        sb.append("  \"movings\": [");
        for (int i = 0; i < recipe.movings.size(); i++) {
            if (i > 0) sb.append(",");
            sb.append("\n    ").append(sourceJson(recipe.movings.get(i)));
        }
        sb.append("\n  ],\n");
        sb.append("  \"output\": {\n");
        sb.append("    \"path\": ").append(quote(recipe.outputPath)).append(",\n");
        sb.append("    \"compression\": ").append(quote(mapCompressionToVips(recipe.compression))).append(",\n");
        sb.append("    \"quality\": 85,\n");
        sb.append("    \"tile_size\": ").append(recipe.tileSize).append(",\n");
        sb.append("    \"pyramid\": ").append(quote(mapPyramidToVips(recipe.pyramidMode))).append("\n");
        sb.append("  }\n");
        sb.append("}\n");
        Files.writeString(recipePath, sb.toString(), StandardCharsets.UTF_8);
        return recipePath;
    }

    private static String sourceJson(SourceSpec s) {
        StringBuilder sb = new StringBuilder();
        sb.append("{\"path\": ").append(quote(s.path)).append(", ");
        if (s.matrix != null) {
            sb.append("\"matrix\": ").append(matrixJson(s.matrix)).append(", ");
        } else {
            sb.append("\"matrix\": null, ");
        }
        sb.append("\"channels\": [");
        for (int i = 0; i < s.channels.size(); i++) {
            if (i > 0) sb.append(", ");
            ChannelSpec ch = s.channels.get(i);
            sb.append(String.format(Locale.ROOT,
                    "{\"page\": %d, \"name\": %s, \"is_dapi\": %s, \"include\": %s, \"output_name\": %s}",
                    ch.page, quote(ch.name), ch.isDapi, ch.include,
                    ch.outputName != null ? quote(ch.outputName) : "null"));
        }
        sb.append("]}");
        return sb.toString();
    }

    private static String matrixJson(double[][] m) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < 3; i++) {
            if (i > 0) sb.append(", ");
            sb.append("[");
            for (int j = 0; j < 3; j++) {
                if (j > 0) sb.append(", ");
                sb.append(String.format(Locale.ROOT, "%.12e", m[i][j]));
            }
            sb.append("]");
        }
        sb.append("]");
        return sb.toString();
    }

    private static String quote(String s) {
        if (s == null) return "null";
        StringBuilder sb = new StringBuilder("\"");
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '\\': sb.append("\\\\"); break;
                case '"':  sb.append("\\\""); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                case '\b': sb.append("\\b"); break;
                case '\f': sb.append("\\f"); break;
                default:
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
            }
        }
        sb.append("\"");
        return sb.toString();
    }

    private static String mapCompressionToVips(OMEPyramidWriter.CompressionType c) {
        if (c == null) return "lzw";
        switch (c) {
            case UNCOMPRESSED: return "none";
            case LZW:          return "lzw";
            case ZLIB:         return "deflate";
            case JPEG:         return "jpeg";
            case J2K:
            case J2K_LOSSY:    return "jp2k";
            case DEFAULT:
            default:           return "lzw";
        }
    }

    private static String mapPyramidToVips(OmeTiffMergeWriter.PyramidMode m) {
        if (m == null) return "dyadic";
        switch (m) {
            case DYADIC: return "dyadic";
            case SPARSE: return "dyadic";   // vips only supports dyadic; fallback
            case SINGLE: return "single";
            default:     return "dyadic";
        }
    }
}
