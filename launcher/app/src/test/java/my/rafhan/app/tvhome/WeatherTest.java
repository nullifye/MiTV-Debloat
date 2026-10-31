package my.rafhan.app.tvhome;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/** The Weather card's parsing and choices, on real responses captured on 31 October 2026. */
public class WeatherTest {
    @Test
    public void picksTodaysForecastWhateverTheOrder() throws Exception {
        String json = resource("weather-forecast-Tn181.json"); // Klang, 7 days, not in date order
        Weather.Day d = Weather.parseForecast(json, "Tn181", LocalDate.of(2026, 10, 31));
        assertNotNull(d);
        assertEquals(24, d.min);
        assertEquals(32, d.max);
        assertEquals("No rain", Weather.outlook(d));
        assertEquals(Weather.SUN, Weather.kind(d.summary));
        Weather.Day later = Weather.parseForecast(json, "Tn181", LocalDate.of(2026, 11, 6));
        assertEquals("Thunderstorms, afternoon", Weather.outlook(later));
        assertEquals("Storms pm", Weather.shortOutlook(later));
        // Morning, evening (MET's "petang") and night, each with its own icon on the card.
        assertEquals("No rain", Weather.word(later.morning));
        assertEquals("Storms", Weather.word(later.afternoon));
        assertEquals(Weather.STORM, Weather.kind(later.afternoon));
        assertEquals(Weather.SUN, Weather.kind(later.night));
        assertNull(Weather.parseForecast(json, "Tn181", LocalDate.of(2026, 12, 1))); // out of range
        assertNull(Weather.parseForecast(json, "Ds054", LocalDate.of(2026, 10, 31))); // another place
    }

    @Test
    public void readsEveryApimsStation() throws Exception {
        List<Weather.Station> all = Weather.parseStations(resource("weather-stations.json"));
        assertEquals(68, all.size());
        Weather.Station klang = Weather.station(all, "CA21B");
        assertNotNull(klang);
        assertEquals("Klang", klang.place);
        assertEquals("Selangor", klang.state);
        assertEquals(74, klang.api);
        assertEquals("Moderate", Weather.apiBand(klang.api));
        assertEquals("PM2.5", Weather.pollutant(klang.pollutant));
        // The reading's hour is Malaysian time, stored as if it were UTC.
        assertEquals(LocalDateTime.of(2026, 10, 31, 15, 0), klang.time);
    }

    @Test
    public void nearestStationToTheZonesTown() throws Exception {
        List<Weather.Station> all = Weather.parseStations(resource("weather-stations.json"));
        double[] klang = Globe.zonePlace("SGR03");
        assertEquals("CA21B", Weather.nearest(all, klang[0], klang[1]).id);
        double[] kl = Globe.zonePlace("WLY01");
        assertEquals("CA16W", Weather.nearest(all, kl[0], kl[1]).id); // Cheras, Kuala Lumpur
        assertNull(Weather.nearest(java.util.Collections.emptyList(), 3, 101));
    }

    @Test
    public void autoPlaceFollowsThePrayerZone() throws Exception {
        List<Weather.Place> places = Weather.parsePlaces(resource("weather-places.json"));
        assertEquals("Tn181", Weather.autoPlace("Selangor", "Klang, Kuala Langat", places).id); // town before district
        assertEquals("Tn079", Weather.autoPlace("Wilayah Persekutuan", "Kuala Lumpur, Putrajaya", places).id);
        assertEquals("St012", Weather.autoPlace("Melaka", "Seluruh Negeri Melaka", places).id); // the state
        assertEquals("Rc024", Weather.autoPlace("Pahang", "Pulau Tioman", places).id);
        assertEquals("St013", Weather.autoPlace("Johor", "Pulau Aur dan Pulau Pemanggil", places).id);
        assertEquals("Town", Weather.place(places, "Tn181").type());
    }

    @Test
    public void copernicusAirQuality() {
        String json = "{\"latitude\":3.0,\"longitude\":101.399994,\"current\":{\"time\":\"2026-10-31T15:00\","
                + "\"interval\":3600,\"us_aqi\":144,\"pm2_5\":32.2,\"pm10\":32.5,\"aerosol_optical_depth\":0.78}}";
        Weather.Air a = Weather.parseAir(json);
        assertNotNull(a);
        assertEquals(144, a.aqi);
        assertEquals(32.2, a.pm25, 1e-9);
        assertEquals(0.78, a.haze, 1e-9); // aerosol optical depth: hazy
        assertEquals(LocalDateTime.of(2026, 10, 31, 15, 0), a.time); // shown as Copernicus's reading time
        assertEquals("Unhealthy for sensitive groups", Weather.usAqiBand(a.aqi));
    }

    @Test
    public void everyPhraseMetUsesHasEnglish() {
        // Every summary and period seen across all 440 places over 7 days on 31 October 2026.
        String[] phrases = {"Tiada Hujan", "Ribut petir", "Ribut petir di beberapa tempat", "Hujan di beberapa tempat",
                "Hujan", "Ribut petir di kebanyakan tempat", "Ribut petir di beberapa tempat di kawasan pedalaman",
                "Hujan di kebanyakan tempat", "Hujan di beberapa tempat di kawasan pedalaman", "Mendung",
                "Ribut petir menyeluruh", "Hujan di beberapa tempat di kawasan pantai", "Hujan menyeluruh",
                "Ribut petir di kebanyakan tempat di kawasan pedalaman",
                "Hujan di kebanyakan tempat di kawasan pedalaman"};
        for (String p : phrases) assertNotEquals(p, Weather.english(p));
        assertEquals("Thunderstorms in places, inland",
                Weather.english("Ribut petir di beberapa tempat di kawasan pedalaman"));
        assertEquals("Widespread rain", Weather.english("Hujan menyeluruh"));
        assertEquals("Cloudy", Weather.english("Mendung"));
        assertEquals("Something new", Weather.english("Something new")); // unknown: left as it is
        String[] whens = {"Petang dan Malam", "Petang", "Sepanjang Hari", "Pagi dan Petang", "Pagi", "Malam", "Pagi dan Malam"};
        for (String w : whens) assertNotEquals(w, Weather.englishWhen(w));
        assertEquals("afternoon and night", Weather.englishWhen("Petang dan Malam"));
    }

    @Test
    public void hourlyReadingsAreFetchedWhenTheNextOneIsOut() {
        long retry = 10 * 60_000L, long_ago = 60 * 60_000L;
        LocalDateTime five = LocalDateTime.of(2026, 10, 31, 17, 0);
        // APIMS (out at :10): at 17:05 the 17:00 reading isn't out yet, so 16:00 is still the newest.
        assertTrue(!Weather.hourlyDue(LocalDateTime.of(2026, 10, 31, 16, 0), five.plusMinutes(5), 10, long_ago, retry));
        // From 17:10 it should be: fetch.
        assertTrue(Weather.hourlyDue(LocalDateTime.of(2026, 10, 31, 16, 0), five.plusMinutes(10), 10, long_ago, retry));
        // Tried at 17:10 and it wasn't out: not again before 17:20.
        assertTrue(!Weather.hourlyDue(LocalDateTime.of(2026, 10, 31, 16, 0), five.plusMinutes(15), 10, 5 * 60_000L, retry));
        // Have 17:00: nothing more this hour.
        assertTrue(!Weather.hourlyDue(five, five.plusMinutes(50), 10, long_ago, retry));
        // Copernicus (out on the hour, give it a minute): a new hour, a new value.
        assertTrue(Weather.hourlyDue(five, five.plusHours(1).plusMinutes(1), 1, long_ago, retry));
        assertTrue(Weather.hourlyDue(null, five, 1, long_ago, retry)); // nothing kept yet
    }

    @Test
    public void metWarningsInForceForThePlace() throws Exception {
        // 31 October 2026: thunderstorms 14:00–20:00 over "Selangor (Sepang, Kuala Langat, Klang and
        // Petaling)" and more; strong winds at sea 13:00–17:00; and a "No Advisory" with no times.
        List<Weather.Warning> all = Weather.parseWarnings(resource("weather-warnings.json"));
        assertEquals(2, all.size());
        LocalDateTime evening = LocalDateTime.of(2026, 10, 31, 16, 30);
        Weather.Warning w = Weather.activeWarning(all, evening, java.util.Arrays.asList("Klang"));
        assertNotNull(w);
        assertEquals("Thunderstorms warning", Weather.warningName(w));
        assertEquals(LocalDateTime.of(2026, 10, 31, 20, 0), w.to);
        assertNotNull(Weather.activeWarning(all, evening, java.util.Arrays.asList("Shah Alam", "Kuala Langat")));
        assertNull(Weather.activeWarning(all, evening, java.util.Arrays.asList("Kuching"))); // not named
        assertNull(Weather.activeWarning(all, LocalDateTime.of(2026, 10, 31, 20, 30),
                java.util.Arrays.asList("Klang"))); // over
        assertNull(Weather.activeWarning(all, evening, java.util.Arrays.asList("Lang"))); // whole words only
        // The sea warning (13:00–17:00, "the waters of East Johor • Pahang…") isn't shown.
        assertNull(Weather.activeWarning(all, LocalDateTime.of(2026, 10, 31, 13, 30),
                java.util.Arrays.asList("Pahang")));
        assertTrue(Weather.parseWarnings("<html>").isEmpty());
    }

    @Test
    public void bandEdges() {
        assertEquals("Good", Weather.apiBand(50));
        assertEquals("Moderate", Weather.apiBand(51));
        assertEquals("Unhealthy", Weather.apiBand(101));
        assertEquals("Very unhealthy", Weather.apiBand(201));
        assertEquals("Hazardous", Weather.apiBand(301));
        assertEquals("Moderate", Weather.usAqiBand(100));
        assertEquals("Unhealthy", Weather.usAqiBand(151));
        assertEquals("Hazardous", Weather.usAqiBand(301));
        assertNotEquals(Weather.apiColor(50), Weather.apiColor(51));
    }

    @Test
    public void bandColours() {
        // The DOE's blue, green, yellow, orange, red, as pastels; the System card's green, amber, red.
        assertEquals(0xFF6AAEF5, Weather.apiColor(30)); // Good, blue
        assertEquals(0xFF63D489, Weather.apiColor(74)); // Moderate, green (the System card's)
        assertEquals(0xFFF2DC6B, Weather.apiColor(150)); // Unhealthy, yellow
        assertEquals(0xFFFFB340, Weather.apiColor(250)); // Very unhealthy, orange (the System card's amber)
        assertEquals(0xFFFF6B5E, Weather.apiColor(400)); // Hazardous, red (the System card's)
        assertEquals(0xFFFFB340, Weather.usAqiColor(144)); // Unhealthy for sensitive groups, orange
    }

    @Test
    public void apimsTrendOverThreeHours() throws Exception {
        // Klang's last 24 hours on 31 October 2026: 75 at 17:00, 74, 74, 73 at 14:00, 72, 71 at noon…
        List<Weather.Reading> klang = Weather.parseHourly(resource("weather-hourly-CA21B.json"), "CA21B");
        assertEquals(24, klang.size());
        assertEquals(75, klang.get(0).api); // newest first
        assertEquals(LocalDateTime.of(2026, 10, 31, 17, 0), klang.get(0).time);
        assertEquals(Integer.valueOf(2), Weather.change(klang, null, 3)); // the newest: 73 at 14:00 → 75 at 17:00
        // The reading the card shows may be an hour behind the table: 72 at 13:00 → 74 at 16:00.
        assertEquals(Integer.valueOf(2), Weather.change(klang, LocalDateTime.of(2026, 10, 31, 16, 0), 3));
        assertNull(Weather.change(klang, LocalDateTime.of(2026, 10, 31, 18, 0), 3)); // not in the table yet
        assertEquals("↑ Rising · +2 in 3 h", Weather.trend(2, 3));
        assertEquals("↓ Falling · −5 in 3 h", Weather.trend(-5, 3));
        assertEquals("→ Steady", Weather.trend(1, 3));
        assertEquals("", Weather.trend(null, 3)); // no history yet: nothing shown
        assertTrue(Weather.parseHourly(resource("weather-hourly-CA21B.json"), "CA16W").isEmpty());
        assertNull(Weather.change(java.util.Collections.emptyList(), null, 3));
    }

    @Test
    public void brokenResponsesMeanNoData() {
        for (String bad : new String[]{"", "<html>502</html>", "{}", "[]", "{\"error\":\"x\"}"}) {
            assertNull(Weather.parseForecast(bad, "Tn181", LocalDate.of(2026, 10, 31)));
            assertTrue(Weather.parsePlaces(bad).isEmpty());
            assertTrue(Weather.parseStations(bad).isEmpty());
            assertNull(Weather.parseAir(bad));
        }
    }

    private static String resource(String name) throws Exception {
        try (InputStream in = WeatherTest.class.getClassLoader().getResourceAsStream(name)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
