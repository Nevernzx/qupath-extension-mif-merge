package qupath.ext.mifmerge.merge;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.ext.mifmerge.core.MergedChannelLayout;
import qupath.lib.images.servers.ImageChannel;
import qupath.lib.images.servers.ImageServer;
import qupath.lib.images.servers.TransformedServerBuilder;

import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;

/**
 * Build a virtual merged {@link ImageServer} from a fixed reference image and
 * a list of moving images, each carrying its own affine transform that maps
 * moving-pixel coordinates into fixed-pixel coordinates.
 *
 * <p>For each moving server we apply
 * {@code TransformedServerBuilder.transform(affine).extractChannels(nonDapiIndices)},
 * then concat the wrapped moving servers as additional channels onto the
 * fixed server. The result is an {@code ImageServer<BufferedImage>} whose
 * tiles are computed lazily by QuPath's TileRequest machinery on demand —
 * no full-image warp needs to fit in memory.
 *
 * <p>This class is intentionally minimal: it does NOT open files (callers
 * provide already-opened servers) and does NOT write anything to disk (see
 * {@link OmeTiffMergeWriter}).
 */
public final class MergedServerFactory {

    private static final Logger logger = LoggerFactory.getLogger(MergedServerFactory.class);

    public static final class MovingEntry {
        public final ImageServer<BufferedImage> server;
        /** Affine maps moving pixel coordinates -> fixed pixel coordinates. */
        public final AffineTransform affine;

        public MovingEntry(ImageServer<BufferedImage> server, AffineTransform affine) {
            this.server = server;
            this.affine = affine;
        }
    }

    private MergedServerFactory() {}

    /**
     * @param fixedServer first/reference image
     * @param movings list of moving images with their affine transforms
     * @param layout channel layout — typically built via
     *               {@link MergedChannelLayout#build(List, List, String)};
     *               must have {@code sourceIndex=0} for fixed channels and
     *               {@code sourceIndex >= 1} corresponds to movings.get(i-1)
     */
    public static ImageServer<BufferedImage> build(
            ImageServer<BufferedImage> fixedServer,
            List<MovingEntry> movings,
            List<MergedChannelLayout.ChannelEntry> layout) {

        // 1. For each moving, build a transformed + channel-extracted server.
        List<ImageServer<BufferedImage>> wrappedMovings = new ArrayList<>();
        for (int mi = 0; mi < movings.size(); mi++) {
            MovingEntry m = movings.get(mi);
            int sourceIndex = mi + 1;
            int[] channels = MergedChannelLayout.channelsForSource(layout, sourceIndex);
            if (channels.length == 0) {
                logger.warn("Moving source {} contributes no channels to the merged image, skipping",
                        sourceIndex);
                continue;
            }
            logger.info("Wrapping moving {}: transform={} | keep channels {}",
                    sourceIndex, m.affine, java.util.Arrays.toString(channels));
            ImageServer<BufferedImage> wrapped = new TransformedServerBuilder(m.server)
                    .transform(m.affine)
                    .extractChannels(channels)
                    .build();
            wrappedMovings.add(wrapped);
        }

        // 2. Extract fixed channels if needed (the layout might re-order or drop some;
        // currently the default layout keeps them all in original order, but be defensive).
        int[] fixedChannels = MergedChannelLayout.channelsForSource(layout, 0);
        ImageServer<BufferedImage> fixedToConcat = fixedServer;
        if (fixedChannels.length != fixedServer.nChannels()) {
            fixedToConcat = new TransformedServerBuilder(fixedServer)
                    .extractChannels(fixedChannels)
                    .build();
        }

        // 3. Concat moving channels onto fixed.
        ImageServer<BufferedImage> merged;
        if (wrappedMovings.isEmpty()) {
            merged = fixedToConcat;
        } else {
            merged = new TransformedServerBuilder(fixedToConcat)
                    .concatChannels(wrappedMovings)
                    .build();
        }

        logMergedChannels(merged, layout);
        return merged;
    }

    private static void logMergedChannels(ImageServer<BufferedImage> merged,
                                          List<MergedChannelLayout.ChannelEntry> layout) {
        List<ImageChannel> channels = merged.getMetadata().getChannels();
        logger.info("Merged server: {} x {} px, {} channels:",
                merged.getWidth(), merged.getHeight(), channels.size());
        int n = Math.min(channels.size(), layout.size());
        for (int i = 0; i < n; i++) {
            logger.info("  [{}] {}  (from {})", i, channels.get(i).getName(), layout.get(i));
        }
    }
}
