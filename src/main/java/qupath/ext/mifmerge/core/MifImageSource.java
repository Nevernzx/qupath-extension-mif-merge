package qupath.ext.mifmerge.core;

import java.awt.image.BufferedImage;
import java.util.List;

/**
 * Minimal abstraction over a multi-channel pyramidal image (typically a qptiff).
 *
 * <p>The algorithm code (SIFT/RANSAC/orchestrator) is intentionally decoupled
 * from any concrete reader so it can be exercised with raw Bio-Formats in a
 * standalone CLI and with QuPath's {@code BioFormatsImageServer} when running
 * inside QuPath.
 *
 * <p>Coordinates: channels are 0-indexed, levels are 0-indexed (level 0 =
 * full resolution). Per-level dimensions are recovered with
 * {@link #getLevelWidth(int)} / {@link #getLevelHeight(int)} and downsamples
 * with {@link #getDownsamples()} (downsample 1.0 == level 0).
 */
public interface MifImageSource extends AutoCloseable {

    /** Human-readable channel names, in channel order. */
    List<String> getChannelNames();

    /** Full-resolution width. */
    int getFullWidth();

    /** Full-resolution height. */
    int getFullHeight();

    /**
     * Per-level downsample factors, sorted from smallest (level 0 = 1.0) to
     * largest. {@code downsamples[L]} approximately equals
     * {@code fullWidth / getLevelWidth(L)}.
     */
    double[] getDownsamples();

    int getLevelWidth(int level);

    int getLevelHeight(int level);

    /**
     * Read the entire channel at the given pyramid level as a single
     * {@link BufferedImage} (TYPE_BYTE_GRAY after autocontrast — see
     * {@link AutoContrast}).
     */
    BufferedImage readChannelAtLevel(int channelIndex, int level);

    /**
     * Read a sub-region of a channel at the given pyramid level.
     *
     * <p>Coordinates are in pixels of the chosen level (NOT full-res unless
     * level=0). If the requested rectangle extends beyond the level's bounds
     * it is clipped; the returned image is the clipped portion (so its
     * dimensions can be smaller than {@code width}/{@code height}).
     *
     * <p>The returned image is autocontrasted to 8-bit gray exactly like
     * {@link #readChannelAtLevel(int, int)}. Used by the windowed
     * full-resolution refinement (Stage 3) so we don't have to load the
     * full level-0 plane into memory.
     */
    BufferedImage readRegionAtLevel(int channelIndex, int level, int x, int y, int width, int height);

    /** A short description for logging — typically the underlying filename. */
    String getDisplayName();

    @Override
    void close();
}
