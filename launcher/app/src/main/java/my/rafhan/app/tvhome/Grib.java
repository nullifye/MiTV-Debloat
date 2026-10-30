package my.rafhan.app.tvhome;

import java.util.ArrayList;
import java.util.List;

/**
 * Just enough GRIB2 (WMO FM 92) to read NOAA's GFS wind for the Globe screen saver: a regular
 * latitude/longitude grid (template 3.0) packed with "complex packing and spatial differencing"
 * (template 5.3), which is what NOMADS' grib filter sends, plus simple packing (5.0). Anything
 * else is skipped. Free of Android, so it's unit tested.
 */
final class Grib {
    /** One field: its parameter (discipline, category, number) and its values, row by row as sent. */
    static final class Field {
        final int discipline, category, number, nx, ny;
        final float[] values;

        Field(int discipline, int category, int number, int nx, int ny, float[] values) {
            this.discipline = discipline;
            this.category = category;
            this.number = number;
            this.nx = nx;
            this.ny = ny;
            this.values = values;
        }
    }

    private Grib() {}

    /** Every field in the file it can read; broken or unsupported messages are left out. */
    static List<Field> read(byte[] b) {
        List<Field> out = new ArrayList<>();
        int i = 0;
        while (i + 16 <= b.length) {
            if (b[i] != 'G' || b[i + 1] != 'R' || b[i + 2] != 'I' || b[i + 3] != 'B') {
                i++;
                continue;
            }
            long total = u(b, i + 8, 8);
            if (total < 16 || i + total > b.length) break;
            try {
                Field f = message(b, i, (int) total);
                if (f != null) out.add(f);
            } catch (RuntimeException ignored) { // a message it can't make sense of
            }
            i += (int) total;
        }
        return out;
    }

    private static Field message(byte[] b, int start, int total) {
        int discipline = b[start + 6] & 0xFF;
        int nx = 0, ny = 0, category = -1, number = -1, npts = 0, template5 = -1, s5 = -1, s7 = -1, len7 = 0;
        boolean bitmap = false;
        int j = start + 16, end = start + total - 4;
        while (j + 5 <= end) {
            int len = (int) u(b, j, 4), sec = b[j + 4] & 0xFF;
            if (len < 5) break;
            if (sec == 3) {
                if (u(b, j + 12, 2) != 0) return null; // only the regular lat/lon grid
                nx = (int) u(b, j + 30, 4);
                ny = (int) u(b, j + 34, 4);
            } else if (sec == 4) {
                category = b[j + 9] & 0xFF;
                number = b[j + 10] & 0xFF;
            } else if (sec == 5) {
                npts = (int) u(b, j + 5, 4);
                template5 = (int) u(b, j + 9, 2);
                s5 = j;
            } else if (sec == 6) {
                bitmap = (b[j + 5] & 0xFF) != 255;
            } else if (sec == 7) {
                s7 = j;
                len7 = len;
            }
            j += len;
        }
        if (s5 < 0 || s7 < 0 || bitmap || npts != nx * ny || npts == 0) return null;
        float[] v;
        if (template5 == 3) v = complex(b, s5, s7 + 5, len7 - 5, npts);
        else if (template5 == 0) v = simple(b, s5, s7 + 5, npts);
        else return null;
        return new Field(discipline, category, number, nx, ny, v);
    }

    /** Template 5.0: each value nbits wide, R + X·2^E over 10^D. */
    private static float[] simple(byte[] b, int s5, int data, int npts) {
        float r = Float.intBitsToFloat((int) u(b, s5 + 11, 4));
        int e = signed(b, s5 + 15, 2), d = signed(b, s5 + 17, 2), nbits = b[s5 + 19] & 0xFF;
        double be = Math.pow(2, e), de = Math.pow(10, -d);
        Bits in = new Bits(b, data);
        float[] v = new float[npts];
        for (int k = 0; k < npts; k++) v[k] = (float) ((r + (nbits == 0 ? 0 : in.read(nbits)) * be) * de);
        return v;
    }

    /** Template 5.3: groups of values, each with its own reference and width, then undifferenced. */
    private static float[] complex(byte[] b, int s5, int data, int dataLen, int npts) {
        float r = Float.intBitsToFloat((int) u(b, s5 + 11, 4));
        int e = signed(b, s5 + 15, 2), d = signed(b, s5 + 17, 2), nbits = b[s5 + 19] & 0xFF;
        int missing = b[s5 + 22] & 0xFF;
        if (missing != 0) throw new IllegalArgumentException("missing values not handled");
        int ng = (int) u(b, s5 + 31, 4);
        int widthRef = b[s5 + 35] & 0xFF, widthBits = b[s5 + 36] & 0xFF;
        long lengthRef = u(b, s5 + 37, 4);
        int lengthInc = b[s5 + 41] & 0xFF;
        long lastLength = u(b, s5 + 42, 4);
        int lengthBits = b[s5 + 46] & 0xFF, order = b[s5 + 47] & 0xFF, extra = b[s5 + 48] & 0xFF;
        if (order < 1 || order > 2) throw new IllegalArgumentException("spatial differencing order " + order);

        int p = data;
        long ival1 = signed(b, p, extra), ival2 = 0;
        p += extra;
        if (order == 2) {
            ival2 = signed(b, p, extra);
            p += extra;
        }
        long minsd = signed(b, p, extra);
        p += extra;

        Bits in = new Bits(b, p);
        long[] refs = new long[ng];
        for (int g = 0; g < ng; g++) refs[g] = nbits == 0 ? 0 : in.read(nbits);
        in.align();
        int[] widths = new int[ng];
        for (int g = 0; g < ng; g++) widths[g] = widthRef + (widthBits == 0 ? 0 : (int) in.read(widthBits));
        in.align();
        long[] lengths = new long[ng];
        for (int g = 0; g < ng; g++) lengths[g] = lengthRef + (lengthBits == 0 ? 0 : in.read(lengthBits)) * lengthInc;
        if (ng > 0) lengths[ng - 1] = lastLength;
        in.align();

        long[] x = new long[npts];
        int k = 0;
        for (int g = 0; g < ng && k < npts; g++) {
            for (long n = 0; n < lengths[g] && k < npts; n++) x[k++] = refs[g] + (widths[g] == 0 ? 0 : in.read(widths[g]));
        }
        if (k != npts || in.offset() > data + dataLen) throw new IllegalArgumentException("short data");

        // Undo the spatial differencing: the first `order` values are given, the rest are differences.
        for (int n = order; n < npts; n++) x[n] += minsd;
        if (order == 1) {
            x[0] = ival1;
            for (int n = 1; n < npts; n++) x[n] += x[n - 1];
        } else {
            x[0] = ival1;
            if (npts > 1) x[1] = ival2;
            for (int n = 2; n < npts; n++) x[n] += 2 * x[n - 1] - x[n - 2];
        }
        double be = Math.pow(2, e), de = Math.pow(10, -d);
        float[] v = new float[npts];
        for (int n = 0; n < npts; n++) v[n] = (float) ((r + x[n] * be) * de);
        return v;
    }

    /** Unsigned big-endian integer of n bytes. */
    private static long u(byte[] b, int at, int n) {
        long v = 0;
        for (int i = 0; i < n; i++) v = (v << 8) | (b[at + i] & 0xFF);
        return v;
    }

    /** GRIB's signed integers: the top bit is the sign, the rest the size (not two's complement). */
    static int signed(byte[] b, int at, int n) {
        if (n == 0) return 0;
        long v = u(b, at, n), top = 1L << (8 * n - 1);
        return (int) ((v & top) != 0 ? -(v & (top - 1)) : v);
    }

    /** Reads big-endian bit fields. */
    static final class Bits {
        private final byte[] b;
        private long bit; // absolute bit position

        Bits(byte[] b, int byteOffset) {
            this.b = b;
            bit = byteOffset * 8L;
        }

        long read(int n) {
            long v = 0;
            for (int i = 0; i < n; i++) {
                int by = (int) (bit >>> 3);
                if (by >= b.length) throw new IllegalArgumentException("past the end");
                v = (v << 1) | ((b[by] >> (7 - (int) (bit & 7))) & 1);
                bit++;
            }
            return v;
        }

        void align() {
            bit = (bit + 7) & ~7L;
        }

        int offset() {
            return (int) (bit >>> 3);
        }
    }
}
