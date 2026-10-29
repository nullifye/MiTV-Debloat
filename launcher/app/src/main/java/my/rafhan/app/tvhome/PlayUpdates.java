package my.rafhan.app.tvhome;

import android.Manifest;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Build;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.time.Instant;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Apps with an update waiting on the Play Store. On Android TV the Play Store rarely posts its
 * "updates available" notification, but every update check logs each pending app:
 *
 *   Finsky: UCtl: For package com.netflix.ninja, first update discovery timestamp is set to 2026-10-28T03:21:51Z
 *
 * so the bell reads the Play Store's log lines (logcat -s Finsky). The list is kept in prefs,
 * because the log only holds a day or two: an app leaves it once it's updated (installed after
 * the update was found), disabled or removed, or when the Play Store stops logging it for a few
 * days. Reading the log needs READ_LOGS (granted by the setup app and build-and-install.sh); on
 * Android 13+ Android asks the person every time an app reads the log, so there it's read over
 * the launcher's own ADB connection (AdbKeys) instead, when USB debugging is on. That's also the
 * fallback when READ_LOGS was granted while the launcher was running: Android only applies it
 * from the next start (it's a group id given at process start).
 */
final class PlayUpdates {
    private static final Pattern FOUND = Pattern.compile(
            "UCtl: For package (\\S+), first update discovery timestamp is set to (\\S+)");
    private static final long FORGET_AFTER_MS = 3 * 24 * 60 * 60_000L; // not logged for 3 days
    private static final String PREFS = "play_updates";

    private PlayUpdates() {}

    /** Package -> when the Play Store first found its update (ms), from the log text. */
    static Map<String, Long> parse(String log) {
        Map<String, Long> out = new LinkedHashMap<>();
        Matcher m = FOUND.matcher(log);
        while (m.find()) {
            try {
                out.put(m.group(1), Instant.parse(m.group(2)).toEpochMilli());
            } catch (RuntimeException ignored) { // a timestamp in another format: skip it
            }
        }
        return out;
    }

    /** A pending update as kept: when it was found, and when the log last mentioned it. */
    static final class Pending {
        final long found, seen;

        Pending(long found, long seen) {
            this.found = found;
            this.seen = seen;
        }
    }

    /**
     * Adds what the log shows ({@code logged}, seen {@code now}) to {@code kept}, then drops
     * apps that no longer wait: {@code updatedAt} says when each is installed (-1: missing or
     * disabled), and an install after the update was found means it's done.
     */
    static Map<String, Pending> merge(Map<String, Pending> kept, Map<String, Long> logged, long now,
                                      Map<String, Long> updatedAt) {
        Map<String, Pending> out = new LinkedHashMap<>(kept);
        for (Map.Entry<String, Long> e : logged.entrySet()) out.put(e.getKey(), new Pending(e.getValue(), now));
        out.entrySet().removeIf(e -> {
            Long installed = updatedAt.get(e.getKey());
            return installed == null || installed < 0 || installed >= e.getValue().found
                    || now - e.getValue().seen > FORGET_AFTER_MS;
        });
        return out;
    }

    /** Reads the log, updates the kept list and returns it (package -> pending). Blocks. */
    static synchronized Map<String, Pending> refresh(Context ctx) {
        Map<String, Pending> kept = load(ctx);
        String log = readLog(ctx);
        Map<String, Long> logged = log == null ? new HashMap<>() : parse(log);
        Map<String, Long> updatedAt = new HashMap<>();
        PackageManager pm = ctx.getPackageManager();
        for (String pkg : union(kept.keySet(), logged.keySet())) {
            try {
                PackageInfo pi = pm.getPackageInfo(pkg, 0);
                ApplicationInfo ai = pi.applicationInfo;
                updatedAt.put(pkg, ai != null && ai.enabled ? pi.lastUpdateTime : -1L);
            } catch (PackageManager.NameNotFoundException e) {
                updatedAt.put(pkg, -1L);
            }
        }
        Map<String, Pending> now = merge(kept, logged, System.currentTimeMillis(), updatedAt);
        save(ctx, now);
        return now;
    }

    private static java.util.Set<String> union(java.util.Set<String> a, java.util.Set<String> b) {
        java.util.Set<String> u = new java.util.LinkedHashSet<>(a);
        u.addAll(b);
        return u;
    }

    /** The Play Store's log lines, or null when they can't be read here. */
    private static String readLog(Context ctx) {
        String own = readOwnLog(ctx);
        if (own != null && own.contains("UCtl:")) return own;
        String shell = AppManager.run(ctx, "logcat -d -v brief -s Finsky:I"); // null without USB debugging
        return shell != null ? shell : own;
    }

    /** With READ_LOGS, below Android 13: the app's own logcat. */
    private static String readOwnLog(Context ctx) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU
                && ctx.checkSelfPermission(Manifest.permission.READ_LOGS) == PackageManager.PERMISSION_GRANTED) {
            try {
                Process p = new ProcessBuilder("logcat", "-d", "-v", "brief", "-s", "Finsky:I")
                        .redirectErrorStream(true).start();
                StringBuilder sb = new StringBuilder();
                try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
                    for (String line; (line = r.readLine()) != null; ) {
                        if (line.contains("UCtl:")) sb.append(line).append('\n');
                    }
                }
                p.destroy();
                return sb.toString();
            } catch (Exception e) {
                return null;
            }
        }
        return null;
    }

    private static Map<String, Pending> load(Context ctx) {
        Map<String, Pending> out = new LinkedHashMap<>();
        for (Map.Entry<String, ?> e : prefs(ctx).getAll().entrySet()) {
            String[] v = String.valueOf(e.getValue()).split(";");
            try {
                out.put(e.getKey(), new Pending(Long.parseLong(v[0]), Long.parseLong(v[1])));
            } catch (RuntimeException ignored) {
            }
        }
        return out;
    }

    private static void save(Context ctx, Map<String, Pending> list) {
        SharedPreferences.Editor ed = prefs(ctx).edit().clear();
        for (Map.Entry<String, Pending> e : list.entrySet()) {
            ed.putString(e.getKey(), e.getValue().found + ";" + e.getValue().seen);
        }
        ed.apply();
    }

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
