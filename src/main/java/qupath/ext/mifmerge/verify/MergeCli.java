package qupath.ext.mifmerge.verify;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.ext.mifmerge.core.MergedChannelLayout;
import qupath.ext.mifmerge.core.MifImageSource;
import qupath.ext.mifmerge.core.RegistrationOrchestrator;
import qupath.ext.mifmerge.io.BioFormatsMifSource;
import qupath.ext.mifmerge.merge.MergedServerFactory;
import qupath.ext.mifmerge.merge.OmeTiffMergeWriter;
import qupath.lib.images.servers.ImageServer;
import qupath.lib.images.servers.ImageServerProvider;

import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * End-to-end CLI: register multiple qptiffs (first one = fixed reference) on
 * the DAPI channel, then write a merged pyramidal OME-TIFF that contains the
 * fixed image's channels plus the non-DAPI channels of each moving image
 * transformed into the fixed coordinate frame.
 *
 * <p><b>Runtime requirements:</b> needs the full QuPath runtime on the
 * classpath because it uses {@link ImageServerProvider},
 * {@link qupath.lib.images.servers.TransformedServerBuilder}, and
 * {@link qupath.lib.images.writers.ome.OMEPyramidWriter}. The accompanying
 * Gradle setup of this project keeps QuPath as {@code compileOnly}, so this
 * CLI does NOT have its own {@code runMerge} task — it is intended to be
 * exercised either through the QuPath GUI command (future milestone) or by
 * running its {@code main} with a classpath that has QuPath 0.8 plus its
 * bundled extensions (Ubuntu 22+ / Win / Mac).
 *
 * <pre>{@code
 *   # Inside QuPath's classpath (when this extension is installed):
 *   java -cp ... qupath.ext.mifmerge.verify.MergeCli \
 *        <fixed.qptiff> <moving1.qptiff> [<moving2.qptiff>...] \
 *        --out merged.ome.tiff [--reuse-matrix <path>]
 * }</pre>
 */
public final class MergeCli {

    private static final Logger logger = LoggerFactory.getLogger(MergeCli.class);

    public static void main(String[] args) throws Exception {
        Args parsed = Args.parse(args);

        // 1. Open each qptiff via QuPath ImageServerProvider for the merge path,
        //    and via Bio-Formats directly for the registration path.
        List<MifImageSource> bfSources = new ArrayList<>();
        List<ImageServer<BufferedImage>> qpServers = new ArrayList<>();
        try {
            for (String p : parsed.inputs) {
                logger.info("Opening {}", p);
                bfSources.add(new BioFormatsMifSource(new File(p)));
                qpServers.add(ImageServerProvider.buildServer(p, BufferedImage.class));
            }

            // 2. Register each moving against fixed.
            MifImageSource fixedBf = bfSources.get(0);
            ImageServer<BufferedImage> fixedQp = qpServers.get(0);
            List<MergedServerFactory.MovingEntry> moving = new ArrayList<>();
            List<RegistrationOrchestrator.Result> regResults = new ArrayList<>();
            for (int i = 1; i < bfSources.size(); i++) {
                logger.info("Registering moving {} against fixed", i);
                RegistrationOrchestrator.Result r = RegistrationOrchestrator.run(
                        fixedBf, bfSources.get(i),
                        new RegistrationOrchestrator.Config());
                regResults.add(r);

                AffineTransform aff = new AffineTransform(
                        r.matrixFullRes[0][0], r.matrixFullRes[1][0],
                        r.matrixFullRes[0][1], r.matrixFullRes[1][1],
                        r.matrixFullRes[0][2], r.matrixFullRes[1][2]);
                logger.info("Moving {} -> fixed affine: {}", i, aff);
                moving.add(new MergedServerFactory.MovingEntry(qpServers.get(i), aff));
            }

            // 3. Build channel layout and the merged virtual server.
            List<List<String>> sourceChannels = new ArrayList<>();
            List<String> sourceLabels = new ArrayList<>();
            for (int i = 0; i < bfSources.size(); i++) {
                sourceChannels.add(bfSources.get(i).getChannelNames());
                sourceLabels.add(stem(parsed.inputs.get(i)));
            }
            List<MergedChannelLayout.ChannelEntry> layout = MergedChannelLayout.build(
                    sourceChannels, sourceLabels, "DAPI");
            logger.info("Merged channel layout: {} channels", layout.size());
            for (int i = 0; i < layout.size(); i++) {
                logger.info("  [{}] {}", i, layout.get(i));
            }

            ImageServer<BufferedImage> merged = MergedServerFactory.build(
                    fixedQp, moving, layout);

            // 4. Write out.
            Path outPath = Path.of(parsed.outPath);
            Files.createDirectories(outPath.toAbsolutePath().getParent());
            OmeTiffMergeWriter.Options opts = new OmeTiffMergeWriter.Options();
            OmeTiffMergeWriter.write(merged, outPath.toString(), opts);

            // 5. Optional: dump matrices alongside the OME-TIFF.
            Path matricesPath = outPath.resolveSibling(
                    stem(outPath.getFileName().toString()) + "-matrices.txt");
            writeMatricesSummary(matricesPath, parsed.inputs, regResults);
            logger.info("Wrote matrices summary: {}", matricesPath);

        } finally {
            for (MifImageSource s : bfSources) s.close();
            for (ImageServer<BufferedImage> s : qpServers) try { s.close(); } catch (Exception ignored) {}
        }
    }

    private static String stem(String filename) {
        String name = new File(filename).getName();
        int dot = name.indexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }

    private static void writeMatricesSummary(Path path, List<String> inputs,
                                             List<RegistrationOrchestrator.Result> results) throws Exception {
        StringBuilder sb = new StringBuilder();
        sb.append("Fixed: ").append(inputs.get(0)).append("\n\n");
        for (int i = 0; i < results.size(); i++) {
            sb.append("Moving[").append(i + 1).append("]: ").append(inputs.get(i + 1)).append("\n");
            RegistrationOrchestrator.Result r = results.get(i);
            for (int row = 0; row < 3; row++) {
                sb.append(String.format(Locale.ROOT, "  [%+.6e %+.6e %+.6e]%n",
                        r.matrixFullRes[row][0], r.matrixFullRes[row][1], r.matrixFullRes[row][2]));
            }
            sb.append("  Stage 2 inliers: ").append(r.stages.stage2.nInliers)
                    .append("/").append(r.stages.stage2.nMatchesPostPrefilter)
                    .append(String.format(" (%.1f%%)", 100.0 * r.stages.stage2.inlierRatio))
                    .append("\n");
            sb.append(String.format(Locale.ROOT, "  Reproj median @ L2: %.2f px%n%n",
                    r.stages.stage2.medianReprojErrPx));
        }
        Files.writeString(path, sb.toString());
    }

    // -- Argument parsing -------------------------------------------------

    private static final class Args {
        List<String> inputs = new ArrayList<>();
        String outPath = "merged.ome.tiff";

        static Args parse(String[] argv) {
            if (argv.length == 0) {
                printUsageAndExit();
            }
            Args a = new Args();
            for (int i = 0; i < argv.length; i++) {
                String s = argv[i];
                if ("--out".equals(s) || "-o".equals(s)) {
                    if (i + 1 >= argv.length) printUsageAndExit();
                    a.outPath = argv[++i];
                } else if (s.startsWith("--")) {
                    System.err.println("Unknown option: " + s);
                    printUsageAndExit();
                } else {
                    a.inputs.add(s);
                }
            }
            if (a.inputs.size() < 2) {
                System.err.println("Need at least 2 input qptiff files (first = fixed reference)");
                printUsageAndExit();
            }
            return a;
        }

        private static void printUsageAndExit() {
            System.err.println("Usage: MergeCli <fixed.qptiff> <moving1.qptiff> [<moving2.qptiff>...] --out <merged.ome.tiff>");
            System.exit(2);
        }
    }
}
