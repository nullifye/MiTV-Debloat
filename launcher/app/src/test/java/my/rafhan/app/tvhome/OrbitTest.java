package my.rafhan.app.tvhome;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

import org.junit.Test;

import java.time.Instant;
import java.util.List;

/** Against the reference SGP4 (Vallado's, via the sgp4 2.25 Python package, WGS-72). */
public class OrbitTest {
    private static final String ISS = "ISS (ZARYA)             \n"
            + "1 25544U 98067A   26302.56368278  .00005507  00000+0  10765-3 0  9996\n"
            + "2 25544  51.6333 355.1294 0007049 324.3141  35.7375 15.49100842592583\n";
    private static final String CSS = "CSS (TIANHE)            \n"
            + "1 48274U 21035A   26302.83066373  .00006003  00000+0  72695-4 0  9996\n"
            + "2 48274  41.4690 223.9968 0003720 232.1880 127.8622 15.61232232314199\n";
    private static final String GPS = "GPS BIIR-5  (PRN 22)    \n"
            + "1 26407U 00040A   26302.15465098  .00000025  00000+0  00000+0 0  9999\n"
            + "2 26407  54.8284 209.8469 0115811 304.7949 228.0606  2.00558686192638\n";

    private static void near(double[] want, double[] got, double km) {
        assertNotNull(got);
        double d = Math.sqrt(Math.pow(want[0] - got[0], 2) + Math.pow(want[1] - got[1], 2) + Math.pow(want[2] - got[2], 2));
        if (d > km) throw new AssertionError("off by " + d + " km");
    }

    @Test
    public void readsCelestrakFiles() {
        List<Orbit> all = Orbit.parse(ISS + CSS + "garbage\n1 nonsense\n2 nonsense\n" + GPS);
        assertEquals(3, all.size());
        assertEquals("ISS (ZARYA)", all.get(0).name);
        assertEquals(25544, all.get(0).catalog);
        assertEquals(48274, all.get(1).catalog);
        // 2026, day 302.56368278 (29 October 13:31:42.192 UTC).
        assertEquals(Instant.parse("2026-10-29T13:31:42.192Z").toEpochMilli(), all.get(0).epochMs, 1);
        assertEquals(0, Orbit.parse(null).size());
    }

    @Test
    public void spaceStationsMatchTheReference() {
        Orbit iss = Orbit.parse(ISS).get(0), css = Orbit.parse(CSS).get(0);
        near(new double[]{6769.8098, -576.8817, -0.0043}, iss.position(0), 0.01);
        near(new double[]{6563.2559, -1420.4916, -1037.7641}, iss.position(90), 0.01);
        near(new double[]{-514.6464, -4190.7568, -5329.5980}, iss.position(720), 0.01);
        near(new double[]{-6709.5298, 1118.2819, -53.7128}, iss.position(1440), 0.01);
        near(new double[]{-4865.4317, -4697.9621, -0.0035}, css.position(0), 0.01);
        near(new double[]{-5110.1171, 1700.4637, -4091.9910}, css.position(720), 0.01);
        near(new double[]{1266.7078, 5752.6107, -3321.2339}, css.position(1440), 0.01);
    }

    @Test
    public void gpsIsCloseWithoutTheMoonAndSun() {
        // Deep space: the reference adds the Moon's and Sun's pull, left out here. That's a steady
        // ~15 km at 26,500 km from the Earth's centre: under a pixel on the globe.
        Orbit gps = Orbit.parse(GPS).get(0);
        near(new double[]{24068.9728, 11297.6187, 3094.0989}, gps.position(0), 25);
        near(new double[]{13938.7723, 18514.0298, -12958.5399}, gps.position(90), 25);
        near(new double[]{23990.3393, 11550.4991, 2714.0587}, gps.position(720), 25);
        near(new double[]{23904.4334, 11799.9944, 2332.9773}, gps.position(1440), 25);
    }

    @Test
    public void theSpotBelowIsOnTheMap() {
        Orbit iss = Orbit.parse(ISS).get(0);
        double[] at = iss.below(iss.epochMs);
        // At epoch the ISS crosses the equator going north (z ≈ 0, its inclination 51.6°).
        assertEquals(0, at[0], 0.05);
        // Its right ascension (−4.87°) less sidereal time.
        double ra = Math.toDegrees(Math.atan2(-576.8817, 6769.8098));
        assertEquals(Globe.wrap(ra - Globe.siderealDegrees(iss.epochMs)), at[1], 0.01);
    }
}
