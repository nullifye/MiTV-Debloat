package my.rafhan.app.tvhome;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * The Globe screen saver's sky: real stars and deep-sky objects, read from the compact catalogue
 * bundled in assets/sky.csv (HYG v3.8 to magnitude 6.5, and the Messier objects from OpenNGC).
 * Free of Android, so it's unit tested.
 *
 * A vertex is (x, y, z, r, g, b, a, sizePx, kind): x, y, z is a unit vector in the ecliptic
 * frame Globe.toEcliptic uses (x to the June solstice, y to ecliptic north, z to the March
 * equinox), so the view only has to turn it by the camera's longitude and tilt. Colours stay
 * dim: nothing white, for burn-in.
 */
final class Sky {
    /** Floats a vertex: position (3), colour and alpha (4), point size (1), kind (1). */
    static final int STRIDE = 9;
    static final int STAR = 0, DEEP_SKY = 1;

    /** J2000 mean obliquity, the epoch of the star and Messier positions. */
    private static final double OBLIQUITY = Math.toRadians(23.4392911);

    private Sky() {}

    /** Stars and deep-sky objects, faintest first so the bright ones draw on top. */
    static float[] parse(InputStream in, float density) throws IOException {
        List<Body> bodies = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            for (String line = reader.readLine(); line != null; line = reader.readLine()) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#")) continue;
                String[] f = line.split(",");
                try {
                    if (f.length >= 4 && f[0].equals("S")) {
                        double mag = Double.parseDouble(f[3]);
                        double ci = f.length > 4 && !f[4].isEmpty() ? Double.parseDouble(f[4]) : Double.NaN;
                        bodies.add(star(Double.parseDouble(f[1]), Double.parseDouble(f[2]), mag, ci, density));
                    } else if (f.length >= 6 && f[0].equals("D")) {
                        bodies.add(deepSky(Double.parseDouble(f[1]), Double.parseDouble(f[2]), f[3].charAt(0),
                                Double.parseDouble(f[4]), Double.parseDouble(f[5]), density));
                    }
                } catch (RuntimeException ignored) {
                    // A malformed line leaves one object out; the rest of the sky still draws.
                }
            }
        }
        bodies.sort(Comparator.comparingDouble(b -> b.order));
        float[] out = new float[bodies.size() * STRIDE];
        int n = 0;
        for (Body b : bodies) {
            System.arraycopy(b.vertex, 0, out, n, STRIDE);
            n += STRIDE;
        }
        return out;
    }

    /**
     * A star's direction in the ecliptic frame, from right ascension (hours) and declination
     * (degrees), both J2000. Equatorial x is the March equinox, y 90° along the equator, z the
     * north celestial pole; the ecliptic frame then puts x at the June solstice, y at ecliptic
     * north and z at the equinox, matching Globe.vector and Globe.toEcliptic.
     */
    static double[] equatorialToEcliptic(double raHours, double decDeg) {
        double ra = Math.toRadians(raHours * 15), dec = Math.toRadians(decDeg);
        double x = Math.cos(dec) * Math.cos(ra), y = Math.cos(dec) * Math.sin(ra), z = Math.sin(dec);
        double c = Math.cos(OBLIQUITY), s = Math.sin(OBLIQUITY);
        return new double[]{y * c + z * s, -y * s + z * c, x};
    }

    private static Body star(double raHours, double decDeg, double mag, double ci, float density) {
        double[] v = equatorialToEcliptic(raHours, decDeg);
        // Brightness from magnitude: Sirius (-1.5) is the brightest, 6.5 is the naked-eye limit.
        double b = clamp((6.5 - mag) / 8.0);
        float alpha = (float) (0.20 + 0.50 * b);
        float size = (float) ((1.5 + 3.5 * b) * density);
        float[] rgb = starColor(ci);
        return new Body(v, rgb, alpha, size, STAR, -mag); // faint (large magnitude) first
    }

    private static Body deepSky(double raHours, double decDeg, char type, double sizeArcmin,
                                double mag, float density) {
        double[] v = equatorialToEcliptic(raHours, decDeg);
        // Messier objects are mostly 7th–10th magnitude; the bright few get a little more colour.
        double b = clamp((9.0 - mag) / 6.0);
        float alpha = (float) (0.16 + 0.22 * b);
        float size = (float) ((5.0 + Math.min(12.0, Math.max(0, sizeArcmin) / 12.0)) * density);
        float[] rgb = deepSkyColor(type);
        return new Body(v, rgb, alpha, size, DEEP_SKY, -100 - b); // all deep-sky objects first
    }

    /** A dim colour from the star's B−V index: blue-white through yellow to orange. */
    private static float[] starColor(double ci) {
        if (Double.isNaN(ci)) return new float[]{0.70f, 0.76f, 0.84f};
        if (ci < 0.0) return new float[]{0.58f, 0.72f, 0.90f};
        if (ci < 0.4) return new float[]{0.70f, 0.78f, 0.88f};
        if (ci < 0.8) return new float[]{0.84f, 0.80f, 0.66f};
        if (ci < 1.4) return new float[]{0.88f, 0.70f, 0.50f};
        return new float[]{0.86f, 0.58f, 0.40f};
    }

    /** Galaxies cool, nebulae mauve, clusters warm, everything else neutral. */
    private static float[] deepSkyColor(char type) {
        switch (type) {
            case 'G': return new float[]{0.62f, 0.68f, 0.80f};
            case 'N': return new float[]{0.72f, 0.58f, 0.72f};
            case 'C': return new float[]{0.80f, 0.74f, 0.54f};
            default: return new float[]{0.68f, 0.72f, 0.76f};
        }
    }

    private static double clamp(double v) {
        return Math.max(0, Math.min(1, v));
    }

    private static final class Body {
        final float[] vertex = new float[STRIDE];
        final double order;

        Body(double[] v, float[] rgb, float alpha, float size, int kind, double order) {
            vertex[0] = (float) v[0];
            vertex[1] = (float) v[1];
            vertex[2] = (float) v[2];
            vertex[3] = rgb[0];
            vertex[4] = rgb[1];
            vertex[5] = rgb[2];
            vertex[6] = alpha;
            vertex[7] = size;
            vertex[8] = kind;
            this.order = order;
        }
    }
}
