package my.rafhan.app.tvhome;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.provider.Settings;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The phone remote's app manager: disable / enable apps and free the TV's caches, run as the shell
 * user over the loopback ADB connection (so only while USB debugging is on). Only disables, never
 * uninstalls; never touches what the TV needs to work. Every app disabled here is logged in
 * files/disabled-by-remote.txt (readable over ADB), so scripts/restore.sh can turn them back on.
 */
final class AppManager {
    // The TV can't do without these: Settings, the Play Store and its services, the system UI,
    // and live TV / HDMI inputs. The current keyboard and every home app are added at run time.
    private static final Set<String> PROTECTED = new HashSet<>(Arrays.asList(
            "android", "com.android.systemui", "com.android.tv.settings", "com.android.settings",
            "com.android.vending", "com.google.android.gms", "com.google.android.gsf",
            "com.mediatek.wwtv.tvcenter", "com.mediatek.tvinput", "com.mediatek.tv.service", "mitv.service"));

    private AppManager() {}

    private static final String LOG = "disabled-by-remote.txt";

    /**
     * The remote's disable log, in private storage: cleaners (Xiaomi's TV Manager) delete files in
     * apps' shared folders. A copy stays in the shared folder (sharedLog) for restore.sh and the
     * setup app, which read it over ADB; syncShared() writes it again if it goes missing.
     */
    private static File log(Context ctx) {
        return new File(ctx.getFilesDir(), LOG);
    }

    private static File sharedLog(Context ctx) {
        File dir = ctx.getExternalFilesDir(null);
        return dir == null ? null : new File(dir, LOG);
    }

    /**
     * Apps the remote disabled and that are still off, oldest first. Undo all changes (restore.sh,
     * the setup app) turns them back on, and they drop off the list by themselves.
     */
    static synchronized Set<String> disabled(Context ctx) {
        Set<String> out = read(log(ctx));
        if (!log(ctx).isFile()) out.addAll(read(sharedLog(ctx))); // older versions: shared folder only
        PackageManager pm = ctx.getPackageManager();
        out.removeIf(p -> {
            try {
                int state = pm.getApplicationEnabledSetting(p);
                return state != PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER
                        && state != PackageManager.COMPONENT_ENABLED_STATE_DISABLED;
            } catch (IllegalArgumentException e) {
                return true; // uninstalled
            }
        });
        return out;
    }

    /** Writes the log and its shared copy again (the copy may have been cleaned away). */
    static synchronized void syncShared(Context ctx) {
        save(ctx, disabled(ctx));
    }

    private static Set<String> read(File f) {
        Set<String> out = new LinkedHashSet<>();
        if (f == null || !f.isFile()) return out;
        try {
            for (String l : Files.readAllLines(f.toPath(), StandardCharsets.UTF_8)) {
                if (!l.trim().isEmpty()) out.add(l.trim());
            }
        } catch (Exception ignored) {
        }
        return out;
    }

    private static synchronized void save(Context ctx, Set<String> pkgs) {
        StringBuilder sb = new StringBuilder();
        for (String p : pkgs) sb.append(p).append('\n');
        byte[] bytes = sb.toString().getBytes(StandardCharsets.UTF_8);
        for (File f : new File[] {log(ctx), sharedLog(ctx)}) {
            if (f == null) continue;
            try (FileOutputStream out = new FileOutputStream(f)) {
                out.write(bytes);
            } catch (Exception ignored) {
            }
        }
    }

    /** Why {@code pkg} may not be disabled, or null if it may. */
    static String refusal(Context ctx, String pkg) {
        if (pkg == null || pkg.isEmpty()) return "No app given.";
        if (pkg.equals(ctx.getPackageName())) return "This is the home screen itself.";
        if (PROTECTED.contains(pkg)) return "The TV needs this app to work.";
        String ime = Settings.Secure.getString(ctx.getContentResolver(), Settings.Secure.DEFAULT_INPUT_METHOD);
        if (ime != null && ime.startsWith(pkg + "/")) return "That's the TV's keyboard.";
        List<ResolveInfo> homes = ctx.getPackageManager().queryIntentActivities(
                new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), PackageManager.MATCH_ALL);
        for (ResolveInfo r : homes) if (pkg.equals(r.activityInfo.packageName)) return "That's a home screen app.";
        for (AppEntry e : AppRepository.load(ctx)) if (e.pkg.equals(pkg)) return null;
        return "Only apps from the app list can be disabled.";
    }

    /** Disables {@code pkg} for the main user (never uninstalls) and logs it. Blocking. */
    static String disable(Context ctx, String pkg) {
        if (!validPackage(pkg)) return "No app given.";
        String why = refusal(ctx, pkg);
        if (why != null) return why;
        String out = run(ctx, "pm disable-user --user 0 " + pkg);
        if (out == null) return "Turn on USB debugging on the TV first.";
        if (!out.contains("disabled-user")) return "The TV refused: " + out.trim();
        Set<String> d = disabled(ctx);
        d.add(pkg);
        save(ctx, d);
        return null;
    }

    /** Re-enables {@code pkg} and drops it from the log. Blocking. */
    static String enable(Context ctx, String pkg) {
        if (!validPackage(pkg)) return "No app given.";
        String out = run(ctx, "pm enable --user 0 " + pkg);
        if (out == null) return "Turn on USB debugging on the TV first.";
        if (!out.contains("enabled")) return "The TV refused: " + out.trim();
        Set<String> d = disabled(ctx);
        d.remove(pkg);
        save(ctx, d);
        return null;
    }

    // Package names the phone remote can send are interpolated into ADB shell commands, so accept
    // only what Android itself accepts: letters, digits, dots and underscores. This stops a paired
    // phone from smuggling shell metacharacters ("pkg; ...") into `pm enable`.
    private static final Pattern PACKAGE_NAME = Pattern.compile("[A-Za-z0-9._]+");

    private static boolean validPackage(String pkg) {
        return pkg != null && pkg.length() <= 255 && PACKAGE_NAME.matcher(pkg).matches();
    }

    /** Frees every app's cache (Android 10's shell can't clear one app's); returns bytes freed, -1 without ADB. */
    static long trimCaches(Context ctx) {
        long before = SysInfo.disk(ctx)[0];
        if (run(ctx, "pm trim-caches 999G") == null) return -1;
        return Math.max(0, before - SysInfo.disk(ctx)[0]);
    }

    /** Runs {@code command} as the shell user and returns its output; null when ADB isn't ready. */
    static String run(Context ctx, String command) {
        AdbKeys adb = AdbKeys.get(ctx);
        if (adb.state() != AdbKeys.State.READY) return null;
        AdbKeys.Hid h = adb.shell("timeout 60 " + command + " 2>&1");
        if (h == null) return null;
        StringBuilder sb = new StringBuilder();
        try {
            for (String line; (line = h.stream.getSource().readUtf8Line()) != null; ) sb.append(line).append('\n');
        } catch (Exception ignored) {
        } finally {
            adb.stop(h);
        }
        return sb.toString();
    }
}
