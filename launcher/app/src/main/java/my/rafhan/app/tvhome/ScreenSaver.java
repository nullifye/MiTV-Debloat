package my.rafhan.app.tvhome;

import android.Manifest;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.provider.Settings;
import android.service.dreams.DreamService;

import java.util.ArrayList;
import java.util.List;

/**
 * The TV's screen saver, picked in TV Home's Settings → Screen saver: OK cycles Prayer time →
 * Live log → Globe → every other screen saver on the TV (A–Z), like the TV's own list.
 * Some TVs (this Mi TV) can't open their screen-saver page from another app, so TV Home writes
 * the choice itself. That needs WRITE_SECURE_SETTINGS, a development permission granted over ADB
 * by the setup app and build-and-install.sh; without it the row opens the TV's Settings instead.
 */
final class ScreenSaver {
    private static final String COMPONENTS = "screensaver_components", ENABLED = "screensaver_enabled";

    private ScreenSaver() {}

    static ComponentName prayer(Context ctx) {
        return new ComponentName(ctx, ClockDream.class);
    }

    static ComponentName log(Context ctx) {
        return new ComponentName(ctx, LogDream.class);
    }

    static ComponentName globe(Context ctx) {
        return new ComponentName(ctx, GlobeDream.class);
    }

    static boolean canSet(Context ctx) {
        return ctx.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED;
    }

    /** Screen savers switched on (Android's default when it was never set: on). */
    static boolean enabled(Context ctx) {
        return Settings.Secure.getInt(ctx.getContentResolver(), ENABLED, 1) == 1;
    }

    static void setEnabled(Context ctx, boolean on) {
        try {
            Settings.Secure.putInt(ctx.getContentResolver(), ENABLED, on ? 1 : 0);
        } catch (SecurityException ignored) { // the permission was taken away meanwhile
        }
    }

    /** The screen saver in use (the first of the list Android keeps), or null. */
    static ComponentName current(Context ctx) {
        String v = Settings.Secure.getString(ctx.getContentResolver(), COMPONENTS);
        if (v == null || v.isEmpty()) return null;
        return ComponentName.unflattenFromString(v.split(",")[0]);
    }

    /** What OK cycles through: ours, then the TV's other screen savers A–Z. */
    static List<ComponentName> choices(Context ctx) {
        List<ComponentName> out = new ArrayList<>();
        out.add(prayer(ctx));
        out.add(log(ctx));
        out.add(globe(ctx));
        PackageManager pm = ctx.getPackageManager();
        List<ComponentName> others = new ArrayList<>();
        for (ResolveInfo r : pm.queryIntentServices(new Intent(DreamService.SERVICE_INTERFACE), 0)) {
            ComponentName c = new ComponentName(r.serviceInfo.packageName, r.serviceInfo.name);
            if (!c.getPackageName().equals(ctx.getPackageName())) others.add(c);
        }
        others.sort((a, b) -> label(ctx, a).compareToIgnoreCase(label(ctx, b)));
        out.addAll(others);
        return out;
    }

    /**
     * The next screen saver after the current one; saves it and turns screen savers on. Picking
     * Globe starts downloading its maps, so the first run usually has them.
     */
    static void next(Context ctx) {
        List<ComponentName> all = choices(ctx);
        int i = all.indexOf(current(ctx));
        ComponentName pick = all.get((i + 1) % all.size());
        try {
            Settings.Secure.putString(ctx.getContentResolver(), COMPONENTS, pick.flattenToString());
            Settings.Secure.putInt(ctx.getContentResolver(), ENABLED, 1);
        } catch (SecurityException ignored) { // the permission was taken away meanwhile
        }
        if (pick.equals(globe(ctx)) && !GlobeFeeds.mapsReady(ctx)) {
            Context app = ctx.getApplicationContext();
            Thread t = new Thread(() -> GlobeFeeds.fetchMaps(app), "globe-maps");
            t.setDaemon(true);
            t.start();
        }
    }

    /** "Prayer time", "Live log", "Globe", or the other screen saver's own name. */
    static String label(Context ctx, ComponentName c) {
        if (c == null) return "None";
        if (c.equals(prayer(ctx))) return "Prayer time";
        if (c.equals(log(ctx))) return "Live log";
        if (c.equals(globe(ctx))) return "Globe";
        try {
            return String.valueOf(ctx.getPackageManager().getServiceInfo(c, 0).loadLabel(ctx.getPackageManager()));
        } catch (PackageManager.NameNotFoundException e) {
            return c.getPackageName();
        }
    }
}
