package my.rafhan.app.tvhome;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Handler;
import android.os.Looper;
import android.text.format.DateFormat;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Every launcher option in one place (prefs "settings"), edited from the Settings page (gear
 * pill). Defaults are what the launcher did before there were settings, so nothing changes until
 * an option is edited. Listeners hear about changes on the main thread, like DockConfig.
 */
final class LauncherSettings {
    // Keys
    static final String ZONE = "prayer_zone", SHOW_PRAYER = "show_prayer", SHOW_USAGE = "show_usage",
            SHOW_SYSTEM = "show_system", SHOW_NOW = "show_now_playing", CLOCK = "clock",
            USAGE_EXCLUDED = "usage_excluded", WALL_DIM = "wallpaper_dim",
            ALERT_UPDATE = "alert_update", ALERT_PLAY = "alert_play", ALERT_HDMI = "alert_hdmi", ALERT_BT = "alert_bluetooth",
            ALERT_MEMORY = "alert_memory", MEMORY_LEVEL = "memory_level", NET_BANNER = "net_banner",
            REMOTE = "remote_on", WALLPAPER = "wallpaper", WEEK_START = "week_start",
            AZAN = "azan", AZAN_TEST = "azan_test",
            PRAYER_REMINDER = "prayer_reminder",
            STATUS_BAR = "status_bar", STATUS_LEFT = "status_left", STATUS_CENTER = "status_center",
            STATUS_RIGHT = "status_right", STATUS_POS = "status_position", STATUS_SIZE = "status_size",
            STATUS_BG = "status_background", APPS_PER_ROW = "apps_per_row";
    // The Globe screen saver's layers (Settings → Screen saver, when Globe is the one shown).
    static final String GLOBE_PRAYER_LINES = "globe_prayer_lines", GLOBE_MAKKAH = "globe_makkah",
            GLOBE_ZONE = "globe_zone", GLOBE_CITY_LIGHTS = "globe_city_lights", GLOBE_CLOUDS = "globe_clouds",
            GLOBE_SNOW = "globe_snow", GLOBE_AURORA = "globe_aurora", GLOBE_EDITS = "globe_edits",
            GLOBE_QUAKES = "globe_quakes", GLOBE_ISS = "globe_iss", GLOBE_TIANGONG = "globe_tiangong",
            GLOBE_HUBBLE = "globe_hubble", GLOBE_LEGEND = "globe_legend", GLOBE_WIND = "globe_wind",
            GLOBE_GLINT = "globe_glint", GLOBE_RIM = "globe_rim";

    /** What a status-bar slot shows. */
    static final int SLOT_NONE = 0, SLOT_SPEED = 1, SLOT_UPTIME = 2, SLOT_CLOCK = 3, SLOT_ELAPSED = 4;

    /** Clock format: follow the TV, or force one. */
    static final int CLOCK_AUTO = 0, CLOCK_12 = 1, CLOCK_24 = 2;
    /** Wallpaper dim levels (alpha of the black scrim) for Off / Low / Medium / High. */
    static final int[] DIM_ALPHA = {0, 70, 115, 160};

    interface Listener {
        void onSettingChanged(String key);
    }

    private static final CopyOnWriteArrayList<Listener> listeners = new CopyOnWriteArrayList<>();
    private static final Handler main = new Handler(Looper.getMainLooper());

    private LauncherSettings() {}

    static void addListener(Listener l) {
        listeners.add(l);
    }

    static void removeListener(Listener l) {
        listeners.remove(l);
    }

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getSharedPreferences("settings", Context.MODE_PRIVATE);
    }

    /** Not a stored setting: tells listeners (the azan in RemoteService) to play once now. */
    static void playAzanNow() {
        changed(AZAN_TEST);
    }

    private static void changed(String key) {
        main.post(() -> {
            for (Listener l : listeners) l.onSettingChanged(key);
        });
    }

    static boolean flag(Context ctx, String key) {
        return prefs(ctx).getBoolean(key, defaultFlag(key));
    }

    /**
     * Every switch starts on, except the info overlay (it would double up with an overlay app)
     * and the Globe's clouds and wind (they cover much of the map; wind also takes more CPU).
     */
    private static boolean defaultFlag(String key) {
        return !STATUS_BAR.equals(key) && !GLOBE_CLOUDS.equals(key) && !GLOBE_WIND.equals(key);
    }

    static void setFlag(Context ctx, String key, boolean on) {
        prefs(ctx).edit().putBoolean(key, on).apply();
        changed(key);
    }

    static int choice(Context ctx, String key) {
        int v = prefs(ctx).getInt(key, defaultChoice(key));
        // Apps per row once also offered 4: a saved 4 reads as 5, the smallest now.
        return APPS_PER_ROW.equals(key) ? Math.max(5, Math.min(7, v)) : v;
    }

    static void setChoice(Context ctx, String key, int value) {
        prefs(ctx).edit().putInt(key, value).apply();
        changed(key);
    }

    private static int defaultChoice(String key) {
        switch (key) {
            case WALL_DIM: return 2;      // Medium: the old fixed scrim
            case MEMORY_LEVEL: return 85; // %
            case WEEK_START: return 1;    // DayOfWeek: 1 = Monday, 7 = Sunday
            case PRAYER_REMINDER: return 5; // minutes before each prayer; 0 = off
            case STATUS_LEFT: return SLOT_SPEED;
            case STATUS_CENTER: return SLOT_UPTIME;
            case STATUS_RIGHT: return SLOT_CLOCK;
            case STATUS_SIZE: return 12;  // sp: Small
            case APPS_PER_ROW: return 5;  // the dock and each app-list row
            default: return 0;
        }
    }

    // ---------------------------------------------------------------- typed helpers

    static String zone(Context ctx) {
        return prefs(ctx).getString(ZONE, ctx.getString(R.string.prayer_zone));
    }

    static void setZone(Context ctx, String zone) {
        prefs(ctx).edit().putString(ZONE, zone).apply();
        changed(ZONE);
    }

    /**
     * "none", a built-in id (Wallpapers.BUILT_IN) or "custom". Default: a picture pushed over
     * ADB if there is one (as before Settings existed), else the first built-in.
     */
    static String wallpaper(Context ctx) {
        String w = prefs(ctx).getString(WALLPAPER, null);
        // A built-in that was replaced in an update falls back to the default.
        if (w != null && (w.equals(Wallpapers.NONE) || w.equals(Wallpapers.CUSTOM) || w.equals(Wallpapers.BING)
                || Wallpapers.photo(w) != null
                || (w.startsWith(Wallpapers.FILE) && Wallpapers.file(ctx, w) != null))) {
            return w; // a picture deleted from the Wallpapers folder falls back too
        }
        return Wallpapers.custom(ctx) != null ? Wallpapers.CUSTOM : Wallpapers.DEFAULT;
    }

    static void setWallpaper(Context ctx, String id) {
        prefs(ctx).edit().putString(WALLPAPER, id).apply();
        changed(WALLPAPER);
    }

    static boolean use24h(Context ctx) {
        int c = choice(ctx, CLOCK);
        return c == CLOCK_AUTO ? DateFormat.is24HourFormat(ctx) : c == CLOCK_24;
    }

    /** Apps left out of Most used (Live TV by default). */
    static Set<String> usageExcluded(Context ctx) {
        Set<String> s = prefs(ctx).getStringSet(USAGE_EXCLUDED, null);
        return s != null ? new HashSet<>(s)
                : new HashSet<>(Arrays.asList(ctx.getResources().getStringArray(R.array.usage_excluded)));
    }

    /**
     * The left-out apps that are on the TV (installed and switched on), as the pickers list them.
     * Uninstalled ones stay saved, so a reinstalled app is still left out.
     */
    static Set<String> usageExcludedPresent(Context ctx) {
        Set<String> on = new HashSet<>();
        for (String pkg : usageExcluded(ctx)) {
            try {
                if (ctx.getPackageManager().getApplicationInfo(pkg, 0).enabled) on.add(pkg);
            } catch (PackageManager.NameNotFoundException ignored) {
            }
        }
        return on;
    }

    static void setUsageExcluded(Context ctx, String pkg, boolean excluded) {
        Set<String> s = usageExcluded(ctx);
        if (excluded) s.add(pkg);
        else s.remove(pkg);
        prefs(ctx).edit().putStringSet(USAGE_EXCLUDED, s).apply();
        changed(USAGE_EXCLUDED);
    }
}
