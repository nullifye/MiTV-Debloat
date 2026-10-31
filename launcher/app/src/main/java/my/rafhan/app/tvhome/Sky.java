package my.rafhan.app.tvhome;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * The Globe screen saver's sky: real stars and deep-sky objects, and the Sun and the Moon where
 * they stand now.
 * The stars and Messier objects come from a compact catalogue (sky.csv) that build() makes on the
 * TV from the original catalogues, HYG v3.8 (every star to magnitude 6.5) and OpenNGC (every
 * Messier object); GlobeFeeds downloads those once and keeps only the result, so none of it is
 * in the APK. Free of Android, so it's unit tested.
 *
 * A vertex is (x, y, z, r, g, b, a, sizePx, kind): x, y, z is a unit vector in the ecliptic
 * frame Globe.toEcliptic uses (x to the June solstice, y to ecliptic north, z to the March
 * equinox), so the view only has to turn it by the camera's longitude and tilt. Colours stay
 * dim: nothing white, for burn-in.
 */
final class Sky {
    /** Floats a vertex: position (3), colour and alpha (4), point size (1), kind (1). */
    static final int STRIDE = 9;
    static final int STAR = 0, DEEP_SKY = 1, SUN = 2, MOON = 3;
    /** The faintest star kept: the naked-eye limit. */
    static final double FAINTEST = 6.5;

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
     * Writes the compact catalogue parse() reads, from HYG's CSV (every star to magnitude 6.5)
     * and OpenNGC's semicolon-separated files (NGC.csv and addendum.csv: every Messier object,
     * leaving out M102's duplicate entry). HYG's first row, the Sun, is left out: its position
     * there is a placeholder (RA 0, Dec 0); the Sun is drawn where it really stands (sun()).
     * Returns {stars, deep-sky objects}; columns are found by name, and a file without them
     * (an error page) throws.
     */
    static int[] build(InputStream hyg, InputStream[] openNgc, Appendable out) throws IOException {
        out.append("# Globe sky catalogue. Generated from real catalogues; see docs/features.md for credits.\n"
                + "# Stars: HYG Database v3.8 (astronexus/HYG-Database), CC BY-SA 4.0, all stars to magnitude 6.5.\n"
                + "# Deep sky: OpenNGC (mattiaverga/OpenNGC), CC BY-SA 4.0, all Messier objects.\n"
                + "# S,ra_hours,dec_degrees,magnitude,b-v\n"
                + "# D,ra_hours,dec_degrees,type(G/N/C/O),size_arcmin,magnitude\n");
        int stars = 0;
        BufferedReader r = reader(hyg);
        String[] head = header(r, ",");
        int id = column(head, "id"), ra = column(head, "ra"), dec = column(head, "dec"), mag = column(head, "mag"),
                ci = column(head, "ci");
        for (String line = r.readLine(); line != null; line = r.readLine()) {
            String[] f = line.split(",", -1);
            if (f.length != head.length || f[id].equals("0")) continue; // the Sun
            try {
                double m = Double.parseDouble(f[mag]);
                if (m > FAINTEST) continue;
                String c = f[ci].isEmpty() ? "" : String.format(Locale.US, "%.3f", Double.parseDouble(f[ci]));
                out.append(String.format(Locale.US, "S,%.5f,%.5f,%.2f,%s\n",
                        Double.parseDouble(f[ra]), Double.parseDouble(f[dec]), m, c));
                stars++;
            } catch (NumberFormatException ignored) {
                // One unreadable row leaves one star out.
            }
        }
        Map<Integer, String> messier = new TreeMap<>(); // by Messier number
        for (InputStream in : openNgc) {
            BufferedReader n = reader(in);
            head = header(n, ";");
            int type = column(head, "Type"), ra2 = column(head, "RA"), dec2 = column(head, "Dec"),
                    size = column(head, "MajAx"), b = column(head, "B-Mag"), v = column(head, "V-Mag"),
                    m = column(head, "M");
            for (String line = n.readLine(); line != null; line = n.readLine()) {
                String[] f = line.split(";", -1);
                if (f.length < head.length || f[m].isEmpty() || f[type].equals("Dup")) continue;
                try {
                    String bright = !f[v].isEmpty() ? f[v] : !f[b].isEmpty() ? f[b] : "99";
                    messier.put(Integer.parseInt(f[m]), String.format(Locale.US, "D,%.5f,%.5f,%c,%.1f,%.2f\n",
                            sexagesimal(f[ra2]), sexagesimal(f[dec2]), deepSkyType(f[type]),
                            f[size].isEmpty() ? 0 : Double.parseDouble(f[size]), Double.parseDouble(bright)));
                } catch (RuntimeException ignored) {
                    // One unreadable row leaves one object out.
                }
            }
        }
        for (String line : messier.values()) out.append(line);
        return new int[]{stars, messier.size()};
    }

    /** OpenNGC's object types, in the catalogue's four: galaxy, nebula, cluster, other. */
    static char deepSkyType(String type) {
        switch (type) {
            case "G": case "GPair": case "GTrpl": case "GGroup":
                return 'G';
            case "PN": case "HII": case "EmN": case "Neb": case "RfN": case "SNR": case "DrkN": case "Cl+N":
                return 'N';
            case "OCl": case "GCl": case "*Ass":
                return 'C';
            default:
                return 'O';
        }
    }

    /** "05:34:31.94" or "+22:00:52.2" as hours or degrees. */
    static double sexagesimal(String s) {
        double sign = s.startsWith("-") ? -1 : 1;
        String[] p = s.replaceFirst("^[+-]", "").split(":");
        double v = 0;
        for (int i = p.length - 1; i >= 0; i--) v = v / 60 + Double.parseDouble(p[i]);
        return sign * v;
    }

    private static BufferedReader reader(InputStream in) {
        return new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
    }

    private static String[] header(BufferedReader r, String separator) throws IOException {
        String line = r.readLine();
        if (line == null) throw new IOException("empty catalogue");
        String[] head = line.split(separator, -1);
        for (int i = 0; i < head.length; i++) head[i] = head[i].replace("\"", "").trim();
        return head;
    }

    private static int column(String[] head, String name) throws IOException {
        for (int i = 0; i < head.length; i++) if (head[i].equals(name)) return i;
        throw new IOException("not the catalogue: no " + name + " column");
    }

    /**
     * The Sun as one vertex, at its direction in the ecliptic frame (Globe.toEcliptic of the
     * subsolar point, so it lines up with the light on the globe). The sky's brightest point but
     * a warm amber, never white, for burn-in: no more than the sun glint's 65 %.
     */
    static float[] sun(double[] ecliptic, float density) {
        return new Body(ecliptic, new float[]{0.92f, 0.78f, 0.50f}, 0.70f, 26 * density, SUN, 0).vertex;
    }

    /**
     * The Moon as one vertex: a grey disc, no brighter than the Sun, that GlobeView's shader
     * lights with moonLight() so it shows the phase it has tonight.
     */
    static float[] moon(double[] ecliptic, float density) {
        return new Body(ecliptic, new float[]{0.80f, 0.80f, 0.77f}, 0.72f, 18 * density, MOON, 0).vertex;
    }

    /**
     * Where the Moon is, seen from the Earth's centre: a unit vector in the ecliptic frame. The
     * Astronomical Almanac's low-precision series (ecliptic longitude and latitude of date):
     * within about 0.2° of a full ephemeris, less than the Moon's own width.
     */
    static double[] moonDirection(long epochMs) {
        double t = (epochMs / 86_400_000.0 + 2440587.5 - 2451545.0) / 36525; // centuries since J2000.0
        double lon = 218.32 + 481267.881 * t
                + 6.29 * sin(135.0 + 477198.87 * t) - 1.27 * sin(259.3 - 413335.36 * t)
                + 0.66 * sin(235.7 + 890534.22 * t) + 0.21 * sin(269.9 + 954397.74 * t)
                - 0.19 * sin(357.5 + 35999.05 * t) - 0.11 * sin(186.5 + 966404.03 * t);
        double lat = 5.13 * sin(93.3 + 483202.02 * t) + 0.28 * sin(228.2 + 960400.89 * t)
                - 0.28 * sin(318.3 + 6003.15 * t) - 0.17 * sin(217.6 - 407332.21 * t);
        double l = Math.toRadians(lon), b = Math.toRadians(lat);
        // x to the June solstice (longitude 90°), y to ecliptic north, z to the March equinox.
        return new double[]{Math.cos(b) * Math.sin(l), Math.sin(b), Math.cos(b) * Math.cos(l)};
    }

    /**
     * Where the light falls on the Moon, for the shader: its phase as seen from the Earth (so the
     * real one on any date: full when it stands opposite the Sun, new when beside it), with the
     * lit side turned towards the Sun on screen. In: the Sun's and the Moon's directions in view
     * space (+z towards the viewer). Out: the light's direction in the disc's frame, x right,
     * y up and z towards whoever looks at it from the Earth.
     */
    static float[] moonLight(double[] sun, double[] moon) {
        double cosE = sun[0] * moon[0] + sun[1] * moon[1] + sun[2] * moon[2]; // elongation's cosine
        double sinE = Math.sqrt(Math.max(0, 1 - cosE * cosE));
        // Towards the Sun across the sky from the Moon, as the screen shows it.
        double px = sun[0] - cosE * moon[0], py = sun[1] - cosE * moon[1], len = Math.hypot(px, py);
        if (len < 1e-9) return new float[]{0, 0, (float) -cosE};
        return new float[]{(float) (px / len * sinE), (float) (py / len * sinE), (float) -cosE};
    }

    private static double sin(double degrees) {
        return Math.sin(Math.toRadians(degrees));
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
