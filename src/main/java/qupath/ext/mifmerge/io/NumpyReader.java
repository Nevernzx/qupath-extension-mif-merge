package qupath.ext.mifmerge.io;

import java.io.DataInput;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Minimal reader for NumPy {@code .npy} v1 files containing a 2D float64 array.
 *
 * <p>Just enough to ingest the {@code matrix_*.npy} files produced by
 * register_batch.py for golden-value comparison; not a general .npy reader.
 *
 * <p>Format reference: https://numpy.org/doc/stable/reference/generated/numpy.lib.format.html
 */
public final class NumpyReader {

    private NumpyReader() {}

    public static final class Array2D {
        public final int rows;
        public final int cols;
        public final double[][] data;

        Array2D(int rows, int cols, double[][] data) {
            this.rows = rows;
            this.cols = cols;
            this.data = data;
        }
    }

    public static Array2D readFloat64Matrix(Path path) throws IOException {
        try (InputStream raw = Files.newInputStream(path);
             DataInputStream in = new DataInputStream(raw)) {

            byte[] magic = new byte[6];
            in.readFully(magic);
            if (magic[0] != (byte) 0x93 || magic[1] != 'N' || magic[2] != 'U'
                    || magic[3] != 'M' || magic[4] != 'P' || magic[5] != 'Y') {
                throw new IOException("Not a .npy file: bad magic");
            }
            int major = in.readUnsignedByte();
            int minor = in.readUnsignedByte();
            int headerLen;
            if (major == 1) {
                headerLen = readUInt16LE(in);
            } else if (major == 2 || major == 3) {
                headerLen = readUInt32LE(in);
            } else {
                throw new IOException("Unsupported .npy version " + major + "." + minor);
            }

            byte[] headerBytes = new byte[headerLen];
            in.readFully(headerBytes);
            String header = new String(headerBytes, "US-ASCII").trim();

            // header looks like: {'descr': '<f8', 'fortran_order': False, 'shape': (3, 3), }
            String descr = extractString(header, "descr");
            boolean fortranOrder = extractBoolean(header, "fortran_order");
            int[] shape = extractShape(header);

            if (!"<f8".equals(descr) && !"=f8".equals(descr) && !"|f8".equals(descr)) {
                throw new IOException("Unsupported dtype " + descr + " (only little-endian float64 supported)");
            }
            if (shape.length != 2) {
                throw new IOException("Expected 2D array, got shape with rank " + shape.length);
            }
            int rows = shape[0];
            int cols = shape[1];
            int n = rows * cols;
            byte[] payload = new byte[n * 8];
            in.readFully(payload);
            ByteBuffer bb = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN);

            double[][] data = new double[rows][cols];
            if (!fortranOrder) {
                for (int r = 0; r < rows; r++) {
                    for (int c = 0; c < cols; c++) {
                        data[r][c] = bb.getDouble();
                    }
                }
            } else {
                for (int c = 0; c < cols; c++) {
                    for (int r = 0; r < rows; r++) {
                        data[r][c] = bb.getDouble();
                    }
                }
            }
            return new Array2D(rows, cols, data);
        }
    }

    private static int readUInt16LE(DataInput in) throws IOException {
        int b0 = in.readUnsignedByte();
        int b1 = in.readUnsignedByte();
        return b0 | (b1 << 8);
    }

    private static int readUInt32LE(DataInput in) throws IOException {
        int b0 = in.readUnsignedByte();
        int b1 = in.readUnsignedByte();
        int b2 = in.readUnsignedByte();
        int b3 = in.readUnsignedByte();
        return b0 | (b1 << 8) | (b2 << 16) | (b3 << 24);
    }

    private static String extractString(String header, String key) {
        // 'key': 'value'
        int k = header.indexOf("'" + key + "'");
        if (k < 0) throw new IllegalArgumentException("missing key: " + key);
        int q1 = header.indexOf('\'', k + key.length() + 2);
        int q2 = header.indexOf('\'', q1 + 1);
        return header.substring(q1 + 1, q2);
    }

    private static boolean extractBoolean(String header, String key) {
        int k = header.indexOf("'" + key + "'");
        if (k < 0) throw new IllegalArgumentException("missing key: " + key);
        String after = header.substring(k + key.length() + 3).trim();   // skip key + "': "
        return after.startsWith("True");
    }

    private static int[] extractShape(String header) {
        int k = header.indexOf("'shape'");
        if (k < 0) throw new IllegalArgumentException("missing shape");
        int p1 = header.indexOf('(', k);
        int p2 = header.indexOf(')', p1);
        String tuple = header.substring(p1 + 1, p2).trim();
        if (tuple.isEmpty()) return new int[0];
        String[] parts = tuple.split(",");
        // Handle trailing comma (e.g. "(3,)")
        int n = 0;
        for (String p : parts) if (!p.trim().isEmpty()) n++;
        int[] out = new int[n];
        int j = 0;
        for (String p : parts) {
            String t = p.trim();
            if (!t.isEmpty()) out[j++] = Integer.parseInt(t);
        }
        return out;
    }
}
