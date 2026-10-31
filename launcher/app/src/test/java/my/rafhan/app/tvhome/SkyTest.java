package my.rafhan.app.tvhome;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;

/** The bundled sky: catalogue positions, the ecliptic frame, and the burn-in limits. */
public class SkyTest {
    private static final double EPS = Math.toRadians(23.4392911);

    @Test
    public void equatorialToEclipticUsesTheMarchEquinoxAndEclipticNorth() {
        double[] equinox = Sky.equatorialToEcliptic(0, 0);
        assertEquals(0, equinox[0], 1e-9); // June solstice
        assertEquals(0, equinox[1], 1e-9); // ecliptic north
        assertEquals(1, equinox[2], 1e-9); // March equinox

        // RA 6 h on the equator is ecliptic longitude 90°, latitude −obliquity.
        double[] solstice = Sky.equatorialToEcliptic(6, 0);
        assertEquals(Math.cos(EPS), solstice[0], 1e-9);
        assertEquals(-Math.sin(EPS), solstice[1], 1e-9);
        assertEquals(0, solstice[2], 1e-9);

        // The north celestial pole is 66.56° up the June-solstice meridian.
        double[] pole = Sky.equatorialToEcliptic(12, 90);
        assertEquals(Math.sin(EPS), pole[0], 1e-9);
        assertEquals(Math.cos(EPS), pole[1], 1e-9);
        assertEquals(0, pole[2], 1e-9);
    }

    @Test
    public void parsesStarsAndDeepSkyFaintestFirst() throws Exception {
        String csv = "# header\n"
                + "S,0,0,6.5,0.5\n"
                + "S,6,0,-1.5,-0.2\n"
                + "D,6,0,G,10,8\n";
        float[] v = Sky.parse(new ByteArrayInputStream(csv.getBytes(StandardCharsets.UTF_8)), 1f);
        assertEquals(3 * Sky.STRIDE, v.length);
        // The galaxy draws first, then the faint star, then Sirius-bright.
        assertEquals(Sky.DEEP_SKY, v[8], 0);
        assertEquals(Sky.STAR, v[Sky.STRIDE + 8], 0);
        assertEquals(Sky.STAR, v[2 * Sky.STRIDE + 8], 0);
        assertTrue(v[2 * Sky.STRIDE + 6] > v[Sky.STRIDE + 6]); // the brighter star is stronger
        for (int i = 0; i < v.length; i += Sky.STRIDE) {
            assertEquals(1, Math.hypot(Math.hypot(v[i], v[i + 1]), v[i + 2]), 1e-6);
            assertTrue(v[i + 7] > 0);
            assertTrue(v[i + 6] > 0 && v[i + 6] <= 0.7f); // dim for burn-in
            assertTrue(v[i + 3] <= 0.9f && v[i + 4] <= 0.9f && v[i + 5] <= 0.9f); // nothing white
        }
    }

    @Test
    public void bundledCatalogueHasNakedEyeStarsAndEveryMessierObject() throws Exception {
        try (InputStream in = Files.newInputStream(Paths.get("src/main/assets/sky.csv"))) {
            float[] v = Sky.parse(in, 1f);
            int stars = 0, deepSky = 0;
            for (int i = 0; i < v.length; i += Sky.STRIDE) {
                assertEquals(1, Math.hypot(Math.hypot(v[i], v[i + 1]), v[i + 2]), 1e-5);
                if (v[i + 8] == Sky.DEEP_SKY) deepSky++;
                else stars++;
            }
            assertEquals(8920, stars); // HYG v3.8 to magnitude 6.5, without the Sun
            assertEquals(109, deepSky); // every Messier object, M102's duplicate left out
        }
    }
}
