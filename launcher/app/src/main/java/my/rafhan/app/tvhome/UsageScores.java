package my.rafhan.app.tvhome;

import android.app.AppOpsManager;
import android.app.usage.UsageStats;
import android.app.usage.UsageStatsManager;
import android.content.Context;
import android.os.Process;

import org.json.JSONObject;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.TemporalAdjusters;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Per-app use, two ways:
 *  - this week (from the week's first day, Monday or Sunday as set in Settings, up to today),
 *    leaving out the apps excluded in Settings: the Most used card;
 *  - all time, every app: the grid order and the phone remote's app list.
 *
 * Per day, an app's time is the larger of its visible time and its foreground-service time, so
 * an app that keeps playing with the screen elsewhere (RadioStream) counts its playback. Visible,
 * not "foreground": Live TV showing HDMI is visible but barely counts as foreground. Service time
 * only counts on days the app was on screen: on this TV a group of always-on services (Live TV,
 * MiLink, overlays) log identical whole-day service times that aren't use.
 *
 * The launcher keeps its own copy of each day's times for the last 7 days (files/usage-history.json). This TV has no
 * clock battery: after a power cut it boots in 1974 until network time fixes the clock, and
 * Android's usage history is shifted out of the last-7-days window by the jump. The copy is only
 * written while the clock is sane, and each day counts the larger of the copy and Android's figure.
 *
 * All time = files/usage-alltime.json (each day's final figures, added when the day leaves the
 * 7-day copy) + the days still in the copy. It starts from zero: only use the launcher counted.
 */
final class UsageScores {
    private static final int KEEP_DAYS = 7; // today and the 6 days before: any week's first day is in it
    private static final int SANE_YEAR = 2025; // before this the clock hasn't been set yet

    private UsageScores() {}

    static boolean granted(Context ctx) {
        AppOpsManager ops = ctx.getSystemService(AppOpsManager.class);
        return ops.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(),
                ctx.getPackageName()) == AppOpsManager.MODE_ALLOWED;
    }

    /** Use this week (Most used card) and all time (grid order). */
    static final class Scores {
        static final Scores NONE = new Scores(Collections.emptyMap(), Collections.emptyMap());
        final Map<String, Long> week, allTime;

        Scores(Map<String, Long> week, Map<String, Long> allTime) {
            this.week = week;
            this.allTime = allTime;
        }
    }

    /** Package -> milliseconds in use this week, excluded apps left out. Empty without Usage access. */
    static Map<String, Long> week(Context ctx) {
        return read(ctx).week;
    }

    /** Package -> milliseconds in use since tracking began, every app. */
    static Map<String, Long> allTime(Context ctx) {
        return read(ctx).allTime;
    }

    static synchronized Scores read(Context ctx) {
        if (!granted(ctx)) return Scores.NONE;
        LocalDate today = LocalDate.now();
        if (today.getYear() < SANE_YEAR) return Scores.NONE; // clock not set yet
        LocalDate first = today.minusDays(KEEP_DAYS - 1);
        LocalDate weekStart = weekStart(ctx, today);

        // Android's figures, per calendar day.
        Map<LocalDate, Map<String, Long>> days = new HashMap<>();
        ZoneId zone = ZoneId.systemDefault();
        long begin = first.minusDays(1).atStartOfDay(zone).toInstant().toEpochMilli();
        List<UsageStats> stats = ctx.getSystemService(UsageStatsManager.class)
                .queryUsageStats(UsageStatsManager.INTERVAL_DAILY, begin, System.currentTimeMillis());
        if (stats != null) for (UsageStats u : stats) {
            long visible = u.getTotalTimeVisible();
            // Background playback only counts on days the app was also on screen: always-on
            // services (overlays, system helpers) otherwise rack up whole days of "use".
            long playback = visible > 0 ? u.getTotalTimeForegroundServiceUsed() : 0;
            long time = Math.max(visible, playback);
            if (time <= 0) continue;
            long mid = u.getFirstTimeStamp() / 2 + u.getLastTimeStamp() / 2;
            LocalDate day = Instant.ofEpochMilli(mid).atZone(zone).toLocalDate();
            if (day.isBefore(first) || day.isAfter(today)) continue;
            days.computeIfAbsent(day, d -> new HashMap<>()).merge(u.getPackageName(), time, Long::sum);
        }

        // Merge with the launcher's copy: per day and app, the larger figure wins. Days leaving
        // the copy go into the all-time total.
        Map<LocalDate, Map<String, Long>> saved = readHistory(ctx);
        Map<String, Long> archive = readTotal(ctx);
        int archivedBefore = archive.size();
        long sumBefore = sum(archive);
        if (merge(saved, days, first, today, archive)) {
            writeHistory(ctx, saved);
            if (archive.size() != archivedBefore || sum(archive) != sumBefore) writeTotal(ctx, archive);
        }

        Set<String> excluded = LauncherSettings.usageExcluded(ctx); // Settings → Home screen
        return new Scores(totals(saved, weekStart, excluded), allTime(archive, saved));
    }

    /** The all-time total: the archived days plus every day still in the copy. */
    static Map<String, Long> allTime(Map<String, Long> archive, Map<LocalDate, Map<String, Long>> saved) {
        Map<String, Long> all = new HashMap<>(archive);
        totals(saved, LocalDate.MIN, Collections.emptySet()).forEach((k, v) -> all.merge(k, v, Long::sum));
        return all;
    }

    /**
     * Merges a day's Android figures into the launcher's copy (the larger wins, per day and app)
     * and drops days outside [{@code first}, {@code today}], adding their figures to
     * {@code archive} (the all-time total). Returns true if anything changed.
     */
    static boolean merge(Map<LocalDate, Map<String, Long>> saved, Map<LocalDate, Map<String, Long>> days,
                         LocalDate first, LocalDate today, Map<String, Long> archive) {
        boolean changed = false;
        for (Map.Entry<LocalDate, Map<String, Long>> d : days.entrySet()) {
            Map<String, Long> keep = saved.computeIfAbsent(d.getKey(), k -> new HashMap<>());
            for (Map.Entry<String, Long> e : d.getValue().entrySet()) {
                Long old = keep.get(e.getKey());
                if (old == null || e.getValue() > old) {
                    keep.put(e.getKey(), e.getValue());
                    changed = true;
                }
            }
        }
        for (Iterator<Map.Entry<LocalDate, Map<String, Long>>> it = saved.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<LocalDate, Map<String, Long>> d = it.next();
            if (!d.getKey().isBefore(first) && !d.getKey().isAfter(today)) continue;
            d.getValue().forEach((k, v) -> archive.merge(k, v, Long::sum));
            it.remove();
            changed = true;
        }
        return changed;
    }

    /** Per-app totals from {@code weekStart} on, leaving out {@code excluded}. */
    static Map<String, Long> totals(Map<LocalDate, Map<String, Long>> saved, LocalDate weekStart, Set<String> excluded) {
        Map<String, Long> scores = new HashMap<>();
        for (Map.Entry<LocalDate, Map<String, Long>> day : saved.entrySet()) {
            if (day.getKey().isBefore(weekStart)) continue; // kept, but last week
            for (Map.Entry<String, Long> e : day.getValue().entrySet()) {
                if (!excluded.contains(e.getKey())) scores.merge(e.getKey(), e.getValue(), Long::sum);
            }
        }
        return scores;
    }

    /** The first day of this week: the last Monday or Sunday (Settings), or today if it is one. */
    static LocalDate weekStart(Context ctx, LocalDate today) {
        DayOfWeek start = LauncherSettings.choice(ctx, LauncherSettings.WEEK_START) == 7 ? DayOfWeek.SUNDAY : DayOfWeek.MONDAY;
        return weekStart(start, today);
    }

    /** The first day of the week that starts on {@code start}: the last one, or today if it is one. */
    static LocalDate weekStart(DayOfWeek start, LocalDate today) {
        return today.with(TemporalAdjusters.previousOrSame(start));
    }

    private static long sum(Map<String, Long> m) {
        long t = 0;
        for (long v : m.values()) t += v;
        return t;
    }

    private static File totalFile(Context ctx) {
        return new File(ctx.getFilesDir(), "usage-alltime.json");
    }

    /** {"since": "2026-10-26", "apps": {"pkg": ms, ...}}; empty if not started yet. */
    private static Map<String, Long> readTotal(Context ctx) {
        File f = totalFile(ctx);
        Map<String, Long> out = new HashMap<>();
        if (!f.isFile()) return out;
        try {
            JSONObject apps = new JSONObject(new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8))
                    .getJSONObject("apps");
            for (Iterator<String> p = apps.keys(); p.hasNext(); ) {
                String pkg = p.next();
                out.put(pkg, apps.getLong(pkg));
            }
        } catch (Exception e) {
            out.clear(); // corrupt: start again
        }
        return out;
    }

    private static void writeTotal(Context ctx, Map<String, Long> apps) {
        try {
            File f = totalFile(ctx), tmp = new File(f.getPath() + ".tmp");
            String since = LocalDate.now().toString();
            if (f.isFile()) {
                try {
                    since = new JSONObject(new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8))
                            .optString("since", since);
                } catch (Exception ignored) {
                }
            }
            JSONObject a = new JSONObject();
            for (Map.Entry<String, Long> e : apps.entrySet()) a.put(e.getKey(), (long) e.getValue());
            JSONObject root = new JSONObject().put("since", since).put("apps", a);
            Files.write(tmp.toPath(), root.toString().getBytes(StandardCharsets.UTF_8));
            if (!tmp.renameTo(f)) tmp.delete();
        } catch (Exception ignored) {
        }
    }

    private static File historyFile(Context ctx) {
        return new File(ctx.getFilesDir(), "usage-history.json");
    }

    /** {"2026-10-23": {"pkg": ms, ...}, ...}; empty if missing or unreadable. */
    private static Map<LocalDate, Map<String, Long>> readHistory(Context ctx) {
        Map<LocalDate, Map<String, Long>> out = new HashMap<>();
        File f = historyFile(ctx);
        if (!f.isFile()) return out;
        try {
            JSONObject root = new JSONObject(new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8));
            for (Iterator<String> it = root.keys(); it.hasNext(); ) {
                String key = it.next();
                JSONObject day = root.getJSONObject(key);
                Map<String, Long> m = new HashMap<>();
                for (Iterator<String> p = day.keys(); p.hasNext(); ) {
                    String pkg = p.next();
                    m.put(pkg, day.getLong(pkg));
                }
                out.put(LocalDate.parse(key), m);
            }
        } catch (Exception e) {
            out.clear(); // corrupt: start again from Android's figures
        }
        return out;
    }

    private static void writeHistory(Context ctx, Map<LocalDate, Map<String, Long>> days) {
        try {
            JSONObject root = new JSONObject();
            for (Map.Entry<LocalDate, Map<String, Long>> d : days.entrySet()) {
                JSONObject day = new JSONObject();
                for (Map.Entry<String, Long> e : d.getValue().entrySet()) day.put(e.getKey(), (long) e.getValue());
                root.put(d.getKey().toString(), day);
            }
            File f = historyFile(ctx), tmp = new File(f.getPath() + ".tmp");
            Files.write(tmp.toPath(), root.toString().getBytes(StandardCharsets.UTF_8));
            if (!tmp.renameTo(f)) tmp.delete();
        } catch (Exception ignored) {
        }
    }

    static long score(Map<String, Long> scores, String pkg) {
        Long s = scores.get(pkg);
        return s == null ? 0 : s;
    }

    /** Most used first; apps with no recorded use keep their order (A-Z). List.sort is stable. */
    static void sort(List<AppEntry> list, Map<String, Long> scores) {
        list.sort((a, b) -> Long.compare(score(scores, b.pkg), score(scores, a.pkg)));
    }
}
