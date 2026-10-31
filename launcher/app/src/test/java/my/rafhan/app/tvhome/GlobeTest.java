package my.rafhan.app.tvhome;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class GlobeTest {
    private static long ms(String iso) {
        return Instant.parse(iso).toEpochMilli();
    }

    @Test
    public void theSunIsOverheadWhereItShouldBe() {
        // June solstice 2026 (08:24 UTC): over the Tropic of Cancer.
        assertEquals(23.44, Globe.subsolar(ms("2026-06-21T08:24:00Z"))[0], 0.05);
        // March equinox 2026 (14:46 UTC): over the equator.
        assertEquals(0, Globe.subsolar(ms("2026-03-20T14:46:00Z"))[0], 0.05);
        // At 12:00 UTC on the equinox the sun is ~7 min ahead of noon at Greenwich (equation of time).
        assertEquals(1.8, Globe.subsolar(ms("2026-03-20T12:00:00Z"))[1], 0.5);
        // Six hours later it's a quarter of the way round, to the west.
        assertEquals(-88.2, Globe.subsolar(ms("2026-03-20T18:00:00Z"))[1], 0.5);
    }

    @Test
    public void thePrayerLinesMatchJakimTimesInKualaLumpur() {
        double[] kl = Globe.zonePlace("WLY01");
        // e-solat WLY01, 30 Oct 2026: Subuh 05:48, Maghrib 18:59 (UTC+8). JAKIM adds a minute or
        // two of caution to each, so the sun is a little past the line.
        assertEquals(Globe.MAGHRIB_ALT, Globe.sunAltitude(kl[0], kl[1], ms("2026-10-30T10:59:00Z")), 1.0);
        assertEquals(Globe.FAJR_ALT, Globe.sunAltitude(kl[0], kl[1], ms("2026-10-29T21:48:00Z")), 1.0);
        // Fajr is on the rising side, Maghrib on the setting side.
        assertTrue(Globe.sunRising(kl[0], kl[1], ms("2026-10-29T21:48:00Z")));
        assertTrue(!Globe.sunRising(kl[0], kl[1], ms("2026-10-30T10:59:00Z")));
        // And at Zuhur (13:00) it's high in the sky.
        assertTrue(Globe.sunAltitude(kl[0], kl[1], ms("2026-10-30T05:00:00Z")) > 60);
    }

    @Test
    public void theAxisAndTheSunSitRightInSpace() {
        long now = ms("2026-10-30T06:46:00Z");
        // The north pole leans 23.44° towards the June-solstice side of the orbit.
        double[] pole = Globe.toEcliptic(new double[]{0, 1, 0}, now);
        assertEquals(Math.sin(Math.toRadians(23.44)), pole[0], 1e-3);
        assertEquals(Math.cos(Math.toRadians(23.44)), pole[1], 1e-3);
        assertEquals(0, pole[2], 1e-9);
        // The sun lies in the orbit's plane, in its real direction for the date:
        // ~217° (late October), 90° (June solstice), 0° (March equinox).
        String[][] dates = {{"2026-10-30T06:46:00Z", "217"}, {"2026-06-21T08:24:00Z", "90"}, {"2026-03-20T14:46:00Z", "0"}};
        for (String[] d : dates) {
            long t = ms(d[0]);
            double[] s = Globe.subsolar(t), sun = Globe.toEcliptic(Globe.vector(s[0], s[1]), t);
            assertEquals(d[0], 0, sun[1], 1e-3);
            double lon = Math.toDegrees(Math.atan2(sun[0], sun[2]));
            assertEquals(d[0], 0, Globe.wrap(lon - Double.parseDouble(d[1])), 1.0);
        }
    }

    @Test
    public void vectorsAndGreatCircles() {
        double[] v = Globe.vector(0, 90);
        assertEquals(1, v[0], 1e-9);
        assertEquals(0, v[1], 1e-9);
        double[] kl = Globe.zonePlace("WLY01");
        List<double[]> arc = Globe.greatCircle(kl[0], kl[1], Globe.MAKKAH_LAT, Globe.MAKKAH_LON, 10);
        assertEquals(11, arc.size());
        double[] end = arc.get(10), makkah = Globe.vector(Globe.MAKKAH_LAT, Globe.MAKKAH_LON);
        for (int i = 0; i < 3; i++) assertEquals(makkah[i], end[i], 1e-9);
        for (double[] p : arc) assertEquals(1, Math.sqrt(p[0] * p[0] + p[1] * p[1] + p[2] * p[2]), 1e-9);
        assertEquals(-170, Globe.wrap(190), 1e-9);
        assertEquals(180, Globe.wrap(-180), 1e-9);
    }

    @Test
    public void everyJakimZoneHasAPlace() throws Exception {
        String xml = new String(Files.readAllBytes(Paths.get("src/main/res/values/zones.xml")), StandardCharsets.UTF_8);
        Matcher m = Pattern.compile("<item>[^|]*\\|(\\w+)\\|").matcher(xml);
        int n = 0;
        while (m.find()) {
            assertTrue(m.group(1), Globe.knowsZone(m.group(1)));
            n++;
        }
        assertEquals(60, n);
    }

    @Test
    public void readsOpenStreetMapChangesets() {
        String xml = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<osm version=\"0.6\">\n"
                + "  <changeset id=\"191221563\" created_at=\"2026-10-30T05:14:04Z\" closed_at=\"2026-10-30T06:14:05Z\""
                + " open=\"false\" num_changes=\"1\" user=\"someone\" uid=\"1\" min_lat=\"46.9755844\" max_lat=\"46.9755844\""
                + " min_lon=\"-123.8286637\" max_lon=\"-123.8286637\" comments_count=\"0\">\n"
                + "    <tag k=\"comment\" v=\"Specify whether pedestrian crossings have markings\"/>\n"
                + "    <tag k=\"created_by\" v=\"StreetComplete 64.0\"/>\n"
                + "  </changeset>\n"
                + "  <changeset id=\"2\" closed_at=\"2026-10-30T06:14:08Z\" min_lat=\"36.0\" max_lat=\"37.0\""
                + " min_lon=\"33.0\" max_lon=\"34.0\">\n"
                + "    <tag k=\"comment\" v=\"Roads &amp; &quot;paths&quot; &#233;\"/>\n"
                + "  </changeset>\n"
                + "  <changeset id=\"3\" closed_at=\"2026-10-30T06:14:09Z\" num_changes=\"0\"/>\n"
                + "  <changeset id=\"4\" min_lat=\"1\" max_lat=\"3\" min_lon=\"5\" max_lon=\"7\"/>\n"
                + "</osm>\n";
        List<Globe.Spot> s = Globe.parseOsm(xml);
        assertEquals(3, s.size()); // the empty changeset has no place
        assertEquals(46.9755844, s.get(0).lat, 1e-9);
        assertEquals(-123.8286637, s.get(0).lon, 1e-9);
        assertEquals("Specify whether pedestrian crossings have markings", s.get(0).text);
        assertEquals(ms("2026-10-30T06:14:05Z"), s.get(0).time);
        assertEquals(36.5, s.get(1).lat, 1e-9);
        assertEquals("Roads & \"paths\" é", s.get(1).text);
        assertEquals("", s.get(2).text);
        assertEquals(0, Globe.parseOsm("not xml").size());
        assertEquals(0, Globe.parseOsm(null).size());
    }

    @Test
    public void findsTheOsmMinuteFile() {
        assertEquals(7247203, Globe.parseOsmSequence("---\nlast_run: 2026-10-30 06:15:43 +00:00\nsequence: 7247203\n"));
        assertEquals(-1, Globe.parseOsmSequence("<html>302</html>"));
        assertEquals("007/247/203.osm.gz", Globe.osmPath(7247203));
    }

    @Test
    public void picksTheGfsRunAndStepForNow() {
        // 10:59 UTC: the 06 UTC run is out (it takes ~4 h); its step nearest to now is +6 h (12:00).
        String url = Globe.gfsWindUrl(ms("2026-10-30T10:59:00Z"), 0);
        assertTrue(url, url.contains("dir=%2Fgfs.20261030%2F06%2Fatmos&file=gfs.t06z.pgrb2.1p00.f006"));
        // One run further back: 00 UTC, its 12 h step.
        url = Globe.gfsWindUrl(ms("2026-10-30T10:59:00Z"), 1);
        assertTrue(url, url.contains("dir=%2Fgfs.20261030%2F00%2Fatmos&file=gfs.t00z.pgrb2.1p00.f012"));
        // Just after midnight the newest likely run is yesterday's 18 UTC.
        url = Globe.gfsWindUrl(ms("2026-10-31T01:00:00Z"), 0);
        assertTrue(url, url.contains("dir=%2Fgfs.20261030%2F18%2Fatmos&file=gfs.t18z.pgrb2.1p00.f006"));
        assertTrue(url.endsWith("&var_UGRD=on&var_VGRD=on&lev_10_m_above_ground=on"));
    }

    @Test
    public void readsTheAuroraForecast() {
        String json = "{\"Observation Time\": \"2026-10-30T07:09:00Z\", \"Data Format\": \"[Longitude, Latitude, Aurora]\","
                + " \"coordinates\": [[0, -90, 2], [0, 67, 100], [359, 67, 40], [180, 90, 7], [10, 95, 50]]}";
        byte[] g = Globe.parseAurora(json);
        int w = Globe.AURORA_W;
        assertEquals(181 * 360, g.length);
        assertEquals(5, g[180 * w + 180] & 0xFF);       // lon 0 is the middle column; −90° the last row
        assertEquals(255, g[(90 - 67) * w + 180] & 0xFF);
        assertEquals(102, g[(90 - 67) * w + 179] & 0xFF); // lon 359 = −1°
        assertEquals(18, g[0] & 0xFF);                   // lon 180 = −180°, the first column; 90° N first row
        assertEquals(null, Globe.parseAurora("{\"error\": 1}"));
        assertEquals(null, Globe.parseAurora(null));
    }

    @Test
    public void tellsSnowAndIceFromTheMapColours() {
        assertEquals(0, Globe.snowLevel(0xFF52626A));    // land without snow
        assertEquals(0, Globe.snowLevel(0x00000000));    // sea (transparent)
        assertTrue(Globe.snowLevel(0xFF595E6F) < 40);    // a trace
        assertEquals(255, Globe.snowLevel(0xFFEB0BE0));  // deep snow (magenta)
        assertEquals(255, Globe.snowLevel(0xFF0000FF));
        assertEquals(230, Globe.iceLevel(0xFFFFBBBB));
        assertEquals(0, Globe.iceLevel(0x00FFFFFF));
    }

    @Test
    public void readsUsgsQuakes() {
        String json = "{\"type\":\"FeatureCollection\",\"metadata\":{\"count\":3},\"features\":["
                + "{\"type\":\"Feature\",\"properties\":{\"mag\":4.8,\"place\":\"Fiji region\",\"time\":1793337019806,"
                + "\"updated\":1793338023040,\"magType\":\"mb\"},\"geometry\":{\"type\":\"Point\","
                + "\"coordinates\":[-177.0471,-18.1766,371.793]},\"id\":\"us7000tu6i\"},\n"
                + "{\"type\":\"Feature\",\"properties\":{\"mag\":2.6,\"place\":\"5 km S of Pe\\u00f1a, \\\"PR\\\"\","
                + "\"time\":1793336311595},\"geometry\":{\"type\":\"Point\",\"coordinates\":[142.3621,40.3108,56.598]}},\n"
                + "{\"type\":\"Feature\",\"properties\":{\"mag\":null,\"place\":null,\"time\":1},"
                + "\"geometry\":{\"type\":\"Point\",\"coordinates\":[1,2,3]}}]}";
        List<Globe.Spot> q = Globe.parseQuakes(json);
        assertEquals(2, q.size());
        assertEquals(-18.1766, q.get(0).lat, 1e-9);
        assertEquals(-177.0471, q.get(0).lon, 1e-9);
        assertEquals(4.8f, q.get(0).mag, 1e-6);
        assertEquals(1793337019806L, q.get(0).time);
        assertEquals("Fiji region", q.get(0).text);
        assertEquals("5 km S of Peña, \"PR\"", q.get(1).text);
        assertEquals(0, Globe.parseQuakes("{}").size());
        assertEquals(0, Globe.parseQuakes(null).size());
    }

    @Test
    public void realTimesWanderNeverOutpacesTheEarth() {
        double fastest = 0, maxLon = 0, maxTilt = 0;
        long t0 = java.time.Instant.parse("2026-10-31T00:00:00Z").toEpochMilli();
        for (long t = t0; t < t0 + 8 * 3_600_000L; t += 60_000) {
            double[] a = GlobeView.wander(t), b = GlobeView.wander(t + 60_000);
            fastest = Math.max(fastest, Math.abs(b[0] - a[0]));
            maxLon = Math.max(maxLon, Math.abs(a[0]));
            maxTilt = Math.max(maxTilt, Math.abs(a[1]));
        }
        assertTrue(fastest < 360.0 / 1436); // degrees a minute: the Earth turns once a sidereal day
        assertTrue(maxLon > 7.9 && maxLon <= 8); // it does wander, by up to 8° sideways…
        assertTrue(maxTilt > 19.9 && maxTilt <= 20); // …and 20° north and south
    }
}
