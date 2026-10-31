package my.rafhan.app.tvhome;

import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The phone remote's Settings tab: the same options as the TV's Settings page (minus the phone
 * remote's own switch and "Forget all paired phones", which would cut the phone off), sent as a
 * schema the page renders. A change is checked against that schema before it's saved, so the
 * phone can only set what the TV offers. Also the wallpaper list with small thumbnails.
 */
final class RemoteSettings {
    private static final int THUMB_W = 360;

    private final Context ctx;
    private final AtomicBoolean fetching = new AtomicBoolean();
    // Thumbnails by file path + modified time; a few dozen KB each.
    private final Map<String, byte[]> thumbs = new LinkedHashMap<String, byte[]>(16, .75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, byte[]> e) {
            return size() > 24;
        }
    };

    RemoteSettings(Context ctx) {
        this.ctx = ctx;
    }

    void trim() {
        synchronized (thumbs) {
            thumbs.clear();
        }
    }

    // ---------------------------------------------------------------- schema

    JSONObject schema() throws JSONException {
        JSONArray cats = new JSONArray();
        cats.put(category("Home screen", "Cards, clock and wallpaper",
                choice(LauncherSettings.CLOCK, "Clock", "Auto follows the TV's own setting",
                        new int[] {LauncherSettings.CLOCK_AUTO, LauncherSettings.CLOCK_12, LauncherSettings.CLOCK_24},
                        new String[] {"Auto", "12-hour", "24-hour"}),
                choice(LauncherSettings.APPS_PER_ROW, "Apps per row", "The dock and the app list. More apps, smaller tiles",
                        new int[] {5, 6, 7}, new String[] {"5", "6", "7"}),
                toggle(LauncherSettings.SHOW_SYSTEM, "System card", "CPU, RAM, storage, network and USB debugging"),
                toggle(LauncherSettings.SHOW_NOW, "Now Playing card", "Cover, title and controls while something plays"),
                choice(LauncherSettings.WALL_DIM, "Wallpaper dim", "How dark the wallpaper is behind the cards",
                        new int[] {0, 1, 2, 3}, new String[] {"Off", "Low", "Medium", "High"}),
                row("wallpaper", LauncherSettings.WALLPAPER, "Wallpaper", "Bing photo of the day, built-in photos or your own")
                        .put("value", LauncherSettings.wallpaper(ctx))
                        .put("name", SettingsScreen.wallpaperName(LauncherSettings.wallpaper(ctx)))));
        String zone = LauncherSettings.zone(ctx);
        String[] z = zoneEntry(ctx, zone);
        cats.put(category("Prayer times", "Zone, card and azan",
                row("zone", LauncherSettings.ZONE, "Zone", z != null ? z[0] + " · " + z[2] : "JAKIM e-solat zone")
                        .put("value", zone),
                toggle(LauncherSettings.SHOW_PRAYER, "Prayer card", "Today's five prayer times on the home screen"),
                toggle(LauncherSettings.AZAN, "Azan", "Plays the azan and shows a banner at each prayer time, while the TV is on"),
                choice(LauncherSettings.PRAYER_REMINDER, "Reminder before prayer",
                        "A banner over any app, without sound, while the TV is on",
                        new int[] {0, 5, 10, 15, 30}, new String[] {"Off", "5 min", "10 min", "15 min", "30 min"}),
                row("action", LauncherSettings.AZAN_TEST, "Play azan now", "Hear it on the TV and check the volume")));
        cats.put(category("Weather", "Forecast and air quality",
                toggle(LauncherSettings.SHOW_WEATHER, "Weather card",
                        "Today's forecast and air quality on the home screen")));
        cats.put(category("Most used", "Your top apps this week",
                toggle(LauncherSettings.SHOW_USAGE, "Most used card", "Your top 5 apps this week on the home screen"),
                choice(LauncherSettings.WEEK_START, "Week starts on", "Counts from this day up to today",
                        new int[] {1, 7}, new String[] {"Monday", "Sunday"}),
                row("apps", LauncherSettings.USAGE_EXCLUDED, "Leave out",
                        "Not on the card, like Live TV (it's only the HDMI and antenna screen)")
                        .put("value", new JSONArray(LauncherSettings.usageExcludedPresent(ctx)))));
        cats.put(category("Notifications", "What the bell tells you",
                toggle(LauncherSettings.ALERT_UPDATE, "TV Home updates", "Checked every 6 hours"),
                toggle(LauncherSettings.ALERT_PLAY, "Play Store updates", "Apps with an update waiting, checked every hour"),
                toggle(LauncherSettings.ALERT_HDMI, "New HDMI devices", "A device that appears over HDMI-CEC"),
                toggle(LauncherSettings.ALERT_BT, "Bluetooth devices", "Connecting or disconnecting (not the TV remote)"),
                toggle(LauncherSettings.NET_BANNER, "Connection status",
                        "Wi-Fi connected, disconnected or no internet, over any app"),
                toggle(LauncherSettings.ALERT_MEMORY, "Low memory", "When RAM use passes the level below"),
                choice(LauncherSettings.MEMORY_LEVEL, "Low-memory level", "Alerts again after dropping 5 points under it",
                        new int[] {80, 85, 90}, new String[] {"80 %", "85 %", "90 %"})));
        cats.put(category("Info overlay", "Speed, uptime and clock over apps",
                toggle(LauncherSettings.STATUS_BAR, "Show over apps",
                        "A thin bar over every app, never on the home screen"),
                choice(LauncherSettings.STATUS_LEFT, "Left", "What the left end shows",
                        StatusOverlay.SLOT_VALUES, StatusOverlay.SLOT_NAMES),
                choice(LauncherSettings.STATUS_CENTER, "Centre", "What the middle shows",
                        StatusOverlay.SLOT_VALUES, StatusOverlay.SLOT_NAMES),
                choice(LauncherSettings.STATUS_RIGHT, "Right", "What the right end shows",
                        StatusOverlay.SLOT_VALUES, StatusOverlay.SLOT_NAMES),
                choice(LauncherSettings.STATUS_POS, "Position", "Top or bottom edge of the screen",
                        StatusOverlay.POS_VALUES, StatusOverlay.POS_NAMES),
                choice(LauncherSettings.STATUS_SIZE, "Text size", "Tiny 10, Small 12, Medium 14, Large 18",
                        StatusOverlay.SIZE_VALUES, StatusOverlay.SIZE_NAMES),
                toggle(LauncherSettings.STATUS_BG, "Background", "A dark see-through box behind the text")));
        return new JSONObject().put("ok", true).put("categories", cats);
    }

    /**
     * Applies {key, value} (and "on" for the Leave out list). Returns why it was refused, or null.
     * Only keys in the schema are accepted, with a value of the row's kind.
     */
    String set(JSONObject body) throws JSONException {
        String key = body.optString("key");
        JSONObject row = find(schema(), key);
        if (row == null) return "Unknown setting.";
        Object v = body.opt("value");
        switch (row.getString("type")) {
            case "toggle":
                if (!(v instanceof Boolean)) return "Expected on or off.";
                LauncherSettings.setFlag(ctx, key, (Boolean) v);
                return null;
            case "choice": {
                if (!(v instanceof Number)) return "Expected a number.";
                int n = ((Number) v).intValue();
                JSONArray opts = row.getJSONArray("options");
                for (int i = 0; i < opts.length(); i++) {
                    if (opts.getJSONArray(i).getInt(0) == n) {
                        LauncherSettings.setChoice(ctx, key, n);
                        return null;
                    }
                }
                return "Not one of the choices.";
            }
            case "zone":
                if (!(v instanceof String) || zoneEntry(ctx, (String) v) == null) return "Unknown zone.";
                LauncherSettings.setZone(ctx, (String) v);
                return null;
            case "wallpaper": {
                JSONArray items = wallpapers().getJSONArray("items");
                for (int i = 0; i < items.length(); i++) {
                    if (items.getJSONObject(i).getString("id").equals(v)) {
                        LauncherSettings.setWallpaper(ctx, (String) v);
                        return null;
                    }
                }
                return "That wallpaper isn't on the TV.";
            }
            case "action":
                LauncherSettings.playAzanNow();
                return null;
            case "apps": {
                if (!(v instanceof String)) return "No app given.";
                try {
                    ctx.getPackageManager().getApplicationInfo((String) v, 0);
                } catch (PackageManager.NameNotFoundException e) {
                    return "No such app.";
                }
                LauncherSettings.setUsageExcluded(ctx, (String) v, body.optBoolean("on", true));
                return null;
            }
            default:
                return "Unknown setting.";
        }
    }

    /** The 60 JAKIM zones, for the phone's searchable list. */
    JSONObject zones() throws JSONException {
        JSONArray out = new JSONArray();
        for (String e : ctx.getResources().getStringArray(R.array.prayer_zones)) {
            String[] z = e.split("\\|", 3);
            if (z.length == 3) out.put(new JSONObject().put("state", z[0]).put("code", z[1]).put("area", z[2]));
        }
        return new JSONObject().put("ok", true).put("current", LauncherSettings.zone(ctx)).put("zones", out);
    }

    /** {state, code, areas} for a zone code, or null. */
    static String[] zoneEntry(Context ctx, String code) {
        for (String e : ctx.getResources().getStringArray(R.array.prayer_zones)) {
            String[] z = e.split("\\|", 3);
            if (z.length == 3 && z[1].equals(code)) return z;
        }
        return null;
    }

    // ---------------------------------------------------------------- wallpapers

    /**
     * Everything the TV's Wallpaper list offers, in the same order. Built-ins not downloaded yet
     * (and a Bing picture that's due) are fetched in the background; "pending" tells the phone to
     * ask again for their thumbnails.
     */
    JSONObject wallpapers() throws JSONException {
        String current = LauncherSettings.wallpaper(ctx);
        JSONArray items = new JSONArray();
        boolean pending = false;
        items.put(wallpaper(Wallpapers.NONE, "None", "Plain dark background", null));
        File bing = Bing.shown(ctx);
        String cap = Bing.caption(ctx);
        items.put(wallpaper(Wallpapers.BING, "Bing photo of the day",
                bing != null && !cap.isEmpty() ? cap : "A new photo every day", bing));
        for (Wallpapers.Photo p : Wallpapers.BUILT_IN) {
            File f = Wallpapers.file(ctx, p.id);
            if (f == null) pending = true;
            items.put(wallpaper(p.id, p.name, f != null ? p.credit : "Downloading…", f));
        }
        for (File f : Wallpapers.userPictures()) {
            String name = f.getName();
            items.put(wallpaper(Wallpapers.FILE + name, SettingsScreen.baseName(name),
                    String.format(Locale.US, "Your picture · %.1f MB", f.length() / 1048576.0), f));
        }
        File custom = Wallpapers.custom(ctx);
        if (custom != null) items.put(wallpaper(Wallpapers.CUSTOM, "Your picture", "Pushed over ADB", custom));
        if (pending || Bing.due(ctx)) fetchMissing();
        String folder;
        try {
            folder = Wallpapers.folder().getCanonicalPath();
        } catch (IOException e) {
            folder = Wallpapers.folder().getPath();
        }
        return new JSONObject().put("ok", true).put("current", current).put("folder", folder)
                .put("pending", pending || fetching.get()).put("items", items);
    }

    private static JSONObject wallpaper(String id, String name, String sub, File f) throws JSONException {
        return new JSONObject().put("id", id).put("name", name).put("sub", sub)
                .put("thumb", f != null ? f.lastModified() : 0);
    }

    private void fetchMissing() {
        if (!fetching.compareAndSet(false, true)) return;
        new Thread(() -> {
            try {
                if (Bing.due(ctx)) Bing.refresh(ctx);
                for (Wallpapers.Photo p : Wallpapers.BUILT_IN) Wallpapers.fetch(ctx, p.id);
            } finally {
                fetching.set(false);
            }
        }, "remote-wallpapers").start();
    }

    /** A JPEG about 360 px wide, or null if that wallpaper has no picture (yet). Blocking. */
    byte[] thumb(String id) {
        File f = id == null ? null : Wallpapers.file(ctx, id);
        if (f == null) return null;
        String key = f.getPath() + "@" + f.lastModified();
        synchronized (thumbs) {
            byte[] hit = thumbs.get(key);
            if (hit != null) return hit;
        }
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(f.getPath(), o);
        if (o.outWidth <= 0) return null;
        o.inSampleSize = 1;
        while (o.outWidth / (o.inSampleSize * 2) >= THUMB_W) o.inSampleSize *= 2;
        o.inJustDecodeBounds = false;
        Bitmap b = BitmapFactory.decodeFile(f.getPath(), o);
        if (b == null) return null;
        if (b.getWidth() > THUMB_W) {
            Bitmap s = Bitmap.createScaledBitmap(b, THUMB_W, Math.max(1, b.getHeight() * THUMB_W / b.getWidth()), true);
            b.recycle();
            b = s;
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        b.compress(Bitmap.CompressFormat.JPEG, 80, out);
        b.recycle();
        byte[] jpg = out.toByteArray();
        synchronized (thumbs) {
            thumbs.put(key, jpg);
        }
        return jpg;
    }

    // ---------------------------------------------------------------- helpers

    private static JSONObject find(JSONObject schema, String key) throws JSONException {
        JSONArray cats = schema.getJSONArray("categories");
        for (int c = 0; c < cats.length(); c++) {
            JSONArray rows = cats.getJSONObject(c).getJSONArray("rows");
            for (int r = 0; r < rows.length(); r++) {
                if (rows.getJSONObject(r).getString("key").equals(key)) return rows.getJSONObject(r);
            }
        }
        return null;
    }

    private static JSONObject category(String title, String sub, JSONObject... rows) throws JSONException {
        JSONArray a = new JSONArray();
        for (JSONObject r : rows) a.put(r);
        return new JSONObject().put("title", title).put("sub", sub).put("rows", a);
    }

    private static JSONObject row(String type, String key, String label, String sub) throws JSONException {
        return new JSONObject().put("type", type).put("key", key).put("label", label).put("sub", sub);
    }

    private JSONObject toggle(String key, String label, String sub) throws JSONException {
        return row("toggle", key, label, sub).put("value", LauncherSettings.flag(ctx, key));
    }

    private JSONObject choice(String key, String label, String sub, int[] values, String[] names) throws JSONException {
        JSONArray opts = new JSONArray();
        for (int i = 0; i < values.length; i++) opts.put(new JSONArray().put(values[i]).put(names[i]));
        return row("choice", key, label, sub).put("value", LauncherSettings.choice(ctx, key)).put("options", opts);
    }
}
