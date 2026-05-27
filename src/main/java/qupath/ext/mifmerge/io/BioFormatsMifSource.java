package qupath.ext.mifmerge.io;

import loci.common.services.ServiceFactory;
import loci.formats.IFormatReader;
import loci.formats.ImageReader;
import loci.formats.MetadataTools;
import loci.formats.meta.IMetadata;
import loci.formats.services.OMEXMLService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.ext.mifmerge.core.AutoContrast;
import qupath.ext.mifmerge.core.MifImageSource;

import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.ShortBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * {@link MifImageSource} backed by Bio-Formats. Designed for Vectra Polaris
 * qptiff: 1 series, multi-channel, pyramidal.
 *
 * <p>Channel names come from the OME-XML <code>&lt;Channel Name="..."/&gt;</code>
 * attributes (DAPI is usually called "DAPI", with biomarker channels named
 * by fluorophore + protein like "Opal 520 (CK19)").
 *
 * <p>The reader is opened on construction and held open until {@link #close()}
 * is called. Concurrent reads from a single instance are NOT safe — wrap
 * with serialization or open multiple readers.
 */
public final class BioFormatsMifSource implements MifImageSource {

    private static final Logger logger = LoggerFactory.getLogger(BioFormatsMifSource.class);

    /** Default percentile-range autocontrast values (mirror register_batch.py). */
    public static final double DEFAULT_PCT_LO = 1.0;
    public static final double DEFAULT_PCT_HI = 99.5;

    private final IFormatReader reader;
    private final IMetadata meta;
    private final int series;
    private final List<String> channelNames;
    private final double pctLo;
    private final double pctHi;
    private final String displayName;

    public BioFormatsMifSource(File file) throws Exception {
        this(file, 0, DEFAULT_PCT_LO, DEFAULT_PCT_HI);
    }

    public BioFormatsMifSource(File file, int series, double pctLo, double pctHi) throws Exception {
        this.series = series;
        this.pctLo = pctLo;
        this.pctHi = pctHi;
        this.displayName = file.getName();

        // Build an OME metadata store and attach it to a fresh reader.
        ServiceFactory factory = new ServiceFactory();
        OMEXMLService xml = factory.getInstance(OMEXMLService.class);
        this.meta = xml.createOMEXMLMetadata();
        this.reader = new ImageReader();
        reader.setFlattenedResolutions(false);   // expose pyramid as resolutions, not series
        reader.setMetadataStore(meta);
        reader.setId(file.getAbsolutePath());

        if (series >= reader.getSeriesCount()) {
            throw new IllegalArgumentException(
                    "Requested series " + series + " but file only has " + reader.getSeriesCount());
        }
        reader.setSeries(series);

        int nC = reader.getSizeC();
        ArrayList<String> names = new ArrayList<>(nC);
        for (int c = 0; c < nC; c++) {
            String name = meta.getChannelName(series, c);
            names.add(name != null ? name : ("Channel " + c));
        }
        this.channelNames = List.copyOf(names);

        logger.info("Opened {} (series {} of {}): full {}x{}, {} channels, {} resolutions",
                file.getName(), series, reader.getSeriesCount(),
                reader.getSizeX(), reader.getSizeY(),
                nC, reader.getResolutionCount());
        for (int c = 0; c < nC; c++) {
            logger.info("  channel {}: {}", c, channelNames.get(c));
        }
    }

    @Override
    public List<String> getChannelNames() {
        return channelNames;
    }

    @Override
    public int getFullWidth() {
        synchronized (reader) {
            reader.setResolution(0);
            return reader.getSizeX();
        }
    }

    @Override
    public int getFullHeight() {
        synchronized (reader) {
            reader.setResolution(0);
            return reader.getSizeY();
        }
    }

    @Override
    public double[] getDownsamples() {
        synchronized (reader) {
            int n = reader.getResolutionCount();
            double[] ds = new double[n];
            reader.setResolution(0);
            double w0 = reader.getSizeX();
            for (int r = 0; r < n; r++) {
                reader.setResolution(r);
                ds[r] = w0 / reader.getSizeX();
            }
            return ds;
        }
    }

    @Override
    public int getLevelWidth(int level) {
        synchronized (reader) {
            reader.setResolution(level);
            return reader.getSizeX();
        }
    }

    @Override
    public int getLevelHeight(int level) {
        synchronized (reader) {
            reader.setResolution(level);
            return reader.getSizeY();
        }
    }

    /** Tile size for sub-region reads. Bio-Formats handles arbitrary sizes; 1024 is a sane default. */
    private static final int TILE_SIZE = 1024;

    /**
     * Safety cap so a misconfigured pyramid level can't ask us to allocate >2 GB.
     * If hit, throws immediately instead of OOM-ing the JVM / native heap.
     */
    private static final long MAX_PIXELS_PER_PLANE = 2L * 1024 * 1024 * 1024 / 2;   // 2 GB / 2 bytes-per-px ≈ 1 G pixels

    @Override
    public BufferedImage readChannelAtLevel(int channelIndex, int level) {
        synchronized (reader) {
            reader.setResolution(level);
            int w = reader.getSizeX();
            int h = reader.getSizeY();
            int pixelType = reader.getPixelType();
            int bytesPerPx = loci.formats.FormatTools.getBytesPerPixel(pixelType);
            long pixels = (long) w * (long) h;
            long bytesAtFullDepth = pixels * bytesPerPx;
            logger.info("readChannelAtLevel(ch={}, level={}) -> dims {}x{}, pixelType={}, "
                            + "{} px = {} MB at {}-byte depth — reading by {} px tiles",
                    channelIndex, level, w, h,
                    loci.formats.FormatTools.getPixelTypeString(pixelType),
                    pixels, bytesAtFullDepth / (1024L * 1024L), bytesPerPx, TILE_SIZE);
            if (pixels > MAX_PIXELS_PER_PLANE) {
                throw new RuntimeException(String.format(
                        "Refusing to read %d × %d = %d pixels at level %d of %s "
                                + "(over %d pixel cap). Did the pyramid level selector pick the wrong layer? "
                                + "Try a smaller stage1/stage2 long-side in the GUI.",
                        w, h, pixels, level, displayName, MAX_PIXELS_PER_PLANE));
            }
            int z = 0;
            int t = 0;
            try {
                int planeIndex = reader.getIndex(z, channelIndex, t);
                boolean isLittleEndian = reader.isLittleEndian();

                switch (pixelType) {
                    case loci.formats.FormatTools.UINT8:
                    case loci.formats.FormatTools.INT8: {
                        byte[] full = readPlaneTiled(planeIndex, w, h, bytesPerPx);
                        return AutoContrast.stretchToByteGray(full, w, h, pctLo, pctHi);
                    }
                    case loci.formats.FormatTools.UINT16:
                    case loci.formats.FormatTools.INT16: {
                        short[] u16 = readPlaneTiledAsShorts(planeIndex, w, h, isLittleEndian);
                        return AutoContrast.stretchToByteGray(u16, w, h, pctLo, pctHi);
                    }
                    default:
                        throw new UnsupportedOperationException(
                                "Unsupported pixel type " + pixelType
                                        + " (need UINT8/UINT16, got " + loci.formats.FormatTools.getPixelTypeString(pixelType) + ")");
                }
            } catch (Exception e) {
                throw new RuntimeException(
                        "Failed to read channel " + channelIndex + " at level " + level
                                + " of " + displayName, e);
            }
        }
    }

    @Override
    public BufferedImage readRegionAtLevel(int channelIndex, int level,
                                           int x, int y, int width, int height) {
        synchronized (reader) {
            reader.setResolution(level);
            int levelW = reader.getSizeX();
            int levelH = reader.getSizeY();
            // Clip to level bounds
            int x0 = Math.max(0, x);
            int y0 = Math.max(0, y);
            int x1 = Math.min(levelW, x + width);
            int y1 = Math.min(levelH, y + height);
            int w = Math.max(0, x1 - x0);
            int h = Math.max(0, y1 - y0);
            if (w == 0 || h == 0) {
                throw new IllegalArgumentException(
                        "Requested region [" + x + "," + y + "," + width + "," + height + "] "
                                + "is fully outside level " + level + " bounds " + levelW + "x" + levelH);
            }
            int pixelType = reader.getPixelType();
            int bytesPerPx = loci.formats.FormatTools.getBytesPerPixel(pixelType);
            try {
                int planeIndex = reader.getIndex(0, channelIndex, 0);
                byte[] raw = reader.openBytes(planeIndex, x0, y0, w, h);
                boolean isLittleEndian = reader.isLittleEndian();

                switch (pixelType) {
                    case loci.formats.FormatTools.UINT8:
                    case loci.formats.FormatTools.INT8:
                        return AutoContrast.stretchToByteGray(raw, w, h, pctLo, pctHi);
                    case loci.formats.FormatTools.UINT16:
                    case loci.formats.FormatTools.INT16: {
                        short[] u16 = bytesToShorts(raw, isLittleEndian);
                        return AutoContrast.stretchToByteGray(u16, w, h, pctLo, pctHi);
                    }
                    default:
                        throw new UnsupportedOperationException(
                                "Unsupported pixel type " + pixelType);
                }
            } catch (Exception e) {
                throw new RuntimeException(
                        "Failed to read region " + x + "," + y + "," + width + "x" + height
                                + " of channel " + channelIndex + " at level " + level
                                + " of " + displayName, e);
            }
        }
    }

    @Override
    public String getDisplayName() {
        return displayName;
    }

    @Override
    public void close() {
        try {
            reader.close();
        } catch (Exception e) {
            logger.warn("Error closing Bio-Formats reader for {}: {}", displayName, e.getMessage());
        }
    }

    private static short[] bytesToShorts(byte[] raw, boolean littleEndian) {
        ByteBuffer bb = ByteBuffer.wrap(raw).order(
                littleEndian ? ByteOrder.LITTLE_ENDIAN : ByteOrder.BIG_ENDIAN);
        ShortBuffer sb = bb.asShortBuffer();
        short[] out = new short[sb.remaining()];
        sb.get(out);
        return out;
    }

    /**
     * Read an entire plane by iterating over {@link #TILE_SIZE}-px tiles and copying
     * pixel bytes into a single result array. This bounds transient memory to roughly
     * one tile per iteration rather than the full plane.
     *
     * <p>Used only for 1- or 2-byte pixel types — the result array is the full plane.
     */
    private byte[] readPlaneTiled(int planeIndex, int width, int height, int bytesPerPx)
            throws Exception {
        long total = (long) width * height * bytesPerPx;
        if (total > Integer.MAX_VALUE) {
            throw new RuntimeException("Plane too big for a single byte[] (" + total + " bytes)");
        }
        byte[] out = new byte[(int) total];
        int stride = width * bytesPerPx;
        for (int y = 0; y < height; y += TILE_SIZE) {
            int th = Math.min(TILE_SIZE, height - y);
            for (int x = 0; x < width; x += TILE_SIZE) {
                int tw = Math.min(TILE_SIZE, width - x);
                byte[] tile = reader.openBytes(planeIndex, x, y, tw, th);
                int tileStride = tw * bytesPerPx;
                for (int ty = 0; ty < th; ty++) {
                    int srcOff = ty * tileStride;
                    int dstOff = (y + ty) * stride + x * bytesPerPx;
                    System.arraycopy(tile, srcOff, out, dstOff, tileStride);
                }
            }
        }
        return out;
    }

    /** Convenience wrapper around {@link #readPlaneTiled} that converts the result to short[]. */
    private short[] readPlaneTiledAsShorts(int planeIndex, int width, int height, boolean littleEndian)
            throws Exception {
        byte[] bytes = readPlaneTiled(planeIndex, width, height, 2);
        return bytesToShorts(bytes, littleEndian);
    }

    /**
     * Convenience: find the first channel whose name matches {@code target}
     * (case-insensitive substring). Returns -1 if none matches.
     */
    public static int findChannelIndex(List<String> channelNames, String target) {
        String needle = target.toLowerCase();
        for (int i = 0; i < channelNames.size(); i++) {
            String n = channelNames.get(i);
            if (n != null && n.toLowerCase().contains(needle)) {
                return i;
            }
        }
        return -1;
    }
}
