package my.rafhan.app.tvhome;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/** The sky: building its catalogue, positions in the ecliptic frame, the Sun and the Moon, and the burn-in limits. */
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
    public void buildsTheCatalogueFromHygAndOpenNgc() throws Exception {
        // Real rows: HYG's header, the Sun (a placeholder position), a 9th-magnitude star, Sirius.
        String hyg = "\"id\",\"hip\",\"hd\",\"hr\",\"gl\",\"bf\",\"proper\",\"ra\",\"dec\",\"dist\",\"pmra\","
                + "\"pmdec\",\"rv\",\"mag\",\"absmag\",\"spect\",\"ci\",\"x\",\"y\",\"z\",\"vx\",\"vy\",\"vz\","
                + "\"rarad\",\"decrad\",\"pmrarad\",\"pmdecrad\",\"bayer\",\"flam\",\"con\",\"comp\","
                + "\"comp_primary\",\"base\",\"lum\",\"var\",\"var_min\",\"var_max\"\n"
                + "0,,,,\"\",\"\",Sol,0.0,0.0,0.0,0.0,0.0,0.0,-26.7,4.85,G2V,0.656,0.000005,0.0,0.0,0.0,0.0,0.0,"
                + "0.0,0.0,0.0,0.0,\"\",\"\",\"\",1,0,\"\",1.0,\"\",,\n"
                + "1,1,224700,,\"\",\"\",\"\",0.00006,1.089009,219.7802,-5.2,-1.88,0.0,9.1,2.39,F5,0.482,219.740502,"
                + "0.003449,4.177065,0.00000004,-0.00000554,-0.000002,0.0000156934097753,0.01900678824815125,"
                + "-0.0000000252103114,-0.000000009114497,\"\",\"\",Psc,1,1,\"\",9.638290236239703,\"\",,\n"
                + "32263,32349,48915,2491,Gl 244A,\"9Alp CMa\",Sirius,6.752481,-16.716116,2.6371,-546.01,-1223.08,"
                + "-9.4,-1.44,1.454,A0m...,0.009,-0.494323,2.476731,-0.758485,0.00000953,-0.00001207,-0.00001221,"
                + "1.7677953696021995,-0.291751258517685,-0.0000026471311772,-0.000005929659164,Alp,\"9\",CMa,1,"
                + "32263,Gl 244,22.824433121735034,\"\",-1.333,-1.523\n";
        String head = "Name;Type;RA;Dec;Const;MajAx;MinAx;PosAng;B-Mag;V-Mag;J-Mag;H-Mag;K-Mag;SurfBr;Hubble;Pax;"
                + "Pm-RA;Pm-Dec;RadVel;Redshift;Cstar U-Mag;Cstar B-Mag;Cstar V-Mag;M;NGC;IC;Cstar Names;"
                + "Identifiers;Common names;NED notes;OpenNGC notes;Sources\n";
        // M1, M101, M102's duplicate entry (left out), and a non-Messier object.
        String ngc = head
                + "NGC1952;SNR;05:34:31.97;+22:00:52.1;Tau;8.00;4.00;;;8.40;;;;;;;;;;;;;;001;;;;IRAS 05315+2158,"
                + "LBN 833;Crab Nebula;Position is for the Crab pulsar.;;Type:1\n"
                + "NGC5457;G;14:03:12.54;+54:20:56.2;UMa;23.99;23.07;28;8.36;7.90;6.52;5.81;5.51;23.97;SABc;;;;243;"
                + "0.000811;;;;101;;;;UGC 08981;;;;\n"
                + "M102;Dup;14:03:12.54;+54:20:56.2;UMa;;;;;;;;;;;;;;;;;;;101;;;;;;;Controversial;Type:1\n"
                + "IC0001;**;00:08:27.05;+27:43:03.6;Peg;;;;;;;;;;;;;;;;;;;;;;;;;;;Type:1\n";
        // The addendum: M45 (the Pleiades) and M40 (a double star).
        String addendum = head
                + "Mel022;OCl;03:47:28.6;+24:06:19;Tau;150.00;150.00;90;;1.20;;;;;;7.3640;19.997;-45.548;6;0.000020;"
                + ";;;045;;;;MWSC 0305;Pleiades;;;Type:2\n"
                + "M040;**;12:22:16.1;+58:05:04;UMa;;;;;8.00;;;;;;;;;;;;;;040;;;;WDS J12222+5805AB;;;;Type:1\n";
        StringBuilder out = new StringBuilder();
        int[] n = Sky.build(in(hyg), new InputStream[]{in(ngc), in(addendum)}, out);
        assertEquals(1, n[0]); // only Sirius: the Sun and the faint star are left out
        assertEquals(4, n[1]); // M1, M40, M45, M101
        String[] lines = out.toString().split("\n");
        assertEquals("S,6.75248,-16.71612,-1.44,0.009", lines[5]);
        assertEquals("D,5.57555,22.01447,N,8.0,8.40", lines[6]); // M1, by Messier number
        assertEquals("D,12.37114,58.08444,O,0.0,8.00", lines[7]); // M40
        assertEquals("D,3.79128,24.10528,C,150.0,1.20", lines[8]); // M45
        assertEquals("D,14.05348,54.34894,G,24.0,7.90", lines[9]); // M101
        assertEquals(10, lines.length);
        assertEquals(5, Sky.parse(in(out.toString()), 1f).length / Sky.STRIDE);
    }

    @Test(expected = java.io.IOException.class)
    public void anErrorPageIsNotACatalogue() throws Exception {
        Sky.build(in("<html>404: Not Found</html>\n"), new InputStream[0], new StringBuilder());
    }

    @Test
    public void theSunStandsWhereItLightsTheGlobe() {
        // At the June solstice the Sun is at ecliptic longitude 90°: the frame's x axis.
        long solstice = java.time.Instant.parse("2026-06-21T08:24:00Z").toEpochMilli();
        double[] s = Globe.subsolar(solstice);
        double[] e = Globe.toEcliptic(Globe.vector(s[0], s[1]), solstice);
        assertEquals(1, e[0], 1e-3);
        assertEquals(0, e[1], 1e-3); // on the ecliptic
        float[] v = Sky.sun(e, 1f);
        assertEquals(Sky.STRIDE, v.length);
        assertEquals(Sky.SUN, v[8], 0);
        for (int i = 3; i < 6; i++) assertTrue(v[i] * v[6] <= 0.65f); // never white, for burn-in
    }

    @Test
    public void theMoonIsWhereTheEphemerisPutsIt() {
        // PyEphem's Moon (ecliptic of date), 2026-10-31 05:00 UTC: longitude 106.203°, latitude 3.303°.
        long t = java.time.Instant.parse("2026-10-31T05:00:00Z").toEpochMilli();
        double l = Math.toRadians(106.203), b = Math.toRadians(3.303);
        double[] want = {Math.cos(b) * Math.sin(l), Math.sin(b), Math.cos(b) * Math.cos(l)};
        assertTrue(angle(Sky.moonDirection(t), want) < 0.3); // degrees: under the Moon's own width
    }

    @Test
    public void theMoonShowsTheRealPhase() {
        // Full on 26 October 2026 at 04:12 UTC, new on 9 November at 07:02 UTC.
        assertEquals(1, light("2026-10-26T04:12:00Z")[2], 0.01); // lit face towards the Earth
        assertEquals(-1, light("2026-11-09T07:02:00Z")[2], 0.01); // dark face towards the Earth
        // First quarter (2026-11-17 11:48 UTC): lit from the side, half the disc.
        assertEquals(0, light("2026-11-17T11:48:00Z")[2], 0.06);
    }

    @Test
    public void theMoonsLitSideFacesTheSun() {
        double[] moon = {0, 0, -1}, sunRight = {1, 0, 0}; // a quarter Moon with the Sun off to the right
        float[] l = Sky.moonLight(sunRight, moon);
        assertEquals(1, l[0], 1e-6);
        assertEquals(0, l[1], 1e-6);
        assertEquals(0, l[2], 1e-6);
        float[] v = Sky.moon(moon, 1f);
        assertEquals(Sky.MOON, v[8], 0);
        for (int i = 3; i < 6; i++) assertTrue(v[i] * v[6] <= 0.65f); // never white, for burn-in
    }

    /** The Moon's light (Sky.moonLight) at a moment, from the real Sun and Moon. */
    private static float[] light(String when) {
        long t = java.time.Instant.parse(when).toEpochMilli();
        double[] s = Globe.subsolar(t);
        return Sky.moonLight(Globe.toEcliptic(Globe.vector(s[0], s[1]), t), Sky.moonDirection(t));
    }

    private static double angle(double[] a, double[] b) {
        return Math.toDegrees(Math.acos(Math.min(1, a[0] * b[0] + a[1] * b[1] + a[2] * b[2])));
    }

    private static InputStream in(String s) {
        return new ByteArrayInputStream(s.getBytes(StandardCharsets.UTF_8));
    }
}
