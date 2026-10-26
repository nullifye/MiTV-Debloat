package my.rafhan.app.tvhome;

import android.annotation.SuppressLint;
import android.app.ActivityManager;
import android.bluetooth.BluetoothDevice;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.media.tv.TvContract;
import android.media.tv.TvInputInfo;
import android.media.tv.TvInputManager;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.provider.Settings;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Feeds the bell (Alerts), modelled on Matinee's, from RemoteService so it runs while any app is
 * on screen:
 *  - TV Home updates: a newer release on GitHub (UpdateCheck), at start and every 6 h; opening it
 *    downloads and installs it (UpdateActivity). A banner shows once per version;
 *  - Play Store updates: the Play Store's own "updates available" notifications, read from
 *    `dumpsys notification --noredact` (this TV refuses notification-listener access; the
 *    notification service needs DUMP and PACKAGE_USAGE_STATS, both granted by
 *    build-and-install.sh), at start and every 6 h;
 *  - new HDMI-CEC devices: TvInputManager child inputs under an HDMI port;
 *  - Bluetooth devices connecting / disconnecting (the TV remote and quick reconnects ignored);
 *  - memory use above the level set in Settings (85 % by default; re-arms 5 points lower).
 * Each source can be switched off in Settings → Notifications.
 * Device and memory alerts also show a short banner over any app.
 */
final class AlertSources {
    private static final long UPDATE_EVERY_MS = 6 * 60 * 60_000L, UPDATE_FIRST_MS = 60_000;
    private static final long PLAY_EVERY_MS = 6 * 60 * 60_000L, MEMORY_EVERY_MS = 60_000, BT_SETTLE_MS = 2 * 60_000;
    private static final float MEMORY_REARM_GAP = 0.05f; // re-arm 5 points under the level
    private static final Pattern REMOTE = Pattern.compile("(?i)\\b(rc|remote)\\b");

    private final Context ctx;
    private final NetBanner banner;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Alerts alerts = Alerts.get();
    private final TvInputManager tim;

    // HDMI-CEC: devices seen this session (first scan is the baseline, like Matinee).
    private Set<String> announcedCec;
    // Bluetooth: last disconnect time per address, and pending "disconnected" alerts.
    private final Map<String, Long> btLastDown = new HashMap<>();
    private final Map<String, Runnable> btPendingDown = new HashMap<>();
    private boolean memoryArmed = true;

    AlertSources(Context ctx, NetBanner banner) {
        this.ctx = ctx;
        this.banner = banner;
        tim = ctx.getSystemService(TvInputManager.class);
    }

    /** Play updates switched on/off in Settings: re-read (or clear) right away. */
    private final LauncherSettings.Listener settingsListener = this::onSettingChanged;

    private void onSettingChanged(String key) {
        if (LauncherSettings.ALERT_UPDATE.equals(key)) {
            main.removeCallbacks(updateCheck);
            main.post(updateCheck);
        }
        if (!LauncherSettings.ALERT_PLAY.equals(key)) return;
        main.removeCallbacks(playCheck);
        main.post(playCheck);
    }

    void start() {
        LauncherSettings.addListener(settingsListener);
        main.post(playCheck);
        // A minute in: the network is usually up by then after a boot.
        main.postDelayed(updateCheck, UPDATE_FIRST_MS);
        main.postDelayed(memoryCheck, MEMORY_EVERY_MS);
        if (tim != null) {
            scanCec();
            tim.registerCallback(tvCallback, main);
        }
        IntentFilter f = new IntentFilter(BluetoothDevice.ACTION_ACL_CONNECTED);
        f.addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED);
        ctx.registerReceiver(btReceiver, f);
    }

    void stop() {
        LauncherSettings.removeListener(settingsListener);
        main.removeCallbacksAndMessages(null);
        if (tim != null) tim.unregisterCallback(tvCallback);
        try {
            ctx.unregisterReceiver(btReceiver);
        } catch (RuntimeException ignored) {
        }
    }

    // ---------------------------------------------------------------- TV Home updates

    private final Runnable updateCheck = new Runnable() {
        @Override
        public void run() {
            boolean on = LauncherSettings.flag(ctx, LauncherSettings.ALERT_UPDATE);
            Thread t = new Thread(() -> {
                UpdateCheck.Release r = on ? UpdateCheck.newer(ctx) : null;
                List<Alerts.Alert> now = new ArrayList<>();
                if (r != null) {
                    now.add(new Alerts.Alert("update:" + r.version, Alerts.UPDATE,
                            "TV Home " + r.version + " is available", "Open to update (your settings are kept)",
                            System.currentTimeMillis(), UpdateActivity.intent(ctx, r)));
                    SharedPreferences p = ctx.getSharedPreferences("alerts", Context.MODE_PRIVATE);
                    if (!r.version.equals(p.getString("update_banner", null))) {
                        p.edit().putString("update_banner", r.version).apply();
                        main.post(() -> banner.showMessage(R.drawable.ic_update, NetState.GREEN,
                                "TV Home " + r.version + " is available · open the bell to update", 6000));
                    }
                }
                alerts.sync(Alerts.UPDATE, now);
            }, "alerts-update");
            t.setDaemon(true);
            t.start();
            main.postDelayed(this, UPDATE_EVERY_MS);
        }
    };

    // ---------------------------------------------------------------- Play Store updates

    private static final Pattern RECORD = Pattern.compile(
            "NotificationRecord\\(0x[0-9a-f]+: pkg=(\\S+) .*?key=(\\S+?)(?:appImportanceLocked|:|\\s).*?channel=([^ ]+)");

    private final Runnable playCheck = new Runnable() {
        @Override
        public void run() {
            // Switched off in Settings: clear any shown update alerts and stop reading.
            boolean on = LauncherSettings.flag(ctx, LauncherSettings.ALERT_PLAY);
            Thread t = new Thread(() -> alerts.sync(Alerts.PLAY,
                    on ? readPlayUpdates() : new ArrayList<>()), "alerts-play");
            t.setDaemon(true);
            t.start();
            main.postDelayed(this, PLAY_EVERY_MS);
        }
    };

    private List<Alerts.Alert> readPlayUpdates() {
        List<Alerts.Alert> out = new ArrayList<>();
        try {
            Process p = new ProcessBuilder("dumpsys", "notification", "--noredact").redirectErrorStream(true).start();
            try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
                String key = null, title = null, text = null;
                long when = 0;
                boolean match = false;
                for (String line; (line = r.readLine()) != null; ) {
                    String t = line.trim();
                    if (t.startsWith("NotificationRecord(")) {
                        if (match && title != null) out.add(playAlert(key, title, text, when));
                        Matcher m = RECORD.matcher(t);
                        match = m.find() && m.group(1).equals("com.android.vending")
                                && (m.group(3).equals("updates-available") || m.group(3).equals("UPDATES"));
                        key = match ? m.group(2) : null;
                        title = text = null;
                        when = 0;
                    } else if (match && t.startsWith("android.title=")) {
                        title = value(t);
                    } else if (match && t.startsWith("android.text=")) {
                        text = value(t);
                    } else if (match && when == 0 && t.startsWith("when=")) {
                        try {
                            when = Long.parseLong(t.substring(5).replaceAll("\\D.*", ""));
                        } catch (NumberFormatException ignored) {
                        }
                    }
                }
                if (match && title != null) out.add(playAlert(key, title, text, when));
            }
            p.destroy();
        } catch (Exception ignored) {
        }
        return out;
    }

    /** "android.title=String (Updates available)" -> "Updates available". */
    private static String value(String line) {
        int open = line.indexOf('('), close = line.lastIndexOf(')');
        return open >= 0 && close > open ? line.substring(open + 1, close).trim() : null;
    }

    private Alerts.Alert playAlert(String key, String title, String text, long when) {
        Intent open = ctx.getPackageManager().getLeanbackLaunchIntentForPackage("com.android.vending");
        if (open == null) open = ctx.getPackageManager().getLaunchIntentForPackage("com.android.vending");
        long time = when > 0 ? when : System.currentTimeMillis();
        return new Alerts.Alert("play:" + key + "@" + title, Alerts.PLAY, title, text, time, open);
    }

    // ---------------------------------------------------------------- HDMI-CEC devices

    private final TvInputManager.TvInputCallback tvCallback = new TvInputManager.TvInputCallback() {
        @Override
        public void onInputAdded(String inputId) {
            scanCec();
        }

        @Override
        public void onInputRemoved(String inputId) {
            scanCec();
        }

        @Override
        public void onInputUpdated(String inputId) {
            scanCec();
        }

        @Override
        public void onInputStateChanged(String inputId, int state) {
            scanCec();
        }
    };

    /** CEC devices = HDMI inputs whose parent is an HDMI port; alert the ones not seen before. */
    private void scanCec() {
        Map<String, TvInputInfo> byId = new HashMap<>();
        List<TvInputInfo> list = tim.getTvInputList();
        for (TvInputInfo i : list) byId.put(i.getId(), i);
        Set<String> present = new HashSet<>();
        Map<String, String[]> details = new HashMap<>();
        for (TvInputInfo i : list) {
            if (i.getType() != TvInputInfo.TYPE_HDMI || i.getParentId() == null) continue;
            TvInputInfo port = byId.get(i.getParentId());
            if (port == null) continue;
            int state;
            try {
                state = tim.getInputState(i.getId());
            } catch (RuntimeException e) {
                continue;
            }
            if (state != TvInputManager.INPUT_STATE_CONNECTED) continue;
            String name = String.valueOf(i.loadLabel(ctx)), portName = String.valueOf(port.loadLabel(ctx));
            String k = port.getId() + "|" + name;
            present.add(k);
            details.put(k, new String[] {name, portName, port.getId()});
        }
        if (announcedCec == null) {
            announcedCec = present;
            return;
        }
        boolean on = LauncherSettings.flag(ctx, LauncherSettings.ALERT_HDMI);
        for (String k : present) {
            if (!on || announcedCec.contains(k)) continue;
            String[] d = details.get(k);
            Intent switchTo = new Intent(Intent.ACTION_VIEW).setDataAndType(
                    TvContract.buildChannelUriForPassthroughInput(d[2]), "vnd.android.cursor.item/channel");
            long now = System.currentTimeMillis();
            alerts.add(new Alerts.Alert("hdmi:" + k + "@" + now, Alerts.HDMI, "New device on " + d[1],
                    d[0], now, switchTo));
            banner.showMessage(R.drawable.ic_input, NetState.GREEN, "New device on " + d[1] + " · " + d[0], 5000);
        }
        announcedCec.addAll(present);
    }

    // ---------------------------------------------------------------- Bluetooth

    private final BroadcastReceiver btReceiver = new BroadcastReceiver() {
        // getName() needs BLUETOOTH_CONNECT on Android 12+. The launcher doesn't hold it, so the
        // call throws there and the catch falls back to the address, which is all we show then.
        @SuppressLint("MissingPermission")
        @Override
        public void onReceive(Context c, Intent intent) {
            BluetoothDevice dev = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
            if (dev == null) return;
            String name;
            try {
                name = dev.getName();
            } catch (SecurityException e) {
                name = null;
            }
            if (name == null || name.isEmpty()) name = dev.getAddress();
            // The TV remote sleeps and reconnects all day: not news.
            if (REMOTE.matcher(name).find()) return;
            String addr = dev.getAddress(), shown = name;
            long now = System.currentTimeMillis();
            if (BluetoothDevice.ACTION_ACL_CONNECTED.equals(intent.getAction())) {
                Runnable pending = btPendingDown.remove(addr);
                if (pending != null) {
                    main.removeCallbacks(pending); // came back quickly: neither event is news
                    return;
                }
                Long down = btLastDown.get(addr);
                if (down != null && now - down < BT_SETTLE_MS) return;
                btAlert("Bluetooth connected", shown, now);
            } else {
                btLastDown.put(addr, now);
                Runnable alertLater = () -> {
                    btPendingDown.remove(addr);
                    btAlert("Bluetooth disconnected", shown, System.currentTimeMillis());
                };
                btPendingDown.put(addr, alertLater);
                main.postDelayed(alertLater, BT_SETTLE_MS);
            }
        }
    };

    private void btAlert(String title, String name, long now) {
        if (!LauncherSettings.flag(ctx, LauncherSettings.ALERT_BT)) return;
        alerts.add(new Alerts.Alert("bt:" + title + ":" + name + "@" + now, Alerts.BLUETOOTH, title, name, now,
                new Intent(Settings.ACTION_BLUETOOTH_SETTINGS)));
        banner.showMessage(R.drawable.ic_bluetooth, title.endsWith("connected") && !title.contains("dis")
                ? NetState.GREEN : 0xFFB3B3B3, title + " · " + name, 4000);
    }

    // ---------------------------------------------------------------- memory

    private final Runnable memoryCheck = new Runnable() {
        @Override
        public void run() {
            PowerManager pm = ctx.getSystemService(PowerManager.class);
            if (pm == null || pm.isInteractive()) {
                ActivityManager am = ctx.getSystemService(ActivityManager.class);
                ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
                am.getMemoryInfo(mi);
                float used = mi.totalMem > 0 ? (mi.totalMem - mi.availMem) / (float) mi.totalMem : 0;
                float high = LauncherSettings.choice(ctx, LauncherSettings.MEMORY_LEVEL) / 100f;
                boolean on = LauncherSettings.flag(ctx, LauncherSettings.ALERT_MEMORY);
                if (on && memoryArmed && used >= high) {
                    memoryArmed = false;
                    int pct = Math.round(used * 100);
                    Intent clean = ctx.getPackageManager().getLeanbackLaunchIntentForPackage("com.xiaomi.mitv.tvmanager");
                    long now = System.currentTimeMillis();
                    alerts.add(new Alerts.Alert("mem@" + now, Alerts.MEMORY, "Memory is " + pct + "% full",
                            clean != null ? "Open TV Manager to free some" : "Close apps you're not using", now, clean));
                    banner.showMessage(R.drawable.ic_ram, 0xFFFFB340, "Memory is " + pct + "% full", 4000);
                } else if (!memoryArmed && used < high - MEMORY_REARM_GAP) {
                    memoryArmed = true;
                }
            }
            main.postDelayed(this, MEMORY_EVERY_MS);
        }
    };
}
