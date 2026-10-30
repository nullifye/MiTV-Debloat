package my.rafhan.app.tvhome;

import android.content.Context;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.zip.GZIPInputStream;

/**
 * What the Globe screen saver draws from the internet:
 *  - its two maps, NASA's Earth by day and at night (public domain, from Wikimedia Commons),
 *    downloaded once (about 0.5 MB) and kept in the app's files dir, like the wallpapers;
 *  - two live feeds, fetched only while the screen saver runs: OpenStreetMap's changesets of
 *    the last minute (every 60 s) and USGS's M2.5+ earthquakes of the past day (every 5 min). A
 *    feed that fails just leaves its layer empty;
 *  - the orbits of the ISS, China's Tiangong and the Hubble Space Telescope (CelesTrak's two-line
 *    elements), kept in the files dir and fetched again when 12 hours old (CelesTrak asks for
 *    no more than every 2 hours). Where each one is comes from Orbit, on the TV;
 *  - the weather layers, kept in the files dir too: clouds (Live Cloud Maps, from EUMETSAT's
 *    satellites, new every 3 hours), snow and sea ice (NASA GIBS: SMAP snow mass and GHRSST sea
 *    ice, daily, a few days behind), the wind (NOAA's GFS forecast, 10 m above the ground, 1°,
 *    fetched again every 3 hours, ~160 KB of GRIB2) and the aurora (NOAA SWPC's OVATION
 *    forecast, every 15 minutes, held in memory).
 * The lists are swapped whole (volatile), so the drawing threads read them without locks.
 */
final class GlobeFeeds {
    private static final String COMMONS = "https://thumb.wikimedia.org/wikipedia/commons/thumb/";
    private static final String DAY_URL = COMMONS
            + "4/4d/Whole_world_-_land_and_oceans.jpg/1920px-Whole_world_-_land_and_oceans.jpg";
    private static final String NIGHT_URL = COMMONS + "b/ba/The_earth_at_night.jpg/1920px-The_earth_at_night.jpg";
    private static final String OSM = "https://planet.openstreetmap.org/replication/changesets/";
    private static final String QUAKES = "https://earthquake.usgs.gov/earthquakes/feed/v1.0/summary/2.5_day.geojson";
    private static final String CELESTRAK = "https://celestrak.org/NORAD/elements/gp.php?FORMAT=tle&GROUP=";
    private static final int ISS_NUMBER = 25544, TIANGONG_NUMBER = 48274, HUBBLE_NUMBER = 20580; // NORAD catalog numbers
    private static final long OSM_EVERY_MS = 60_000, QUAKES_EVERY_MS = 5 * 60_000, ORBITS_EVERY_MS = 12 * 3_600_000L,
            CLOUDS_EVERY_MS = 3 * 3_600_000L, SNOW_EVERY_MS = 12 * 3_600_000L, AURORA_EVERY_MS = 15 * 60_000,
            WIND_EVERY_MS = 3 * 3_600_000L;
    private static final String CLOUDS = "https://clouds.matteason.co.uk/images/2048x1024/clouds.jpg";
    private static final String GIBS = "https://gibs.earthdata.nasa.gov/wms/epsg4326/best/wms.cgi?SERVICE=WMS"
            + "&REQUEST=GetMap&VERSION=1.3.0&CRS=EPSG:4326&BBOX=-90,-180,90,180&WIDTH=1024&HEIGHT=512"
            + "&FORMAT=image/png&TRANSPARENT=true&LAYERS=";
    private static final String AURORA = "https://services.swpc.noaa.gov/json/ovation_aurora_latest.json";
    /** How long an edit's dot stays (fading). */
    static final long EDIT_FADE_MS = 3 * 60_000;
    private static final int MAX_BYTES = 8 << 20;

    /** An OpenStreetMap edit and when its dot appears: a minute's batch is spread over the minute. */
    static final class Edit {
        final Globe.Spot spot;
        final long showAt;

        Edit(Globe.Spot spot, long showAt) {
            this.spot = spot;
            this.showAt = showAt;
        }
    }

    volatile List<Edit> edits = Collections.emptyList();
    /** How many edits the last minute file had (-1: none read yet). */
    volatile int editsPerMinute = -1;
    volatile List<Globe.Spot> quakes = Collections.emptyList();
    /** The space stations and the Hubble Space Telescope (null until known). */
    volatile Orbit iss, tiangong, hubble;
    /** The aurora forecast (Globe.parseAurora's grid), or null; and a count that goes up with each new one. */
    volatile byte[] aurora;
    volatile int auroraVersion;
    /** The wind 10 m up (m/s, eastward and northward; 360 × 181, rows from 90° N, columns from 0° E), or null. */
    volatile float[] windU, windV;

    private final File dir;
    private final Context app;
    private volatile boolean running;
    private Thread thread;
    private long lastOsmSequence = -1;

    GlobeFeeds(Context ctx) {
        dir = new File(ctx.getFilesDir(), "globe");
        app = ctx.getApplicationContext();
    }

    static File dayMap(Context ctx) {
        return new File(new File(ctx.getFilesDir(), "globe"), "day.jpg");
    }

    static File nightMap(Context ctx) {
        return new File(new File(ctx.getFilesDir(), "globe"), "night.jpg");
    }

    /** The weather maps, as the files dir keeps them (GlobeView reloads them when they change). */
    static File clouds(Context ctx) {
        return new File(new File(ctx.getFilesDir(), "globe"), "clouds.jpg");
    }

    static File snow(Context ctx) {
        return new File(new File(ctx.getFilesDir(), "globe"), "snow.png");
    }

    static File ice(Context ctx) {
        return new File(new File(ctx.getFilesDir(), "globe"), "ice.png");
    }

    static boolean mapsReady(Context ctx) {
        return dayMap(ctx).isFile() && nightMap(ctx).isFile();
    }

    /** Downloads whichever map is missing. Blocking: call off the main thread. */
    static synchronized boolean fetchMaps(Context ctx) {
        File day = dayMap(ctx), night = nightMap(ctx);
        return (day.isFile() || Wallpapers.download(DAY_URL, day))
                && (night.isFile() || Wallpapers.download(NIGHT_URL, night));
    }

    /** Throws away the maps (when one doesn't decode), so they're downloaded again next time. */
    static void dropMaps(Context ctx) {
        //noinspection ResultOfMethodCallIgnored
        dayMap(ctx).delete();
        //noinspection ResultOfMethodCallIgnored
        nightMap(ctx).delete();
    }

    void start() {
        if (running) return;
        running = true;
        thread = new Thread(this::loop, "globe-feeds");
        thread.setDaemon(true);
        thread.start();
    }

    void stop() {
        running = false;
        if (thread != null) thread.interrupt();
        thread = null;
    }

    private void loop() {
        long nextOsm = 0, nextQuakes = 0, nextOrbits = 0, nextAurora = 0;
        readOrbits(false); // what's kept, straight away
        loadWind();
        while (running) {
            long now = System.currentTimeMillis();
            if (now >= nextOrbits) {
                nextOrbits = now + 60 * 60_000; // checked hourly; fetched when old
                // Only what's switched on (Settings → Screen saver) is fetched.
                readOrbits(on(LauncherSettings.GLOBE_ISS) || on(LauncherSettings.GLOBE_TIANGONG)
                        || on(LauncherSettings.GLOBE_HUBBLE));
                if (on(LauncherSettings.GLOBE_CLOUDS)) refresh(new File(dir, "clouds.jpg"), CLOUDS, CLOUDS_EVERY_MS);
                if (on(LauncherSettings.GLOBE_WIND)) readWind();
                if (on(LauncherSettings.GLOBE_SNOW)) {
                    refresh(new File(dir, "ice.png"), GIBS + "GHRSST_L4_MUR25_Sea_Ice_Concentration", SNOW_EVERY_MS);
                    refresh(new File(dir, "snow.png"), GIBS + "SMAP_L4_Snow_Mass", SNOW_EVERY_MS);
                }
            }
            if (now >= nextAurora && on(LauncherSettings.GLOBE_AURORA)) {
                nextAurora = now + AURORA_EVERY_MS;
                readAurora();
            }
            if (now >= nextOsm && on(LauncherSettings.GLOBE_EDITS)) {
                nextOsm = now + OSM_EVERY_MS;
                readOsm();
            }
            if (now >= nextQuakes && on(LauncherSettings.GLOBE_QUAKES)) {
                nextQuakes = now + QUAKES_EVERY_MS;
                readQuakes();
            }
            try {
                Thread.sleep(1_000);
            } catch (InterruptedException e) {
                return;
            }
        }
    }

    private void readOsm() {
        try {
            long seq = Globe.parseOsmSequence(text(get(OSM + "state.yaml")));
            if (seq < 0 || seq == lastOsmSequence) return;
            lastOsmSequence = seq; // only ever the newest minute: no catching up after a gap
            List<Globe.Spot> batch = Globe.parseOsm(text(gunzip(get(OSM + Globe.osmPath(seq)))));
            long now = System.currentTimeMillis();
            List<Edit> next = new ArrayList<>();
            for (Edit e : edits) if (now - e.showAt < EDIT_FADE_MS) next.add(e);
            for (int i = 0; i < batch.size(); i++) {
                next.add(new Edit(batch.get(i), now + i * OSM_EVERY_MS / batch.size()));
            }
            while (next.size() > 600) next.remove(0);
            edits = next;
            editsPerMinute = batch.size();
        } catch (Exception e) {
            android.util.Log.w("TvHome", "globe: OpenStreetMap feed failed: " + e);
        }
    }

    private void readQuakes() {
        try {
            quakes = Globe.parseQuakes(text(get(QUAKES)));
        } catch (Exception e) {
            android.util.Log.w("TvHome", "globe: earthquake feed failed: " + e);
        }
    }

    private boolean on(String layer) {
        return LauncherSettings.flag(app, layer);
    }

    /** Downloads a map again once it's {@code maxAge} old (the download keeps the old one if it fails). */
    private static void refresh(File f, String url, long maxAge) {
        if (f.isFile() && System.currentTimeMillis() - f.lastModified() < maxAge) return;
        if (!Wallpapers.download(url, f)) android.util.Log.w("TvHome", "globe: couldn't fetch " + f.getName());
    }

    /** The newest GFS wind (trying older runs if it isn't out yet), once the kept one is 3 hours old. */
    private void readWind() {
        File f = new File(dir, "wind.grb2");
        if (f.isFile() && System.currentTimeMillis() - f.lastModified() < WIND_EVERY_MS) return;
        for (int back = 0; back < 3; back++) {
            try {
                byte[] b = get(Globe.gfsWindUrl(System.currentTimeMillis(), back));
                if (!wind(b)) continue; // not out yet: an error page
                if (dir.isDirectory() || dir.mkdirs()) {
                    File tmp = new File(dir, "wind.grb2.tmp");
                    try (java.io.FileOutputStream out = new java.io.FileOutputStream(tmp)) {
                        out.write(b);
                    }
                    //noinspection ResultOfMethodCallIgnored
                    tmp.renameTo(f);
                }
                return;
            } catch (Exception e) {
                android.util.Log.w("TvHome", "globe: wind failed: " + e);
            }
        }
    }

    private void loadWind() {
        File f = new File(dir, "wind.grb2");
        try {
            if (f.isFile()) wind(java.nio.file.Files.readAllBytes(f.toPath()));
        } catch (IOException ignored) {
        }
    }

    /** Takes the two wind components from a GRIB2 file; false if they aren't both there. */
    private boolean wind(byte[] b) {
        float[] u = null, v = null;
        for (Grib.Field g : Grib.read(b)) {
            if (g.discipline != 0 || g.category != 2 || g.nx != 360 || g.ny != 181) continue;
            if (g.number == 2) u = g.values;
            if (g.number == 3) v = g.values;
        }
        if (u == null || v == null) return false;
        windV = v;
        windU = u;
        return true;
    }

    private void readAurora() {
        try {
            byte[] grid = Globe.parseAurora(text(get(AURORA)));
            if (grid == null) return;
            aurora = grid;
            auroraVersion++;
        } catch (Exception e) {
            android.util.Log.w("TvHome", "globe: aurora forecast failed: " + e);
        }
    }

    /** The kept orbits, after fetching any group 12 hours old (if {@code fetch}). */
    private void readOrbits(boolean fetch) {
        Orbit a = null, b = null, c = null;
        for (Orbit o : group("stations", fetch)) {
            if (o.catalog == ISS_NUMBER) a = o;
            if (o.catalog == TIANGONG_NUMBER) b = o;
        }
        for (Orbit o : group("science", fetch)) if (o.catalog == HUBBLE_NUMBER) c = o;
        iss = a;
        tiangong = b;
        hubble = c;
    }

    private List<Orbit> group(String name, boolean fetch) {
        File f = new File(dir, name + ".tle");
        if (fetch && (!f.isFile() || System.currentTimeMillis() - f.lastModified() > ORBITS_EVERY_MS)) {
            try {
                String text = text(get(CELESTRAK + name));
                if (!Orbit.parse(text).isEmpty()) { // not an error page
                    File tmp = new File(dir, name + ".tle.tmp");
                    if (dir.isDirectory() || dir.mkdirs()) {
                        try (java.io.FileOutputStream out = new java.io.FileOutputStream(tmp)) {
                            out.write(text.getBytes(StandardCharsets.UTF_8));
                        }
                        //noinspection ResultOfMethodCallIgnored
                        tmp.renameTo(f);
                    }
                }
            } catch (Exception e) {
                android.util.Log.w("TvHome", "globe: orbits (" + name + ") failed: " + e);
            }
        }
        try {
            return f.isFile() ? Orbit.parse(new String(java.nio.file.Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8))
                    : Collections.emptyList();
        } catch (IOException e) {
            return Collections.emptyList();
        }
    }

    private static byte[] get(String url) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        try {
            c.setConnectTimeout(15_000);
            c.setReadTimeout(15_000);
            c.setRequestProperty("User-Agent", Wallpapers.UA);
            if (c.getResponseCode() != 200) throw new IOException("HTTP " + c.getResponseCode() + " for " + url);
            try (InputStream in = c.getInputStream()) {
                return readAll(in);
            }
        } finally {
            c.disconnect();
        }
    }

    private static byte[] gunzip(byte[] gz) throws IOException {
        try (InputStream in = new GZIPInputStream(new ByteArrayInputStream(gz))) {
            return readAll(in);
        }
    }

    private static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[16 * 1024];
        for (int n; (n = in.read(buf)) > 0; ) {
            out.write(buf, 0, n);
            if (out.size() > MAX_BYTES) throw new IOException("too big");
        }
        return out.toByteArray();
    }

    private static String text(byte[] b) {
        return new String(b, StandardCharsets.UTF_8);
    }
}
