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

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.YearMonth;
import java.util.Locale;
import java.util.concurrent.Executor;

/**
 * JAKIM e-solat prayer times card, plus the Hijri date line above the clock.
 *
 * The whole month for the zone is fetched once, cached in the app's files dir, and fetched
 * again only when the month changes (or after a failed fetch, at most once a minute when the
 * network comes back). Display updates ride on the clock's minute tick, so there's no polling.
 */
final class PrayerPanel {
    private static final String API =
            "https://www.e-solat.gov.my/index.php?r=esolatApi/takwimsolat&period=month&zone=";
    private static final String[] KEYS = {"fajr", "dhuhr", "asr", "maghrib", "isha"};
    static final String[] NAMES = {"Fajr", "Dhuhr", "Asr", "Maghrib", "Isya"};
    /** Dawn, midday sun, afternoon sun, sunset, night: one per prayer, in NAMES order. */
    static final int[] ICONS = {R.drawable.ic_fajr, R.drawable.ic_dhuhr, R.drawable.ic_asr,
            R.drawable.ic_maghrib, R.drawable.ic_isya};
    private static final String[] HIJRI_MONTHS = {"Muharam", "Safar", "Rabiulawal", "Rabiulakhir",
            "Jamadilawal", "Jamadilakhir", "Rejab", "Syaaban", "Ramadan", "Syawal", "Zulkaedah", "Zulhijjah"};
    // e-solat dates use Malay month abbreviations: "20-Okt-2026".
    private static final String[] MALAY_MONTHS = {"Jan", "Feb", "Mac", "Apr", "Mei", "Jun",
            "Jul", "Ogo", "Sep", "Okt", "Nov", "Dis"};
    private static final int TEXT = 0xFFFFFFFF, DIM = 0x8CFFFFFF, NEXT = 0xFFFFC857;

    private final Context ctx;
    private final Executor worker;
    private final Handler main = new Handler(Looper.getMainLooper());
    private String zone; // Settings → Prayer times → Zone
    private final float dp;
    private final TextView hijriView, countdown, title;
    private final TextView[] times = new TextView[KEYS.length];
    private final TextView[] names = new TextView[KEYS.length];
    private final ImageView[] icons = new ImageView[KEYS.length];
    private final ImageView headerIcon; // the next prayer's icon, beside "PRAYER · zone"

    private JSONArray month;
    private YearMonth monthKey;
    private boolean fetching;
    private long lastAttempt;

    PrayerPanel(Context ctx, LinearLayout root, TextView hijriView, Executor worker) {
        this.ctx = ctx;
        this.worker = worker;
        this.hijriView = hijriView;
        dp = ctx.getResources().getDisplayMetrics().density;
        zone = LauncherSettings.zone(ctx);

        LinearLayout header = new LinearLayout(ctx);
        header.setGravity(Gravity.CENTER_VERTICAL);
        headerIcon = new ImageView(ctx);
        headerIcon.setImageResource(R.drawable.ic_crescent); // until the times are known
        headerIcon.setImageTintList(ColorStateList.valueOf(DIM));
        LinearLayout.LayoutParams mlp = new LinearLayout.LayoutParams(px(12), px(12));
        mlp.setMarginEnd(px(5));
        header.addView(headerIcon, mlp);
        title = small("PRAYER · " + zone, DIM, 10.5f);
        header.addView(title);
        countdown = small("", NEXT, 10.5f);
        countdown.setGravity(Gravity.END);
        header.addView(countdown, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        root.addView(header);

        LinearLayout row = new LinearLayout(ctx);
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rlp.topMargin = px(8);
        root.addView(row, rlp);
        for (int i = 0; i < KEYS.length; i++) {
            LinearLayout cell = new LinearLayout(ctx);
            cell.setOrientation(LinearLayout.VERTICAL);
            row.addView(cell, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            times[i] = new TextView(ctx);
            times[i].setTextSize(17f);
            times[i].setTypeface(Fonts.outfit(ctx, 500));
            times[i].setTextColor(TEXT);
            times[i].setIncludeFontPadding(false);
            times[i].setText("--:--");
            cell.addView(times[i], new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            // [icon] NAME under the time
            LinearLayout label = new LinearLayout(ctx);
            label.setGravity(Gravity.CENTER_VERTICAL);
            icons[i] = new ImageView(ctx);
            icons[i].setImageResource(ICONS[i]);
            icons[i].setImageTintList(ColorStateList.valueOf(DIM));
            LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(px(10), px(10));
            ilp.setMarginEnd(px(4));
            label.addView(icons[i], ilp);
            names[i] = small(NAMES[i].toUpperCase(Locale.ROOT), DIM, 9f);
            label.addView(names[i]);
            LinearLayout.LayoutParams nlp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            nlp.topMargin = px(3);
            cell.addView(label, nlp);
        }
    }

    /** Called every minute (clock tick) and on resume. */
    void refresh() {
        LocalDate today = LocalDate.now();
        YearMonth ym = YearMonth.from(today);
        if (month == null || !ym.equals(monthKey)) {
            month = readCache(ym);
            monthKey = month == null ? null : ym;
            if (month == null) fetch(ym);
        }
        render(today, LocalTime.now());
    }

    /** Zone changed in Settings: show that zone's times (from cache, else fetched). */
    void setZone(String z) {
        if (z.equals(zone)) return;
        zone = z;
        title.setText("PRAYER · " + zone);
        month = null;
        monthKey = null;
        fetching = false; // a fetch for the old zone is dropped when it lands
        refresh();
    }

    /** Network came back: retry a failed fetch (at most once a minute). */
    void onNetworkAvailable() {
        if (month == null && !fetching && System.currentTimeMillis() - lastAttempt > 60_000) {
            fetch(YearMonth.now());
        }
    }

    // ---------------------------------------------------------------- display

    private void render(LocalDate today, LocalTime now) {
        JSONObject day = entryFor(today);
        if (day == null) {
            hijriView.setText("");
            countdown.setText(fetching ? "Loading…" : "");
            return;
        }
        hijriView.setText(hijri(day.optString("hijri")));

        LocalTime[] t = new LocalTime[KEYS.length];
        boolean h24 = LauncherSettings.use24h(ctx);
        for (int i = 0; i < KEYS.length; i++) {
            t[i] = parse(day.optString(KEYS[i]));
            times[i].setText(t[i] == null ? "--:--" : t[i].format(java.time.format.DateTimeFormatter.ofPattern(h24 ? "HH:mm" : "h:mm")));
        }

        // Next prayer today, or tomorrow's Fajr after Isya.
        int next = -1;
        for (int i = 0; i < KEYS.length; i++) {
            if (t[i] != null && t[i].isAfter(now)) {
                next = i;
                break;
            }
        }
        LocalDateTime target;
        if (next >= 0) {
            target = today.atTime(t[next]);
        } else {
            next = 0;
            JSONObject tomorrow = entryFor(today.plusDays(1));
            LocalTime fajr = tomorrow != null ? parse(tomorrow.optString(KEYS[0])) : t[0];
            target = fajr == null ? null : today.plusDays(1).atTime(fajr);
        }
        for (int i = 0; i < KEYS.length; i++) {
            int c = i == next ? NEXT : TEXT;
            times[i].setTextColor(c);
            names[i].setTextColor(i == next ? NEXT : DIM);
            icons[i].setImageTintList(ColorStateList.valueOf(i == next ? NEXT : DIM));
        }
        headerIcon.setImageResource(ICONS[next]);
        if (target != null) {
            long mins = (long) Math.ceil(Duration.between(LocalDateTime.of(today, now), target).getSeconds() / 60.0);
            long h = mins / 60, m = mins % 60;
            countdown.setText(NAMES[next] + " in " + (h > 0 ? h + "h " + m + "m" : m + "m"));
        } else {
            countdown.setText("");
        }
    }

    private JSONObject entryFor(LocalDate date) {
        if (month == null || !YearMonth.from(date).equals(monthKey)) return null;
        for (int i = 0; i < month.length(); i++) {
            JSONObject o = month.optJSONObject(i);
            String d = o == null ? "" : o.optString("date"); // e.g. "20-Okt-2026"
            if (d.length() >= 2 && TextUtils.isDigitsOnly(d.substring(0, 2))
                    && Integer.parseInt(d.substring(0, 2)) == date.getDayOfMonth()) {
                return o;
            }
        }
        return null;
    }

    private static LocalTime parse(String hms) {
        try {
            return LocalTime.parse(hms);
        } catch (Exception e) {
            return null;
        }
    }

    /** "1448-05-08" -> "8 Jamadilawal 1448H". */
    private static String hijri(String raw) {
        String[] p = raw.split("-");
        if (p.length != 3) return raw;
        try {
            int m = Integer.parseInt(p[1]);
            String name = m >= 1 && m <= 12 ? HIJRI_MONTHS[m - 1] : p[1];
            return Integer.parseInt(p[2]) + " " + name + " " + p[0] + "H";
        } catch (NumberFormatException e) {
            return raw;
        }
    }

    // ---------------------------------------------------------------- data

    private File cacheFile(String z, YearMonth ym) {
        return new File(ctx.getFilesDir(), "prayer-" + z + "-" + ym + ".json");
    }

    /**
     * Today's five times (Fajr..Isya) for {@code zone} from the month cache this card keeps, for
     * the azan in RemoteService; null if that month isn't cached yet. Blocking (file read).
     */
    static LocalTime[] cachedTimes(Context ctx, String zone, LocalDate date) {
        File f = new File(ctx.getFilesDir(), "prayer-" + zone + "-" + YearMonth.from(date) + ".json");
        if (!f.isFile()) return null;
        try (BufferedReader r = new BufferedReader(new FileReader(f))) {
            StringBuilder sb = new StringBuilder();
            for (String line; (line = r.readLine()) != null; ) sb.append(line);
            JSONArray month = new JSONArray(sb.toString());
            for (int i = 0; i < month.length(); i++) {
                JSONObject o = month.optJSONObject(i);
                String d = o == null ? "" : o.optString("date"); // e.g. "20-Okt-2026"
                if (d.length() >= 2 && TextUtils.isDigitsOnly(d.substring(0, 2))
                        && Integer.parseInt(d.substring(0, 2)) == date.getDayOfMonth()) {
                    LocalTime[] t = new LocalTime[KEYS.length];
                    for (int k = 0; k < KEYS.length; k++) t[k] = parse(o.optString(KEYS[k]));
                    return t;
                }
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    private JSONArray readCache(YearMonth ym) {
        File f = cacheFile(zone, ym);
        if (!f.isFile()) return null;
        try (BufferedReader r = new BufferedReader(new FileReader(f))) {
            StringBuilder sb = new StringBuilder();
            for (String line; (line = r.readLine()) != null; ) sb.append(line);
            return new JSONArray(sb.toString());
        } catch (Exception e) {
            return null;
        }
    }

    private void fetch(YearMonth ym) {
        if (fetching) return;
        fetching = true;
        lastAttempt = System.currentTimeMillis();
        String z = zone;
        worker.execute(() -> {
            JSONArray result = null;
            HttpURLConnection c = null;
            try {
                c = (HttpURLConnection) new URL(API + z).openConnection();
                c.setConnectTimeout(15_000);
                c.setReadTimeout(15_000);
                c.setRequestProperty("Accept", "application/json");
                int code = c.getResponseCode();
                if (code != 200) android.util.Log.w("TvHome", "prayer fetch HTTP " + code);
                if (code == 200) {
                    StringBuilder sb = new StringBuilder();
                    try (BufferedReader r = new BufferedReader(
                            new InputStreamReader(c.getInputStream(), StandardCharsets.UTF_8))) {
                        for (String line; (line = r.readLine()) != null; ) sb.append(line);
                    }
                    JSONArray arr = new JSONObject(sb.toString()).getJSONArray("prayerTime");
                    // The API serves the server's current month; only keep it if that's ours.
                    String first = arr.getJSONObject(0).optString("date");
                    String expect = "-" + MALAY_MONTHS[ym.getMonthValue() - 1] + "-" + ym.getYear();
                    if (first.toLowerCase(Locale.ROOT).endsWith(expect.toLowerCase(Locale.ROOT))) {
                        result = arr;
                        writeCache(z, ym, arr);
                    }
                }
            } catch (Exception e) {
                android.util.Log.w("TvHome", "prayer fetch failed", e);
            } finally {
                if (c != null) c.disconnect();
            }
            JSONArray done = result;
            main.post(() -> {
                if (!z.equals(zone)) return; // the zone changed meanwhile
                fetching = false;
                if (done != null) {
                    month = done;
                    monthKey = ym;
                }
                render(LocalDate.now(), LocalTime.now());
            });
        });
    }

    private void writeCache(String z, YearMonth ym, JSONArray arr) throws IOException {
        File[] old = ctx.getFilesDir().listFiles((d, n) -> n.startsWith("prayer-"));
        if (old != null) for (File f : old) f.delete();
        try (FileOutputStream out = new FileOutputStream(cacheFile(z, ym))) {
            out.write(arr.toString().getBytes(StandardCharsets.UTF_8));
        }
    }

    // ---------------------------------------------------------------- helpers

    /** The icon for a prayer name in NAMES; the crescent for anything else. */
    static int icon(String name) {
        for (int i = 0; i < NAMES.length; i++) if (NAMES[i].equals(name)) return ICONS[i];
        return R.drawable.ic_crescent;
    }

    private int px(float dps) {
        return Math.round(dps * dp);
    }

    private TextView small(String text, int color, float size) {
        TextView t = new TextView(ctx);
        t.setText(text);
        t.setTextSize(size);
        t.setTextColor(color);
        t.setLetterSpacing(0.08f);
        t.setTypeface(Fonts.outfit(ctx, 500));
        t.setSingleLine(true);
        t.setEllipsize(TextUtils.TruncateAt.END);
        return t;
    }
}
