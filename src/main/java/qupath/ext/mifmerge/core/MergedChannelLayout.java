package qupath.ext.mifmerge.core;

import java.util.ArrayList;
import java.util.List;

/**
 * Pure-logic helper that decides the channel layout of the merged WSI.
 *
 * <p>Inputs:
 * <ul>
 *   <li>A list of source images (each with its channel names)</li>
 *   <li>An index marker indicating which one is the "fixed" reference</li>
 *   <li>A configurable DAPI name match (default "DAPI")</li>
 * </ul>
 *
 * <p>Output is a flat list of {@link ChannelEntry} describing where each
 * output channel comes from. The fixed image keeps ALL of its channels
 * (including DAPI). Every other source contributes its non-DAPI channels in
 * their original order, with DAPI dropped to avoid duplicates across panels.
 * (Different panels' DAPI scans of the same tissue are redundant once we
 * align everything to the fixed-side DAPI.)
 *
 * <p>The class also produces human-readable channel names, prefixing
 * biomarker-bearing sources with a short label so the merged WSI is
 * navigable in QuPath.
 */
public final class MergedChannelLayout {

    private MergedChannelLayout() {}

    public static final class ChannelEntry {
        /** 0 = fixed, 1..N = moving sources. */
        public final int sourceIndex;
        /** Channel index within that source. */
        public final int sourceChannelIndex;
        /** Display name for this channel in the merged WSI. */
        public final String mergedName;

        public ChannelEntry(int sourceIndex, int sourceChannelIndex, String mergedName) {
            this.sourceIndex = sourceIndex;
            this.sourceChannelIndex = sourceChannelIndex;
            this.mergedName = mergedName;
        }

        @Override
        public String toString() {
            return String.format("ChannelEntry{src=%d, ch=%d, name='%s'}",
                    sourceIndex, sourceChannelIndex, mergedName);
        }
    }

    /**
     * Build the layout, dropping moving DAPI channels (the default behaviour
     * — two DAPI scans of the same tissue are redundant after registration).
     * Equivalent to {@link #build(List, List, String, boolean)} with
     * {@code keepMovingDapi = false}.
     */
    public static List<ChannelEntry> build(
            List<List<String>> sourceChannelNames,
            List<String> sourceLabels,
            String dapiNameMatch) {
        return build(sourceChannelNames, sourceLabels, dapiNameMatch, false);
    }

    /**
     * Build the layout. The first element of {@code sourceChannelNames} is
     * the fixed reference; subsequent elements are moving sources.
     *
     * @param sourceChannelNames per-source list of channel names
     * @param sourceLabels optional short tags used as prefix in merged names
     *                     (e.g. file stem). If null/empty, no prefix added.
     * @param dapiNameMatch substring (case-insensitive) used to identify DAPI
     * @param keepMovingDapi if {@code true}, moving sources' DAPI channels are
     *                       kept in the output (useful when you want to inspect
     *                       both DAPI scans, e.g. for QC). If {@code false},
     *                       only the fixed DAPI is kept.
     */
    public static List<ChannelEntry> build(
            List<List<String>> sourceChannelNames,
            List<String> sourceLabels,
            String dapiNameMatch,
            boolean keepMovingDapi) {
        if (sourceChannelNames.isEmpty()) {
            throw new IllegalArgumentException("At least one source is required");
        }
        boolean usePrefix = sourceLabels != null && !sourceLabels.isEmpty();
        if (usePrefix && sourceLabels.size() != sourceChannelNames.size()) {
            throw new IllegalArgumentException(
                    "sourceLabels.size() (" + sourceLabels.size() + ") must match "
                            + "sourceChannelNames.size() (" + sourceChannelNames.size() + ")");
        }
        String needle = dapiNameMatch == null ? "DAPI" : dapiNameMatch;
        String needleLower = needle.toLowerCase();

        List<ChannelEntry> out = new ArrayList<>();
        for (int srcIdx = 0; srcIdx < sourceChannelNames.size(); srcIdx++) {
            List<String> channels = sourceChannelNames.get(srcIdx);
            boolean isFixed = (srcIdx == 0);
            String label = usePrefix ? sourceLabels.get(srcIdx) : null;
            for (int ch = 0; ch < channels.size(); ch++) {
                String name = channels.get(ch);
                boolean isDapi = name != null && name.toLowerCase().contains(needleLower);
                if (!isFixed && isDapi && !keepMovingDapi) {
                    continue;   // drop moving DAPI to avoid duplicates
                }
                String merged;
                if (label != null && !label.isEmpty()) {
                    merged = label + " | " + (name != null ? name : "ch" + ch);
                } else {
                    merged = (name != null ? name : "ch" + ch);
                }
                out.add(new ChannelEntry(srcIdx, ch, merged));
            }
        }
        return out;
    }

    /**
     * Filter the entries to just those belonging to a given source.
     * Useful when you need the non-DAPI channel indices for a moving source.
     */
    public static int[] channelsForSource(List<ChannelEntry> layout, int sourceIndex) {
        return layout.stream()
                .filter(e -> e.sourceIndex == sourceIndex)
                .mapToInt(e -> e.sourceChannelIndex)
                .toArray();
    }
}
