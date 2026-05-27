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

    @Override
    public BufferedImage readChannelAtLevel(int channelIndex, int level) {
        synchronized (reader) {
            reader.setResolution(level);
            int w = reader.getSizeX();
            int h = reader.getSizeY();
            int pixelType = reader.getPixelType();
            int z = 0;
            int t = 0;
            try {
                int planeIndex = reader.getIndex(z, channelIndex, t);
                byte[] raw = reader.openBytes(planeIndex);
                boolean isLittleEndian = reader.isLittleEndian();
                logger.debug("readChannelAtLevel({}, {}) -> {}x{}, pixelType={}, {} bytes",
                        channelIndex, level, w, h, pixelType, raw.length);

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
