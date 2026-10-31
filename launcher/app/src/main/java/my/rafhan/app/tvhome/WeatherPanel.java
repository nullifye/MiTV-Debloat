package my.rafhan.app.tvhome;

import android.content.Context;
import android.content.res.ColorStateList;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The Weather card, under the prayer card: today's MET Malaysia forecast for the place set in
 * Settings → Weather (and any MET warning in force there, in amber), then two air-quality
 * readings for the same spot: the DOE's Air Pollutant Index (APIMS, the station chosen or the one
 * nearest the prayer zone) and Copernicus's US AQI (Open-Meteo) at that station. Weather
 * (Weather.java) does the parsing.
 *
 * Everything is kept in files/weather/ and shown from there straight away (offline too), and
 * fetched again as its source changes: the APIMS reading (with the station's last 24 hours, for
 * the trend) once each hour's is out, from about 10 past; Copernicus's values each new hour;
 * MET's warnings every 15 minutes; its forecast hourly; the list of MET places weekly. It's
 * checked on the clock's minute tick, so there's no polling; the fetches run on their own thread
 * so a slow server never holds up the app list.
 */
final class WeatherPanel {
    private static final String MET = "https://api.data.gov.my/weather/forecast/";
    private static final String APIMS = "https://eqms.doe.gov.my/api3/publicmapproxy/PUBLIC_DISPLAY/"
            + "CAQM_MCAQM_Current_Reading/MapServer/0/query?where=1%3D1&returnGeometry=false&f=json"
            + "&outFields=STATION_ID,DATETIME,API,CLASS,PARAM_SYMBOL,LATITUDE,LONGITUDE,STATION_LOCATION,STATE_NAME";
    /** One station's readings over the last 24 hours (about 6 KB), for the trend. */
    private static final String APIMS_HOURLY = "https://eqms.doe.gov.my/api3/publicportalapims/apitablehourly?stationid=";
    private static final String WARNINGS = "https://api.data.gov.my/weather/warning/?limit=30";
    private static final String CAMS = "https://air-quality-api.open-meteo.com/v1/air-quality"
            + "?current=us_aqi,pm2_5,aerosol_optical_depth&timezone=Asia%2FKuala_Lumpur";
    // How often each part is fetched, by how often its source changes: MET's forecast is put out
    // daily (now and then revised in the day), its warnings whenever needed; the APIMS reading and
    // Copernicus's values are hourly (hourlyDue); the place list hardly ever changes.
    private static final long FORECAST_EVERY_MS = 3_600_000L, WARNINGS_EVERY_MS = 15 * 60_000L,
            PLACES_EVERY_MS = 7 * 24 * 3_600_000L, HOURLY_RETRY_MS = 10 * 60_000L,
            RETRY_MS = 5 * 60_000L, OLD_MS = 3 * 3_600_000L;
    /** Minutes past the hour each hourly source has its new reading out (the APIMS portal looks at :10). */
    private static final int APIMS_MINUTE = 10, CAMS_MINUTE = 1;
    private static final int MAX_BYTES = 2 << 20;
    private static final int TEXT = 0xFFFFFFFF, DIM = 0x8CFFFFFF, ICON = 0xA6FFFFFF, WARN = 0xFFFFB340,
            FALLING = 0xFF63D489; // a falling API: the System card's green
    /** The API's trend: the newest reading against the one this many hours before. */
    private static final int TREND_HOURS = 3;
    private static final String NONE = "–", MET_NAME = "MET";
    /** One thread for all weather fetches (the card's and Settings'), apart from the app's worker. */
    private static final ExecutorService NET = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "weather");
        t.setDaemon(true);
        return t;
    });

    private final Context ctx;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final float dp;
    private final ImageView titleIcon, skyIcon;
    private final TextView title, outlook, temp, skyTime;
    private final ImageView[] partIcons = new ImageView[3]; // morning, evening, night
    private final Gauge api, aqi;
    private boolean fetching;
    private long lastAttempt;

    WeatherPanel(Context ctx, LinearLayout root) {
        this.ctx = ctx;
        dp = ctx.getResources().getDisplayMetrics().density;
        // Title: WEATHER · KLANG, and today's outlook in words on the right.
        LinearLayout header = new LinearLayout(ctx);
        header.setGravity(Gravity.CENTER_VERTICAL);
        titleIcon = icon(R.drawable.ic_sun_ring, DIM); // a ring sun: not the prayer card's solid ones
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(px(12), px(12));
        tlp.setMarginEnd(px(5));
        header.addView(titleIcon, tlp);
        title = small("WEATHER", DIM, 10.5f, 0.08f);
        header.addView(title);
        outlook = small("", DIM, 10.5f, 0.02f);
        outlook.setGravity(Gravity.END);
        LinearLayout.LayoutParams olp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        olp.setMarginStart(px(10));
        header.addView(outlook, olp);
        header.setPadding(0, 0, px(8), 0); // the card's right edge, as the columns below have
        root.addView(header);

        LinearLayout row = new LinearLayout(ctx);
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        rlp.topMargin = px(9);
        root.addView(row, rlp);

        // Weather: the day's range, then morning / evening / night, then when MET's forecast came in.
        LinearLayout sky = column(row, 1.55f);
        LinearLayout top = new LinearLayout(ctx);
        top.setGravity(Gravity.CENTER_VERTICAL);
        skyIcon = icon(R.drawable.ic_cloud, ICON);
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(px(16), px(16));
        slp.setMarginEnd(px(6));
        top.addView(skyIcon, slp);
        temp = value();
        top.addView(temp);
        sky.addView(top);
        LinearLayout parts = new LinearLayout(ctx);
        parts.setGravity(Gravity.CENTER_VERTICAL);
        String[] names = {"MORNING", "EVENING", "NIGHT"};
        for (int i = 0; i < 3; i++) {
            partIcons[i] = icon(R.drawable.ic_cloud, ICON);
            LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(px(12), px(12));
            ilp.setMarginEnd(px(3));
            if (i > 0) ilp.setMarginStart(px(7));
            parts.addView(partIcons[i], ilp);
            parts.addView(small(names[i], DIM, 7.5f, 0.03f));
        }
        sky.addView(parts, top(5));
        skyTime = small("MET", DIM, 8.5f, 0.06f);
        sky.addView(skyTime, top(5));

        api = new Gauge(column(row, 1f));
        aqi = new Gauge(column(row, 1f));
        render();
    }

    /** Called every minute (clock tick) and on resume: shows what's kept, fetches what's old. */
    void refresh() {
        if (!LauncherSettings.flag(ctx, LauncherSettings.SHOW_WEATHER)) return; // off: nothing fetched
        render();
        if (fetching || System.currentTimeMillis() - lastAttempt < RETRY_MS) return;
        if (stale(ctx)) fetch();
    }

    /** Zone, place or station changed: show and fetch for the new one now. */
    void settingsChanged() {
        lastAttempt = 0;
        refresh();
    }

    /** Network came back: retry soon after a failure. */
    void onNetworkAvailable() {
        if (!fetching && System.currentTimeMillis() - lastAttempt > 60_000) {
            lastAttempt = 0;
            refresh();
        }
    }

    private void fetch() {
        fetching = true;
        lastAttempt = System.currentTimeMillis();
        Context app = ctx.getApplicationContext();
        NET.execute(() -> {
            update(app);
            main.post(() -> {
                fetching = false;
                render();
            });
        });
    }

    // ---------------------------------------------------------------- display

    private void render() {
        List<Weather.Place> places = places(ctx);
        Weather.Place place = place(ctx, places);
        File ff = place == null ? null : file(ctx, forecastName(place.id));
        Weather.Day day = ff == null ? null : Weather.parseForecast(read(ff), place.id, LocalDate.now());
        title.setText(place != null ? "WEATHER · " + place.name.toUpperCase(Locale.ROOT) : "WEATHER");
        Weather.Warning warning = place == null ? null : Weather.activeWarning(
                Weather.parseWarnings(read(file(ctx, "warnings.json"))), LocalDateTime.now(), warningPlaces(place));
        if (warning != null) {
            String w = "⚠ " + Weather.warningName(warning) + " until " + clock(warning.to);
            outlook.setText(w);
            outlook.setTextColor(WARN);
        } else {
            outlook.setText(day != null ? Weather.outlook(day) : "");
            outlook.setTextColor(DIM);
        }
        if (day != null) {
            int kind = Weather.kind(day.summary);
            skyIcon.setImageResource(kindIcon(kind));
            String range = day.min + "–" + day.max + "°C";
            temp.setText(range);
            temp.setTextColor(TEXT);
            String[] p = {day.morning, day.afternoon, day.night};
            for (int i = 0; i < 3; i++) partIcons[i].setImageResource(kindIcon(Weather.kind(p[i])));
            String at = "MET · " + clock(ff.lastModified());
            skyTime.setText(at);
        } else {
            skyIcon.setImageResource(R.drawable.ic_cloud);
            temp.setText(NONE);
            temp.setTextColor(DIM);
            for (ImageView v : partIcons) v.setImageResource(R.drawable.ic_cloud);
            skyTime.setText(MET_NAME);
        }

        Weather.Station st = station(ctx, stations(ctx));
        if (st != null && st.api >= 0) {
            boolean old = st.time == null || st.time.isBefore(LocalDateTime.now().minus(Duration.ofMillis(OLD_MS)));
            int b = Weather.band(st.api, Weather.API_TOP);
            // Rising or falling: this station's last few hours (APIMS's hourly table).
            Integer change = Weather.change(Weather.parseHourly(read(file(ctx, trendName(st.id))), st.id), st.time,
                    TREND_HOURS);
            int trendColor = change == null || Math.abs(change) < 2 ? DIM : change > 0 ? WARN : FALLING;
            api.set("API " + st.api, Weather.API_BANDS[b], Weather.API_COLORS[b], Weather.trend(change, TREND_HOURS),
                    trendColor, "APIMS" + (st.time != null ? " · " + clock(st.time) : ""), old);
        } else {
            api.none("API –", "APIMS");
        }

        File af = st == null ? null : file(ctx, airName(st.id));
        Weather.Air air = af == null ? null : Weather.parseAir(read(af));
        if (air != null) {
            boolean old = System.currentTimeMillis() - af.lastModified() > OLD_MS;
            int b = Weather.band(air.aqi, Weather.US_TOP);
            String detail = (Double.isNaN(air.pm25) ? "" : "PM2.5 " + Math.round(air.pm25))
                    + (Double.isNaN(air.pm25) || Double.isNaN(air.haze) ? "" : " · ")
                    + (Double.isNaN(air.haze) ? "" : String.format(Locale.ROOT, "Haze %.2f", air.haze));
            aqi.set("AQI " + air.aqi, Weather.US_SHORT[b], Weather.US_COLORS[b], detail, DIM,
                    "COPERNICUS" + (air.time != null ? " · " + clock(air.time) : ""), old);
        } else {
            aqi.none("AQI –", "COPERNICUS");
        }
    }

    /**
     * The names a MET warning would use for the place: its own, and for Auto the prayer zone's
     * areas too (the warnings list districts: "Klang", "Kuala Langat").
     */
    private List<String> warningPlaces(Weather.Place place) {
        List<String> out = new java.util.ArrayList<>();
        out.add(place.name);
        if (LauncherSettings.weatherPlace(ctx).isEmpty()) {
            String[] z = RemoteSettings.zoneEntry(ctx, LauncherSettings.zone(ctx)); // state, code, areas
            if (z != null) for (String a : z[2].split(",")) out.add(a.trim());
        }
        return out;
    }

    /** "15:40" or "3:40 PM", as the home clock is set. */
    private String clock(LocalDateTime t) {
        return t.format(java.time.format.DateTimeFormatter.ofPattern(
                LauncherSettings.use24h(ctx) ? "HH:mm" : "h:mm a", Locale.ENGLISH));
    }

    private String clock(long epochMs) {
        return clock(LocalDateTime.ofInstant(java.time.Instant.ofEpochMilli(epochMs), java.time.ZoneId.systemDefault()));
    }

    static int kindIcon(int kind) {
        switch (kind) {
            case Weather.SUN: return R.drawable.ic_sun_ring; // the title's ring sun: not the prayer icons' solid ones
            case Weather.RAIN: return R.drawable.ic_rain;
            case Weather.STORM: return R.drawable.ic_storm;
            default: return R.drawable.ic_cloud; // cloudy, hazy
        }
    }

    // ---------------------------------------------------------------- data (also for Settings)

    // The two lists, parsed once per file version (they're read every minute).
    private static long placesMade = -1, stationsMade = -1;
    private static List<Weather.Place> placesKept;
    private static List<Weather.Station> stationsKept;

    /** The MET places (today's forecast for every location, kept a week), or none yet. */
    static synchronized List<Weather.Place> places(Context ctx) {
        File f = file(ctx, "places.json");
        if (f.lastModified() != placesMade || placesKept == null) {
            placesKept = Weather.parsePlaces(read(f));
            placesMade = f.lastModified();
        }
        return placesKept;
    }

    /** The APIMS stations with their latest readings, or none yet. */
    static synchronized List<Weather.Station> stations(Context ctx) {
        File f = file(ctx, "stations.json");
        if (f.lastModified() != stationsMade || stationsKept == null) {
            stationsKept = Weather.parseStations(read(f));
            stationsMade = f.lastModified();
        }
        return stationsKept;
    }

    /** The place set in Settings, or for Auto the one matching the prayer zone. */
    static Weather.Place place(Context ctx, List<Weather.Place> places) {
        String id = LauncherSettings.weatherPlace(ctx);
        if (!id.isEmpty()) {
            Weather.Place p = Weather.place(places, id);
            if (p != null) return p;
        }
        return autoPlace(ctx, places);
    }

    /** Auto: the MET place matching the prayer zone's town (else its state). */
    static Weather.Place autoPlace(Context ctx, List<Weather.Place> places) {
        String[] z = RemoteSettings.zoneEntry(ctx, LauncherSettings.zone(ctx)); // state, code, areas
        return z == null ? null : Weather.autoPlace(z[0], z[2], places);
    }

    /** The station set in Settings, or for Nearest the one closest to the prayer zone's town. */
    static Weather.Station station(Context ctx, List<Weather.Station> stations) {
        String id = LauncherSettings.weatherStation(ctx);
        if (!id.isEmpty()) {
            Weather.Station s = Weather.station(stations, id);
            if (s != null) return s;
        }
        return nearestStation(ctx, stations);
    }

    /** Nearest: the APIMS station closest to the prayer zone's main town. */
    static Weather.Station nearestStation(Context ctx, List<Weather.Station> stations) {
        double[] home = Globe.zonePlace(LauncherSettings.zone(ctx));
        return Weather.nearest(stations, home[0], home[1]);
    }

    /** Fetches the places and stations if they aren't kept yet (Settings' lists), then runs {@code done}. */
    static void loadLists(Context ctx, Runnable done) {
        Context app = ctx.getApplicationContext();
        Handler main = new Handler(Looper.getMainLooper());
        NET.execute(() -> {
            if (places(app).isEmpty()) fetchPlaces(app);
            if (stations(app).isEmpty()) fetchTo(app, APIMS, "stations.json");
            main.post(done);
        });
    }

    private static boolean stale(Context ctx) {
        long now = System.currentTimeMillis();
        List<Weather.Place> places = places(ctx);
        if (places.isEmpty() || now - file(ctx, "places.json").lastModified() > PLACES_EVERY_MS) return true;
        Weather.Place p = place(ctx, places);
        if (p != null) {
            File f = file(ctx, forecastName(p.id));
            if (now - f.lastModified() > FORECAST_EVERY_MS
                    || Weather.parseForecast(read(f), p.id, LocalDate.now()) == null) return true;
        }
        if (stationsDue(ctx, now)) return true;
        if (now - file(ctx, "warnings.json").lastModified() > WARNINGS_EVERY_MS) return true;
        Weather.Station s = station(ctx, stations(ctx));
        return s != null && (airDue(ctx, s, now) || trendDue(ctx, s));
    }

    /** The APIMS readings: the shown station's newer hour should be out (from :10 past the hour). */
    private static boolean stationsDue(Context ctx, long now) {
        Weather.Station s = station(ctx, stations(ctx));
        return Weather.hourlyDue(s == null ? null : s.time, LocalDateTime.now(), APIMS_MINUTE,
                now - file(ctx, "stations.json").lastModified(), HOURLY_RETRY_MS);
    }

    /** The station's last 24 hours: missing, or older than the readings it should go with. */
    private static boolean trendDue(Context ctx, Weather.Station s) {
        File t = file(ctx, trendName(s.id));
        return !t.isFile() || t.lastModified() < file(ctx, "stations.json").lastModified();
    }

    /** Copernicus's values for the station's spot: a new hour has begun (or there's no haze yet). */
    private static boolean airDue(Context ctx, Weather.Station s, long now) {
        File f = file(ctx, airName(s.id));
        Weather.Air a = Weather.parseAir(read(f));
        LocalDateTime have = a == null || Double.isNaN(a.haze) ? null : a.time;
        return Weather.hourlyDue(have, LocalDateTime.now(), CAMS_MINUTE, now - f.lastModified(), HOURLY_RETRY_MS);
    }

    /** Fetches whatever is old, in order (the place and station decide the rest). Blocking. */
    private static void update(Context ctx) {
        long now = System.currentTimeMillis();
        if (places(ctx).isEmpty() || now - file(ctx, "places.json").lastModified() > PLACES_EVERY_MS) {
            fetchPlaces(ctx);
        }
        Weather.Place p = place(ctx, places(ctx));
        if (p != null) {
            File f = file(ctx, forecastName(p.id));
            if (now - f.lastModified() > FORECAST_EVERY_MS
                    || Weather.parseForecast(read(f), p.id, LocalDate.now()) == null) {
                deleteOthers(ctx, "forecast-", f.getName());
                fetchTo(ctx, MET + "?contains=" + p.id + "@location__location_id", f.getName());
            }
        }
        if (stationsDue(ctx, now)) fetchTo(ctx, APIMS, "stations.json");
        if (now - file(ctx, "warnings.json").lastModified() > WARNINGS_EVERY_MS) fetchTo(ctx, WARNINGS, "warnings.json");
        Weather.Station s = station(ctx, stations(ctx));
        if (s != null) {
            File t = file(ctx, trendName(s.id));
            if (trendDue(ctx, s)) {
                deleteOthers(ctx, "trend-", t.getName());
                fetchTo(ctx, APIMS_HOURLY + s.id + "&", t.getName());
            }
            File f = file(ctx, airName(s.id));
            if (airDue(ctx, s, now)) {
                deleteOthers(ctx, "air-", f.getName());
                fetchTo(ctx, CAMS + String.format(Locale.ROOT, "&latitude=%.4f&longitude=%.4f", s.lat, s.lon),
                        f.getName());
            }
        }
    }

    /** Today's forecast for every MET location: the place list (about 130 KB). */
    private static void fetchPlaces(Context ctx) {
        String d = LocalDate.now().toString();
        fetchTo(ctx, MET + "?date_start=" + d + "@date&date_end=" + d + "@date&limit=1000", "places.json");
    }

    private static String forecastName(String placeId) {
        return "forecast-" + placeId + ".json";
    }

    private static String trendName(String stationId) {
        return "trend-" + stationId + ".json";
    }

    private static String airName(String stationId) {
        return "air-" + stationId + ".json";
    }

    private static File file(Context ctx, String name) {
        return new File(new File(ctx.getFilesDir(), "weather"), name);
    }

    private static String read(File f) {
        try {
            return f.isFile() ? new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8) : "";
        } catch (IOException e) {
            return "";
        }
    }

    private static void deleteOthers(Context ctx, String prefix, String keep) {
        File[] old = new File(ctx.getFilesDir(), "weather").listFiles((d, n) -> n.startsWith(prefix) && !n.equals(keep));
        if (old != null) for (File f : old) //noinspection ResultOfMethodCallIgnored
            f.delete();
    }

    /** Downloads to files/weather/{@code name}; a failure keeps the old file. */
    private static void fetchTo(Context ctx, String url, String name) {
        File dir = new File(ctx.getFilesDir(), "weather");
        if (!dir.isDirectory() && !dir.mkdirs()) return;
        File tmp = new File(dir, name + ".tmp");
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(url).openConnection();
            c.setConnectTimeout(15_000);
            c.setReadTimeout(15_000);
            c.setRequestProperty("User-Agent", Wallpapers.UA);
            c.setRequestProperty("Accept", "application/json");
            if (c.getResponseCode() != 200) throw new IOException("HTTP " + c.getResponseCode());
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            try (InputStream in = c.getInputStream()) {
                byte[] b = new byte[16 * 1024];
                for (int n; (n = in.read(b)) > 0; ) {
                    buf.write(b, 0, n);
                    if (buf.size() > MAX_BYTES) throw new IOException("too big");
                }
            }
            byte[] body = buf.toByteArray();
            if (body.length == 0 || (body[0] != '[' && body[0] != '{')) throw new IOException("not JSON");
            try (FileOutputStream out = new FileOutputStream(tmp)) {
                out.write(body);
            }
            //noinspection ResultOfMethodCallIgnored
            tmp.renameTo(new File(dir, name));
        } catch (Exception e) {
            android.util.Log.w("TvHome", "weather: " + name + " failed: " + e);
        } finally {
            if (c != null) c.disconnect();
            //noinspection ResultOfMethodCallIgnored
            tmp.delete();
        }
    }

    // ---------------------------------------------------------------- views

    private int px(float dps) {
        return Math.round(dps * dp);
    }

    private LinearLayout.LayoutParams top(int margin) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = px(margin);
        return lp;
    }

    private LinearLayout column(LinearLayout row, float weight) {
        LinearLayout col = new LinearLayout(ctx);
        col.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, weight);
        lp.setMarginEnd(px(8));
        row.addView(col, lp);
        return col;
    }

    private ImageView icon(int res, int tint) {
        ImageView v = new ImageView(ctx);
        v.setImageResource(res);
        v.setImageTintList(ColorStateList.valueOf(tint));
        return v;
    }

    private TextView value() {
        TextView t = new TextView(ctx);
        t.setTextSize(14f);
        t.setTypeface(Fonts.outfit(ctx, 500));
        t.setTextColor(TEXT);
        t.setSingleLine(true);
        t.setIncludeFontPadding(false);
        return t;
    }

    private TextView small(String text, int color, float size, float spacing) {
        TextView t = new TextView(ctx);
        t.setText(text);
        t.setTextSize(size);
        t.setTextColor(color);
        t.setLetterSpacing(spacing);
        t.setTypeface(Fonts.outfit(ctx, 500));
        t.setSingleLine(true);
        t.setEllipsize(TextUtils.TruncateAt.END);
        return t;
    }

    /**
     * An air reading: "API 74" with its band beside it in the band's colour, a detail line (the
     * trend, or PM2.5 and haze), then source · time.
     */
    private final class Gauge {
        final TextView value, band, detail, label;

        Gauge(LinearLayout col) {
            LinearLayout top = new LinearLayout(ctx); // baseline-aligned: the band sits on the number's line
            value = value();
            top.addView(value);
            // Beside the number, as the other small labels are; a long one ("VERY UNHEALTHY") ends in "…".
            band = small("", DIM, 8.5f, 0.06f);
            LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            blp.setMarginStart(px(5));
            top.addView(band, blp);
            col.addView(top, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT));
            detail = small("", DIM, 8.5f, 0.02f);
            col.addView(detail, top(5));
            label = small("", DIM, 8.5f, 0.06f);
            col.addView(label, top(5));
        }

        void set(String v, String bandName, int bandColor, String d, int detailColor, String l, boolean old) {
            value.setText(v);
            value.setTextColor(old ? DIM : TEXT);
            band.setText(bandName.toUpperCase(Locale.ROOT));
            band.setTextColor(old ? DIM : bandColor);
            detail.setText(d);
            detail.setTextColor(old ? DIM : detailColor);
            label.setText(l);
        }

        void none(String v, String l) {
            value.setText(v);
            value.setTextColor(DIM);
            band.setText("");
            detail.setText("");
            label.setText(l);
        }
    }
}
