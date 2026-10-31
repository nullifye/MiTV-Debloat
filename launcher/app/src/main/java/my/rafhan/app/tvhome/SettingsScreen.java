package my.rafhan.app.tvhome;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.File;
import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.function.Supplier;

/**
 * The launcher's own Settings (gear pill), laid out like Matinee's: categories on the left, the
 * category's rows on the right (switches, choices that cycle on OK, pickers that open a list in
 * the right pane, actions). Every option lives in LauncherSettings; the parts of the launcher
 * that use one listen for changes. MainActivity owns the keys and the focus highlight and asks
 * this class what's focused.
 */
final class SettingsScreen {
    private static final int TEXT = 0xFFFFFFFF, DIM = 0x8CFFFFFF, GREEN = 0xFF34C759, AMBER = 0xFFFFB340;
    private static final int TOGGLE = 0, CHOICE = 1, PICKER = 2, ACTION = 3, INFO = 4, CHECK = 5;

    private static final int HOME = 0, PRAYER = 1, SAVER = 2, USAGE = 3, ALERTS = 4, STATUS = 5, REMOTE = 6,
            HIDDEN = 7; // About: 8
    private static final String[] CATS = {"Home screen", "Prayer times", "Screen saver", "Most used", "Notifications",
            "Info overlay", "Phone remote", "Running apps", "About"};
    private static final String[] CAT_SUBS = {"Cards, clock and wallpaper", "Zone and card",
            "What the TV shows when idle", "Your top apps this week", "What the bell tells you",
            "Speed, uptime and clock over apps", "Control the TV from your phone", "Apps hidden from the list",
            "Version and TV settings"};
    private static final int[] CAT_ICONS = {R.drawable.ic_tv, R.drawable.ic_crescent, R.drawable.ic_screensaver,
            R.drawable.ic_chart, R.drawable.ic_bell, R.drawable.ic_status_bar, R.drawable.ic_phone, R.drawable.ic_ram,
            R.drawable.ic_info};
    private static final String LIVE_TV = "com.mediatek.wwtv.tvcenter";

    interface Host {
        /** Focus or content changed: the highlight should follow. */
        void onFocusMoved();

        void openTvSettings();

        /** The TV's own screen-saver settings, where "Prayer time" or "Live log" can be chosen. */
        void openScreenSaverSettings();

        /** Starts the screen saver now, as a preview. */
        void startScreenSaver();

        /** Shows a wallpaper without saving it (focus in the Wallpaper list); null: the saved one. */
        void previewWallpaper(String id);
    }

    private static final class Row {
        final int type;
        final String label;
        String sub, key;
        int[] values;
        String[] names;
        Supplier<String> value;
        Runnable action;
        java.util.function.BooleanSupplier on; // a switch for something other than a LauncherSettings flag
        boolean checked;
        String id;         // wallpaper list: what this row shows
        boolean thumbSlot; // wallpaper list: a small preview on the left
        Bitmap thumb;
        String heading;    // a separator above the row, with this small title (never focused)

        Row(int type, String label) {
            this.type = type;
            this.label = label;
        }
    }

    private final Context ctx;
    private final Executor worker;
    private final Host host;
    private final float dp;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final List<View> catViews = new ArrayList<>();
    private final List<View> rowViews = new ArrayList<>();
    private final LinearLayout rowList;
    private final ScrollView scroll, catScroll;
    private final TextView crumb, paneTitle, paneSub;

    private int col, cat, row;
    private List<Row> rows = new ArrayList<>();
    // A picker (zone list, app list) replaces the rows until Back.
    private List<Row> picker;
    private String pickerTitle;
    private int pickerFrom;
    private boolean confirmForget;
    private String confirmPhone; // Phone remote: the phone whose Forget is waiting for a second OK

    SettingsScreen(Context ctx, FrameLayout root, Executor worker, Host host) {
        this.ctx = ctx;
        this.worker = worker;
        this.host = host;
        dp = ctx.getResources().getDisplayMetrics().density;
        // Dark backdrop: text stays readable over bright wallpapers, which still show through
        // (dimmed) as a preview while picking one.
        root.setBackgroundColor(0xB8000000);

        // Title: [gear] Settings / Category
        LinearLayout title = new LinearLayout(ctx);
        title.setGravity(Gravity.CENTER_VERTICAL);
        ImageView gear = icon(R.drawable.ic_settings, TEXT, 22);
        gear.setBackgroundResource(R.drawable.sys_bg);
        gear.setScaleType(ImageView.ScaleType.CENTER);
        title.addView(gear, new LinearLayout.LayoutParams(px(44), px(44)));
        TextView name = text(30, 300, TEXT);
        name.setText("Settings");
        LinearLayout.LayoutParams nlp = wrap();
        nlp.setMarginStart(px(16));
        title.addView(name, nlp);
        crumb = text(15, 400, DIM);
        LinearLayout.LayoutParams clp = wrap();
        clp.setMarginStart(px(12));
        title.addView(crumb, clp);
        FrameLayout.LayoutParams tlp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        tlp.setMargins(px(64), px(44), 0, 0);
        root.addView(title, tlp);

        LinearLayout body = new LinearLayout(ctx);
        FrameLayout.LayoutParams blp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT);
        blp.setMargins(px(56), px(112), px(64), px(52));
        root.addView(body, blp);

        // Left: categories.
        LinearLayout cats = new LinearLayout(ctx);
        cats.setOrientation(LinearLayout.VERTICAL);
        // Scrolls when the screen is too short for every category (720p sets at a low density).
        catScroll = new ScrollView(ctx);
        catScroll.setVerticalScrollBarEnabled(false);
        catScroll.setFocusable(false);
        catScroll.setVerticalFadingEdgeEnabled(true);
        catScroll.setFadingEdgeLength(px(24));
        catScroll.addView(cats, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        body.addView(catScroll, new LinearLayout.LayoutParams(px(290), ViewGroup.LayoutParams.MATCH_PARENT));
        for (int i = 0; i < CATS.length; i++) {
            LinearLayout c = new LinearLayout(ctx);
            c.setGravity(Gravity.CENTER_VERTICAL);
            c.setPadding(px(14), 0, px(14), 0);
            ImageView ic = icon(CAT_ICONS[i], TEXT, 18);
            ic.setBackgroundResource(R.drawable.np_button);
            ic.setScaleType(ImageView.ScaleType.CENTER);
            c.addView(ic, new LinearLayout.LayoutParams(px(32), px(32)));
            TextView t = text(16, 500, TEXT);
            t.setText(CATS[i]);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            lp.setMarginStart(px(14));
            c.addView(t, lp);
            LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, px(50));
            rlp.bottomMargin = px(4);
            cats.addView(c, rlp);
            catViews.add(c);
        }

        // Right: the category's card.
        LinearLayout pane = new LinearLayout(ctx);
        pane.setOrientation(LinearLayout.VERTICAL);
        pane.setBackgroundResource(R.drawable.sys_bg);
        pane.setPadding(px(18), px(16), px(18), px(8));
        LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f);
        plp.setMarginStart(px(28));
        body.addView(pane, plp);
        LinearLayout head = new LinearLayout(ctx);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.setPadding(px(6), 0, px(6), 0);
        paneTitle = text(21, 600, TEXT);
        head.addView(paneTitle, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        paneSub = text(13, 400, DIM);
        head.addView(paneSub);
        LinearLayout.LayoutParams hlp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        hlp.bottomMargin = px(10);
        pane.addView(head, hlp);
        scroll = new ScrollView(ctx);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setFocusable(false);
        scroll.setVerticalFadingEdgeEnabled(true);
        scroll.setFadingEdgeLength(px(24));
        rowList = new LinearLayout(ctx);
        rowList.setOrientation(LinearLayout.VERTICAL);
        rowList.setPadding(0, px(4), 0, px(12));
        scroll.addView(rowList, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        pane.addView(scroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        TextView hint = text(12, 400, DIM);
        hint.setText("BACK  return     ▲ ▼  move     OK  select");
        FrameLayout.LayoutParams hl = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM | Gravity.START);
        hl.setMargins(px(64), 0, 0, px(20));
        root.addView(hint, hl);
    }

    // ---------------------------------------------------------------- focus (MainActivity)

    /** Opened from the gear: categories, on the last category used. */
    void open() {
        col = 0;
        row = 0;
        picker = null;
        confirmForget = false;
        confirmPhone = null;
        render();
    }

    /** What the highlight frames. */
    View focused() {
        if (col == 0) return catViews.get(cat);
        return row < rowViews.size() ? rowViews.get(row) : null;
    }

    /** Rows again from current state (e.g. the screen saver switch after a preview). */
    void refreshRows() {
        if (picker != null) return;
        rows = rowsFor(cat);
        render();
    }

    void up() {
        move(-1);
    }

    void down() {
        move(1);
    }

    private void move(int d) {
        confirmForget = false;
        confirmPhone = null;
        if (col == 0) {
            int c = cat + d;
            if (c < 0 || c >= CATS.length) return;
            cat = c;
            row = 0;
            render();
        } else {
            int r = row + d;
            if (r < 0 || r >= current().size()) return;
            row = r;
            if (picker == null) {
                render(); // rebuilt: a pending "press OK again" goes back to normal
            } else {
                scrollToFocus(); // long lists (zones) aren't rebuilt per key press
                host.onFocusMoved();
                if (inWallpaperList()) host.previewWallpaper(picker.get(row).id);
            }
        }
    }

    void left() {
        if (col == 1) back();
    }

    void right() {
        if (col == 0 && !current().isEmpty()) {
            col = 1;
            row = 0;
            render();
        }
    }

    void select() {
        if (col == 0) {
            right();
            return;
        }
        List<Row> list = current();
        if (row >= list.size()) return;
        act(list.get(row));
    }

    /** Back: picker → rows → categories → (true) close Settings. */
    boolean back() {
        confirmForget = false;
        confirmPhone = null;
        if (picker != null) {
            closePicker();
            return false;
        }
        if (col == 1) {
            col = 0;
            render();
            return false;
        }
        return true;
    }

    // ---------------------------------------------------------------- actions

    private void act(Row r) {
        switch (r.type) {
            case TOGGLE:
                if (r.on != null) r.action.run();
                else LauncherSettings.setFlag(ctx, r.key, !LauncherSettings.flag(ctx, r.key));
                break;
            case CHOICE: {
                int now = LauncherSettings.choice(ctx, r.key), i = 0;
                for (int n = 0; n < r.values.length; n++) if (r.values[n] == now) i = n;
                LauncherSettings.setChoice(ctx, r.key, r.values[(i + 1) % r.values.length]);
                break;
            }
            case PICKER:
            case ACTION:
            case CHECK:
                if (r.action != null) r.action.run();
                break;
            default:
                return; // INFO: nothing to do
        }
        if (picker == null) rows = rowsFor(cat);
        render();
    }

    private void openPicker(String title, List<Row> items, int focus) {
        pickerFrom = row;
        pickerTitle = title;
        picker = items;
        row = Math.max(0, Math.min(focus, items.size() - 1));
        render();
    }

    private void closePicker() {
        if (inWallpaperList()) host.previewWallpaper(null); // left without OK: back to the saved one
        picker = null;
        row = pickerFrom;
        rows = rowsFor(cat);
        render();
    }

    private List<Row> current() {
        return picker != null ? picker : rows;
    }

    // ---------------------------------------------------------------- rows per category

    private List<Row> rowsFor(int c) {
        List<Row> out = new ArrayList<>();
        switch (c) {
            case HOME: homeRows(out); break;
            case PRAYER: prayerRows(out); break;
            case SAVER: saverRows(out); break;
            case USAGE: usageRows(out); break;
            case ALERTS: alertRows(out); break;
            case STATUS: statusRows(out); break;
            case REMOTE: remoteRows(out); break;
            case HIDDEN: hiddenRows(out); break;
            default: aboutRows(out); break;
        }
        return out;
    }

    /**
     * The TV's screen saver (ScreenSaver): on/off, which one (OK cycles Prayer time → Live log →
     * Globe → the TV's others), a preview, and with Globe chosen, a switch for each of its layers. Some TVs can't open their screen-saver page from another
     * app, so with WRITE_SECURE_SETTINGS TV Home sets it itself; without, it opens the TV's Settings.
     */
    private void saverRows(List<Row> out) {
        android.content.ComponentName current = ScreenSaver.current(ctx);
        String name = ScreenSaver.label(ctx, current);
        if (ScreenSaver.canSet(ctx)) {
            Row on = new Row(TOGGLE, "Screen saver");
            on.sub = "Starts when the TV has been idle for a while";
            on.on = () -> ScreenSaver.enabled(ctx);
            on.action = () -> ScreenSaver.setEnabled(ctx, !ScreenSaver.enabled(ctx));
            out.add(on);
            Row show = new Row(PICKER, "Show");
            // What the chosen one shows, so the row explains itself as OK cycles.
            String what = ScreenSaver.prayer(ctx).equals(current) ? "Big clock, dates and prayer times"
                    : ScreenSaver.log(ctx).equals(current) ? "The TV's live log, scrolling on black"
                    : ScreenSaver.globe(ctx).equals(current) ? "The Earth now, with live dots. The arrows turn it"
                    : "One of the TV's own screen savers";
            show.sub = what;
            show.value = () -> name;
            show.action = () -> ScreenSaver.next(ctx);
            out.add(show);
        } else {
            Row pick = new Row(ACTION, "Choose a screen saver");
            pick.sub = "In the TV's Settings: \"Prayer time\" (clock and prayer times), \"Live log\" or \"Globe\"";
            pick.value = () -> name;
            pick.action = host::openScreenSaverSettings;
            out.add(pick);
        }
        Row now = new Row(ACTION, "Start now");
        now.sub = "Preview it: any key ends it";
        now.action = host::startScreenSaver;
        out.add(now);
        if (ScreenSaver.globe(ctx).equals(current)) {
            Row turning = choice(LauncherSettings.GLOBE_SPIN, "Turning",
                    "A spin every 2 minutes, or the Earth's real speed",
                    new int[]{LauncherSettings.SPIN_TWO_MINUTES, LauncherSettings.SPIN_REAL_TIME},
                    new String[]{"Every 2 minutes", "Real time"});
            turning.heading = "Globe";
            out.add(turning);
            out.add(toggle(LauncherSettings.GLOBE_PRAYER_LINES, "Prayer lines", "Where it's Maghrib and Fajr right now"));
            out.add(toggle(LauncherSettings.GLOBE_MAKKAH, "Makkah and qibla", "The Kaaba, and the way to it from your zone"));
            out.add(toggle(LauncherSettings.GLOBE_ZONE, "Your zone", "Where your prayer zone is"));
            out.add(toggle(LauncherSettings.GLOBE_CITY_LIGHTS, "City lights", "The night side's lights"));
            out.add(toggle(LauncherSettings.GLOBE_GLINT, "Sun glint", "The sun's reflection on the sea"));
            out.add(toggle(LauncherSettings.GLOBE_RIM, "Sunlit rim", "The atmosphere glows brighter on the sun's side"));
            out.add(toggle(LauncherSettings.GLOBE_SNOW, "Snow and sea ice", "From NASA, daily"));
            out.add(toggle(LauncherSettings.GLOBE_SKY, "Sky", "The Sun, the Moon in its phase, real stars and deep-sky objects"));
            out.add(toggle(LauncherSettings.GLOBE_CLOUDS, "Clouds", "From weather satellites, every 3 hours"));
            out.add(toggle(LauncherSettings.GLOBE_WIND, "Wind", "Moving lines: the wind near the ground, NOAA's forecast"));
            out.add(toggle(LauncherSettings.GLOBE_AURORA, "Aurora", "NOAA's forecast, every 15 minutes"));
            out.add(toggle(LauncherSettings.GLOBE_EDITS, "Map edits", "OpenStreetMap edits as people save them"));
            out.add(toggle(LauncherSettings.GLOBE_QUAKES, "Earthquakes", "Magnitude 2.5 and up, the past day"));
            out.add(toggle(LauncherSettings.GLOBE_ISS, "ISS", "The International Space Station"));
            out.add(toggle(LauncherSettings.GLOBE_TIANGONG, "Tiangong", "China's space station"));
            out.add(toggle(LauncherSettings.GLOBE_HUBBLE, "Hubble", "The Hubble Space Telescope"));
            out.add(toggle(LauncherSettings.GLOBE_LEGEND, "Legend", "What each colour and icon means, with the latest edit and earthquake"));
        }
    }

    private void prayerRows(List<Row> out) {
        String zone = LauncherSettings.zone(ctx);
        String[] z = RemoteSettings.zoneEntry(ctx, zone);
        Row r = new Row(PICKER, "Zone");
        r.sub = z != null ? z[0] + " · " + z[2] : "JAKIM e-solat zone";
        r.value = () -> zone;
        r.action = this::openZonePicker;
        out.add(r);
        out.add(toggle(LauncherSettings.SHOW_PRAYER, "Prayer card", "Today's five prayer times on the home screen"));
        out.add(toggle(LauncherSettings.AZAN, "Azan", "Plays the azan and shows a banner at each prayer time, while the TV is on"));
        out.add(choice(LauncherSettings.PRAYER_REMINDER, "Reminder before prayer",
                "A banner over any app, without sound, while the TV is on",
                new int[] {0, 5, 10, 15, 30}, new String[] {"Off", "5 min", "10 min", "15 min", "30 min"}));
        Row test = new Row(ACTION, "Play azan now");
        test.sub = "Hear it and check the volume";
        test.action = LauncherSettings::playAzanNow;
        out.add(test);
    }

    private void usageRows(List<Row> out) {
        out.add(toggle(LauncherSettings.SHOW_USAGE, "Most used card", "Your top 5 apps this week on the home screen"));
        out.add(choice(LauncherSettings.WEEK_START, "Week starts on", "Counts from this day up to today",
                new int[] {1, 7}, new String[] {"Monday", "Sunday"}));
        Row ex = new Row(PICKER, "Leave out");
        ex.sub = "Not on the card, like Live TV (it's only the HDMI and antenna screen)";
        int n = LauncherSettings.usageExcludedPresent(ctx).size();
        ex.value = () -> n == 1 ? "1 app" : n + " apps";
        ex.action = this::openUsagePicker;
        out.add(ex);
    }

    private void homeRows(List<Row> out) {
        out.add(choice(LauncherSettings.CLOCK, "Clock", "Auto follows the TV's own setting",
                new int[] {LauncherSettings.CLOCK_AUTO, LauncherSettings.CLOCK_12, LauncherSettings.CLOCK_24},
                new String[] {"Auto", "12-hour", "24-hour"}));
        out.add(choice(LauncherSettings.APPS_PER_ROW, "Apps per row", "The dock and the app list. More apps, smaller tiles",
                new int[] {5, 6, 7}, new String[] {"5", "6", "7"}));
        out.add(toggle(LauncherSettings.SHOW_SYSTEM, "System card", "CPU, RAM, storage, network and USB debugging"));
        out.add(toggle(LauncherSettings.SHOW_NOW, "Now Playing card", "Cover, title and controls while something plays"));
        out.add(choice(LauncherSettings.WALL_DIM, "Wallpaper dim", "How dark the wallpaper is behind the cards",
                new int[] {0, 1, 2, 3}, new String[] {"Off", "Low", "Medium", "High"}));
        Row w = new Row(PICKER, "Wallpaper");
        w.sub = "Bing photo of the day, built-in photos or your own";
        String name = wallpaperName(LauncherSettings.wallpaper(ctx));
        w.value = () -> name;
        w.action = this::openWallpaperPicker;
        out.add(w);
    }

    /** The name the wallpaper row shows (also the phone remote's). */
    static String wallpaperName(String choice) {
        if (Wallpapers.CUSTOM.equals(choice)) return "Your picture";
        if (Wallpapers.BING.equals(choice)) return "Bing daily";
        if (choice.startsWith(Wallpapers.FILE)) return baseName(choice.substring(Wallpapers.FILE.length()));
        Wallpapers.Photo p = Wallpapers.photo(choice);
        return p != null ? p.name : "None";
    }

    private void alertRows(List<Row> out) {
        out.add(toggle(LauncherSettings.ALERT_UPDATE, "TV Home updates", "Checked every 6 hours"));
        out.add(toggle(LauncherSettings.ALERT_PLAY, "Play Store updates", "Apps with an update waiting, checked every hour"));
        out.add(toggle(LauncherSettings.ALERT_HDMI, "New HDMI devices", "A device that appears over HDMI-CEC"));
        out.add(toggle(LauncherSettings.ALERT_BT, "Bluetooth devices", "Connecting or disconnecting (not the TV remote)"));
        out.add(toggle(LauncherSettings.NET_BANNER, "Connection status",
                "Wi-Fi connected, disconnected or no internet, over any app"));
        out.add(toggle(LauncherSettings.ALERT_MEMORY, "Low memory", "When RAM use passes the level below"));
        out.add(choice(LauncherSettings.MEMORY_LEVEL, "Low-memory level", "Alerts again after dropping 5 points under it",
                new int[] {80, 85, 90}, new String[] {"80 %", "85 %", "90 %"}));
    }

    private void statusRows(List<Row> out) {
        out.add(toggle(LauncherSettings.STATUS_BAR, "Show over apps",
                "A thin bar over every app, never on the home screen"));
        out.add(choice(LauncherSettings.STATUS_LEFT, "Left", "What the left end shows",
                StatusOverlay.SLOT_VALUES, StatusOverlay.SLOT_NAMES));
        out.add(choice(LauncherSettings.STATUS_CENTER, "Centre", "What the middle shows",
                StatusOverlay.SLOT_VALUES, StatusOverlay.SLOT_NAMES));
        out.add(choice(LauncherSettings.STATUS_RIGHT, "Right", "What the right end shows",
                StatusOverlay.SLOT_VALUES, StatusOverlay.SLOT_NAMES));
        out.add(choice(LauncherSettings.STATUS_POS, "Position", "Top or bottom edge of the screen",
                StatusOverlay.POS_VALUES, StatusOverlay.POS_NAMES));
        out.add(choice(LauncherSettings.STATUS_SIZE, "Text size", "Tiny 10, Small 12, Medium 14, Large 18",
                StatusOverlay.SIZE_VALUES, StatusOverlay.SIZE_NAMES));
        out.add(toggle(LauncherSettings.STATUS_BG, "Background", "A dark see-through box behind the text"));
    }

    private void remoteRows(List<Row> out) {
        out.add(toggle(LauncherSettings.REMOTE, "Phone remote",
                "Open the address below in your phone's browser and pair with the PIN shown on the TV"));
        Row addr = new Row(INFO, "Address");
        String url = RemoteServer.lanUrl(ctx);
        addr.value = () -> url.replace("http://", "");
        out.add(addr);
        int paired = new RemoteDevices(ctx).count();
        Row f = new Row(paired > 0 ? ACTION : INFO, confirmForget ? "Press OK again to forget them" : "Forget all paired phones");
        f.sub = paired == 0 ? "No phones paired" : paired == 1 ? "1 phone paired" : paired + " phones paired";
        f.value = () -> confirmForget ? "Confirm" : "";
        f.action = () -> {
            if (!confirmForget) {
                confirmForget = true;
                return;
            }
            confirmForget = false;
        confirmPhone = null;
            RemoteServer.forgetAll(ctx);
        };
        out.add(f);
        // Each paired phone, newest first: its name (typed when pairing, or Rename on the phone)
        // and when it was last used. OK twice forgets that one; it has to pair again.
        try {
            org.json.JSONArray phones = new RemoteDevices(ctx).json(null);
            for (int i = 0; i < phones.length(); i++) {
                org.json.JSONObject d = phones.getJSONObject(i);
                String id = d.optString("id"), name = d.optString("name", "Phone");
                boolean asking = id.equals(confirmPhone);
                Row r = new Row(ACTION, asking ? "Press OK again to forget " + name : name);
                r.id = id;
                r.value = () -> asking ? "Confirm" : "Forget";
                r.action = () -> {
                    if (!id.equals(confirmPhone)) {
                        confirmPhone = id;
                        return;
                    }
                    confirmPhone = null;
                    new RemoteDevices(ctx).remove(id);
                };
                long seen = d.optLong("lastSeen"), now = System.currentTimeMillis();
                r.sub = seen <= 0 ? "Paired"
                        : now - seen < 60_000 ? "Last used just now"
                        : "Last used " + android.text.format.DateUtils.getRelativeTimeSpanString(
                                seen, now, android.text.format.DateUtils.MINUTE_IN_MILLIS);
                out.add(r);
            }
        } catch (org.json.JSONException ignored) {
        }
    }

    private void hiddenRows(List<Row> out) {
        PackageManager pm = ctx.getPackageManager();
        List<String> pkgs = new ArrayList<>(RunningApps.hidden(ctx));
        pkgs.sort(String::compareTo);
        for (String pkg : pkgs) {
            Row r = new Row(ACTION, label(pm, pkg));
            r.sub = "Hidden from the running-apps list";
            r.value = () -> "Show again";
            r.action = () -> RunningApps.setHidden(ctx, pkg, false);
            out.add(r);
        }
        if (out.isEmpty()) {
            Row r = new Row(INFO, "No hidden apps");
            r.sub = "Hold OK on an app in the running-apps screen (Up from the top bar) to hide it";
            out.add(r);
        }
    }

    private void aboutRows(List<Row> out) {
        String version = "?", installed = "?";
        try {
            PackageInfo pi = ctx.getPackageManager().getPackageInfo(ctx.getPackageName(), 0);
            long code = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P ? pi.getLongVersionCode() : pi.versionCode;
            version = pi.versionName + " (" + code + ")";
            installed = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(new Date(pi.lastUpdateTime));
        } catch (PackageManager.NameNotFoundException ignored) {
        }
        out.add(info("Version", version));
        out.add(info("Installed", installed));
        out.add(info("APK size", (new File(ctx.getPackageCodePath()).length() / 1024) + " KB"));
        Row tv = new Row(ACTION, "TV settings");
        tv.sub = "Android Settings: network, display, sound, apps";
        tv.action = host::openTvSettings;
        out.add(tv);
    }

    // ---------------------------------------------------------------- pickers

    private void openZonePicker() {
        String zone = LauncherSettings.zone(ctx);
        List<Row> items = new ArrayList<>();
        int focus = 0;
        for (String e : ctx.getResources().getStringArray(R.array.prayer_zones)) {
            String[] z = e.split("\\|", 3); // state | code | areas
            Row r = new Row(CHECK, z[2]);
            r.sub = z[0] + " · " + z[1];
            r.checked = z[1].equals(zone);
            if (r.checked) focus = items.size();
            r.action = () -> {
                LauncherSettings.setZone(ctx, z[1]);
                closePicker();
            };
            items.add(r);
        }
        openPicker("Zone", items, focus);
    }

    private static final String WALLPAPER_TITLE = "Wallpaper";
    private final Map<String, Bitmap> thumbs = new HashMap<>();
    private final Set<String> failed = new HashSet<>();
    private boolean fetchingWallpapers;

    /**
     * None, the built-ins (thumbnail, credit) and the pushed picture if any. Opening it downloads
     * the built-ins not cached yet, one by one; rows update as each lands. Moving the focus
     * previews a wallpaper behind Settings; OK sets it; Back without OK restores the saved one.
     */
    private void openWallpaperPicker() {
        List<Row> items = wallpaperRows();
        int focus = 0;
        for (int i = 0; i < items.size(); i++) if (items.get(i).checked) focus = i;
        openPicker(WALLPAPER_TITLE, items, focus);
        fetchWallpapers();
    }

    private List<Row> wallpaperRows() {
        String current = LauncherSettings.wallpaper(ctx);
        List<Row> items = new ArrayList<>();
        Row none = new Row(CHECK, "None");
        none.sub = "Plain dark background";
        none.thumbSlot = true;
        none.checked = Wallpapers.NONE.equals(current);
        none.id = Wallpapers.NONE;
        none.action = () -> pickWallpaper(Wallpapers.NONE);
        items.add(none);
        File bingFile = Bing.shown(ctx);
        Row bing = new Row(CHECK, "Bing wallpaper of the day");
        String cap = Bing.caption(ctx);
        bing.sub = bingFile != null && !cap.isEmpty() ? cap + " · bing.com"
                : "A new photo every day · bing.com";
        bing.thumbSlot = true;
        bing.thumb = bingFile != null ? thumbs.get(thumbKey(bingFile)) : null;
        bing.checked = Wallpapers.BING.equals(current);
        bing.id = Wallpapers.BING;
        bing.action = () -> pickWallpaper(Wallpapers.BING);
        items.add(bing);
        for (Wallpapers.Photo p : Wallpapers.BUILT_IN) {
            Row r = new Row(CHECK, p.name);
            boolean cached = Wallpapers.isCached(ctx, p.id);
            r.sub = cached ? "Photo: " + p.credit + " · Wikimedia Commons"
                    : failed.contains(p.id) ? "Couldn't download. Check the internet connection" : "Downloading…";
            r.thumbSlot = true;
            r.thumb = thumbs.get(p.id);
            r.checked = p.id.equals(current);
            r.id = p.id;
            r.action = () -> pickWallpaper(p.id);
            items.add(r);
        }
        List<File> mine = Wallpapers.userPictures();
        for (File f : mine) {
            String id = Wallpapers.FILE + f.getName();
            Row r = new Row(CHECK, baseName(f.getName()));
            r.sub = "Wallpapers folder · " + String.format(Locale.US, "%.1f MB", f.length() / 1048576.0);
            r.thumbSlot = true;
            r.thumb = thumbs.get(thumbKey(f));
            r.checked = id.equals(current);
            r.id = id;
            r.action = () -> pickWallpaper(id);
            items.add(r);
        }
        if (mine.isEmpty()) {
            Row r = new Row(INFO, "Add your own");
            r.sub = "On the phone remote: Files → Wallpapers → upload a picture. It shows up here.";
            items.add(r);
        }
        File custom = Wallpapers.custom(ctx);
        if (custom != null) {
            Row r = new Row(CHECK, "Your picture");
            r.sub = "Android/data/" + ctx.getPackageName() + "/files/" + custom.getName();
            r.thumbSlot = true;
            r.thumb = thumbs.get(Wallpapers.CUSTOM);
            r.checked = Wallpapers.CUSTOM.equals(current);
            r.id = Wallpapers.CUSTOM;
            r.action = () -> pickWallpaper(Wallpapers.CUSTOM);
            items.add(r);
        }
        return items;
    }

    /** OK: the previewed wallpaper becomes the saved one. */
    private void pickWallpaper(String id) {
        LauncherSettings.setWallpaper(ctx, id);
        host.previewWallpaper(null);
        refreshWallpaperRows();
    }

    private boolean inWallpaperList() {
        return picker != null && WALLPAPER_TITLE.equals(pickerTitle);
    }

    /** Rebuilds the wallpaper list in place if it's still open (download finished, choice made). */
    private void refreshWallpaperRows() {
        if (picker == null || !WALLPAPER_TITLE.equals(pickerTitle)) return;
        picker = wallpaperRows();
        renderRows();
    }

    /** Downloads the built-ins not cached yet and makes thumbnails, on a background thread. */
    private void fetchWallpapers() {
        if (fetchingWallpapers) return;
        fetchingWallpapers = true;
        failed.clear();
        new Thread(() -> {
            if (Bing.due(ctx)) Bing.refresh(ctx);
            File bf = Bing.shown(ctx);
            if (bf != null) {
                String key = thumbKey(bf);
                if (!thumbs.containsKey(key)) {
                    Bitmap t = thumbnail(bf);
                    main.post(() -> {
                        if (t != null) thumbs.put(key, t);
                        refreshWallpaperRows();
                    });
                }
            }
            List<String> ids = new ArrayList<>();
            for (Wallpapers.Photo p : Wallpapers.BUILT_IN) ids.add(p.id);
            for (File f : Wallpapers.userPictures()) {
                String key = thumbKey(f);
                if (thumbs.containsKey(key)) continue;
                Bitmap t = thumbnail(f);
                main.post(() -> {
                    if (t != null) thumbs.put(key, t);
                    refreshWallpaperRows();
                });
            }
            if (Wallpapers.custom(ctx) != null) ids.add(Wallpapers.CUSTOM);
            for (String id : ids) {
                boolean ok = Wallpapers.CUSTOM.equals(id) || Wallpapers.fetch(ctx, id);
                File f = ok ? Wallpapers.file(ctx, id) : null;
                Bitmap t = f != null && !thumbs.containsKey(id) ? thumbnail(f) : null;
                main.post(() -> {
                    if (!ok) failed.add(id);
                    if (t != null) thumbs.put(id, t);
                    refreshWallpaperRows();
                });
            }
            main.post(() -> fetchingWallpapers = false);
        }, "wallpaper-thumbs").start();
    }

    /** Downscaled to about 240 px wide: enough for the list's 96 dp preview. */
    private static Bitmap thumbnail(File f) {
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(f.getPath(), o);
        o.inSampleSize = 1;
        while (o.outWidth / (o.inSampleSize * 2) >= 240) o.inSampleSize *= 2;
        o.inJustDecodeBounds = false;
        o.inPreferredConfig = Bitmap.Config.RGB_565;
        return BitmapFactory.decodeFile(f.getPath(), o);
    }

    /** A picture replaced under the same name gets a new thumbnail. */
    private static String thumbKey(File f) {
        return Wallpapers.FILE + f.getName() + "@" + f.lastModified();
    }

    static String baseName(String file) {
        int dot = file.lastIndexOf('.');
        return dot > 0 ? file.substring(0, dot) : file;
    }

    private void openUsagePicker() {
        Set<String> excluded = LauncherSettings.usageExcluded(ctx);
        int from = row;
        worker.execute(() -> {
            List<AppEntry> apps = AppRepository.load(ctx);
            main.post(() -> {
                if (col != 1 || cat != USAGE || picker != null || row != from) return; // moved on meanwhile
                List<Row> items = new ArrayList<>();
                List<String> seen = new ArrayList<>();
                for (AppEntry a : apps) {
                    if (seen.contains(a.pkg)) continue;
                    seen.add(a.pkg);
                    Row r = new Row(CHECK, a.label);
                    r.sub = a.pkg.equals(LIVE_TV) ? "Live TV: HDMI, AV and antenna" : null;
                    r.checked = excluded.contains(a.pkg);
                    r.action = () -> {
                        r.checked = !r.checked;
                        LauncherSettings.setUsageExcluded(ctx, a.pkg, r.checked);
                    };
                    items.add(r);
                }
                // Apps already left out first (Live TV), then A–Z.
                items.sort((x, y) -> x.checked != y.checked ? (x.checked ? -1 : 1) : x.label.compareToIgnoreCase(y.label));
                openPicker("Leave out", items, 0);
            });
        });
    }

    // ---------------------------------------------------------------- views

    private void render() {
        if (picker == null) rows = rowsFor(cat);
        for (int i = 0; i < catViews.size(); i++) {
            // The open category stays marked while its rows have focus.
            catViews.get(i).setBackgroundResource(i == cat && col == 1 ? R.drawable.row_selected : 0);
        }
        View c = catViews.get(cat);
        catScroll.post(() -> catScroll.smoothScrollTo(0, Math.max(0, c.getTop() - px(54))));
        crumb.setText("/  " + CATS[cat] + (picker != null ? "  /  " + pickerTitle : ""));
        paneTitle.setText(picker != null ? pickerTitle : CATS[cat]);
        paneSub.setText(inWallpaperList() ? "Move to preview  ·  OK to set"
                : picker != null ? (picker.size() + " items") : CAT_SUBS[cat]);
        renderRows();
    }

    private void renderRows() {
        rowList.removeAllViews();
        rowViews.clear();
        List<Row> list = current();
        for (int i = 0; i < list.size(); i++) {
            Row r = list.get(i);
            if (r.heading != null) rowList.addView(heading(r.heading), new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            View v = rowView(r);
            rowList.addView(v, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT));
            rowViews.add(v);
        }
        if (col == 0) scroll.scrollTo(0, 0);
        else scrollToFocus();
        host.onFocusMoved();
    }

    private void scrollToFocus() {
        View f = row < rowViews.size() ? rowViews.get(row) : null;
        if (f != null) scroll.post(() -> scroll.smoothScrollTo(0, Math.max(0, f.getTop() - px(70))));
    }

    /** A thin line with a small title, between groups of rows (Row.heading). */
    private View heading(String title) {
        LinearLayout h = new LinearLayout(ctx);
        h.setOrientation(LinearLayout.VERTICAL);
        h.setPadding(px(16), px(14), px(14), px(4));
        View line = new View(ctx);
        line.setBackgroundColor(0x26FFFFFF);
        h.addView(line, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, px(1))));
        TextView t = text(12, 500, DIM);
        t.setText(title.toUpperCase(Locale.ROOT));
        t.setLetterSpacing(0.08f);
        LinearLayout.LayoutParams tl = wrap();
        tl.topMargin = px(12);
        h.addView(t, tl);
        return h;
    }

    private View rowView(Row r) {
        LinearLayout v = new LinearLayout(ctx);
        v.setGravity(Gravity.CENTER_VERTICAL);
        v.setMinimumHeight(px(58));
        v.setPadding(px(16), px(9), px(14), px(9));
        if (r.thumbSlot) {
            ImageView t = new ImageView(ctx);
            t.setBackgroundResource(R.drawable.tile_bg);
            t.setClipToOutline(true);
            t.setScaleType(ImageView.ScaleType.CENTER_CROP);
            if (r.thumb != null) t.setImageBitmap(r.thumb);
            LinearLayout.LayoutParams tl = new LinearLayout.LayoutParams(px(96), px(54));
            tl.setMarginEnd(px(16));
            v.addView(t, tl);
        }

        LinearLayout texts = new LinearLayout(ctx);
        texts.setOrientation(LinearLayout.VERTICAL);
        TextView label = text(16, 500, TEXT);
        label.setText(r.label);
        texts.addView(label);
        if (!TextUtils.isEmpty(r.sub)) {
            TextView sub = text(12, 400, DIM);
            sub.setSingleLine(false);
            sub.setMaxLines(2);
            sub.setText(r.sub);
            LinearLayout.LayoutParams slp = wrap();
            slp.topMargin = px(3);
            texts.addView(sub, slp);
        }
        v.addView(texts, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        String value = r.value != null ? r.value.get() : null;
        switch (r.type) {
            case TOGGLE:
                boolean isOn = r.on != null ? r.on.getAsBoolean() : LauncherSettings.flag(ctx, r.key);
                v.addView(switchView(isOn), margin(new LinearLayout.LayoutParams(px(44), px(26))));
                break;
            case CHOICE:
            case PICKER: {
                TextView pill = text(14, 500, TEXT);
                pill.setText(r.type == CHOICE ? r.names[indexOf(r.values, LauncherSettings.choice(ctx, r.key))] : value);
                pill.setBackgroundResource(R.drawable.pill_bg);
                pill.setPadding(px(12), px(5), px(12), px(5));
                v.addView(pill, margin(wrap()));
                v.addView(icon(R.drawable.ic_chevron, DIM, 18), new LinearLayout.LayoutParams(px(20), px(20)));
                break;
            }
            case ACTION:
                if (!TextUtils.isEmpty(value)) {
                    boolean asking = (r.id == null && confirmForget) || (r.id != null && r.id.equals(confirmPhone));
                    TextView t = text(14, 500, asking ? AMBER : DIM);
                    t.setText(value);
                    v.addView(t, margin(wrap()));
                }
                v.addView(icon(R.drawable.ic_chevron, DIM, 18), new LinearLayout.LayoutParams(px(20), px(20)));
                break;
            case INFO:
                if (!TextUtils.isEmpty(value)) {
                    TextView t = text(14, 500, DIM);
                    t.setText(value);
                    v.addView(t, margin(wrap()));
                }
                break;
            case CHECK: {
                ImageView check = icon(R.drawable.ic_check, GREEN, 20);
                check.setVisibility(r.checked ? View.VISIBLE : View.INVISIBLE);
                v.addView(check, margin(new LinearLayout.LayoutParams(px(22), px(22))));
                break;
            }
        }
        return v;
    }

    private View switchView(boolean on) {
        FrameLayout track = new FrameLayout(ctx);
        track.setBackgroundResource(on ? R.drawable.switch_track_on : R.drawable.switch_track);
        View knob = new View(ctx);
        knob.setBackgroundResource(R.drawable.switch_knob);
        FrameLayout.LayoutParams k = new FrameLayout.LayoutParams(px(20), px(20),
                Gravity.CENTER_VERTICAL | (on ? Gravity.END : Gravity.START));
        k.setMargins(px(3), 0, px(3), 0);
        track.addView(knob, k);
        return track;
    }

    // ---------------------------------------------------------------- helpers

    private Row toggle(String key, String label, String sub) {
        Row r = new Row(TOGGLE, label);
        r.key = key;
        r.sub = sub;
        return r;
    }

    private Row choice(String key, String label, String sub, int[] values, String[] names) {
        Row r = new Row(CHOICE, label);
        r.key = key;
        r.sub = sub;
        r.values = values;
        r.names = names;
        return r;
    }

    private Row info(String label, String value) {
        Row r = new Row(INFO, label);
        r.value = () -> value;
        return r;
    }

    private static int indexOf(int[] values, int v) {
        for (int i = 0; i < values.length; i++) if (values[i] == v) return i;
        return 0;
    }

    private static String label(PackageManager pm, String pkg) {
        try {
            return pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString();
        } catch (PackageManager.NameNotFoundException e) {
            return pkg;
        }
    }

    private LinearLayout.LayoutParams margin(LinearLayout.LayoutParams lp) {
        lp.setMarginStart(px(12));
        return lp;
    }

    private ImageView icon(int res, int color, int sizeDp) {
        ImageView i = new ImageView(ctx);
        i.setImageResource(res);
        i.setImageTintList(ColorStateList.valueOf(color));
        i.setScaleType(ImageView.ScaleType.FIT_CENTER);
        i.setMaxWidth(px(sizeDp));
        i.setMaxHeight(px(sizeDp));
        i.setAdjustViewBounds(true);
        return i;
    }

    private int px(float dps) {
        return Math.round(dps * dp);
    }

    private LinearLayout.LayoutParams wrap() {
        return new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private TextView text(float sp, int weight, int color) {
        TextView t = new TextView(ctx);
        t.setTextSize(sp);
        t.setTextColor(color);
        t.setTypeface(Fonts.outfit(ctx, weight));
        t.setSingleLine(true);
        t.setEllipsize(TextUtils.TruncateAt.END);
        t.setIncludeFontPadding(false);
        return t;
    }
}
