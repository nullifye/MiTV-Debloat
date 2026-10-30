package my.rafhan.app.tvhome;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Dock packages, left to right: dock.txt in the app's private storage, else res/values/dock.xml.
 * Edited from the TV (hold OK / Menu on a tile) and the phone remote.
 *
 * Private storage, because cleaners delete files in apps' shared folders: Xiaomi's TV Manager
 * cleans /sdcard/Android/data/<app>/files when the TV wakes, and the dock went back to the
 * default. A copy stays there so the dock is still hand-editable over ADB: a copy that isn't the
 * one the launcher last wrote there (pushed over ADB, or left by an older version) is taken in,
 * and a deleted or stale copy is written again.
 */
final class DockConfig {
    /** dock.txt keeps at most this many; the dock shows "apps per row" of them (5 to 7). */
    static final int MAX = 7;

    /** How many dock apps show (and app-list columns): Settings → Home screen → Apps per row. */
    static int size(Context ctx) {
        return LauncherSettings.choice(ctx, LauncherSettings.APPS_PER_ROW);
    }

    /**
     * The dock to save: {@code shown} (the edited dock, left to right) followed by the saved apps
     * that didn't fit after "apps per row" was lowered: not in {@code shownBefore}, still
     * {@code installed}. They come back when the number goes up again.
     */
    static List<String> keepHidden(List<String> shown, List<String> saved, List<String> shownBefore,
                                   java.util.Set<String> installed) {
        List<String> out = new ArrayList<>(shown);
        for (String p : saved) {
            if (!shownBefore.contains(p) && !out.contains(p) && installed.contains(p)) out.add(p);
        }
        return out;
    }

    interface Listener {
        void onDockChanged();
    }

    private static final List<Listener> listeners = new CopyOnWriteArrayList<>();
    private static final Handler main = new Handler(Looper.getMainLooper());

    private DockConfig() {}

    static void addListener(Listener l) {
        listeners.add(l);
    }

    static void removeListener(Listener l) {
        listeners.remove(l);
    }

    /** Changes whenever dock.txt is written (by us, the phone, or an ADB push); 0 if absent. */
    static long stamp(Context ctx) {
        File own = file(ctx), shared = sharedFile(ctx);
        long a = own.isFile() ? own.lastModified() ^ own.length() : 0;
        long b = shared != null && shared.isFile() ? shared.lastModified() ^ shared.length() : 0;
        return a * 31 + b;
    }

    /** The dock: private storage, where nothing else deletes it. */
    private static File file(Context ctx) {
        return new File(ctx.getFilesDir(), "dock.txt");
    }

    /** The copy in the shared folder, for ADB (null without external storage). */
    private static File sharedFile(Context ctx) {
        File dir = ctx.getExternalFilesDir(null);
        return dir == null ? null : new File(dir, "dock.txt");
    }

    static synchronized List<String> load(Context ctx) {
        File own = file(ctx), shared = sharedFile(ctx);
        String ownText = read(own), sharedText = shared == null ? null : read(shared);
        String ours = prefs(ctx).getString(SHARED_WRITTEN, null); // what we last wrote there
        if (sharedText != null && !sharedText.equals(ownText)) {
            if (ownText == null || !sharedText.equals(ours)) {
                if (write(own, sharedText)) ownText = sharedText; // pushed over ADB: take it in
            } else {
                writeShared(ctx, shared, ownText); // our own copy, out of date
            }
        } else if (ownText != null && shared != null && sharedText == null) {
            writeShared(ctx, shared, ownText); // a cleaner deleted the copy: put it back
        }
        if (ownText != null) return parse(Arrays.asList(ownText.split("\n"))); // may be empty
        return new ArrayList<>(Arrays.asList(ctx.getResources().getStringArray(R.array.dock_packages)));
    }

    /** The file's text, or null when it isn't there. */
    private static String read(File f) {
        if (!f.isFile()) return null;
        try {
            return new String(java.nio.file.Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
        } catch (IOException e) {
            return null;
        }
    }

    private static final String SHARED_WRITTEN = "shared_written";

    private static android.content.SharedPreferences prefs(Context ctx) {
        return ctx.getSharedPreferences("dock", Context.MODE_PRIVATE);
    }

    private static void writeShared(Context ctx, File shared, String text) {
        if (write(shared, text)) prefs(ctx).edit().putString(SHARED_WRITTEN, text).apply();
    }

    /** Writes through a temporary file, so a reader never sees half a dock. */
    private static boolean write(File f, String text) {
        File tmp = new File(f.getPath() + ".tmp");
        try (FileOutputStream out = new FileOutputStream(tmp)) {
            out.write(text.getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            return false;
        }
        return tmp.renameTo(f);
    }

    /** dock.txt's lines: trimmed, comments and blanks dropped, duplicates removed, order kept. */
    static List<String> parse(List<String> lines) {
        List<String> out = new ArrayList<>();
        for (String line : lines) {
            line = line.trim();
            if (!line.isEmpty() && !line.startsWith("#") && !out.contains(line)) out.add(line);
        }
        return out;
    }

    /** At most MAX packages, no nulls, empties or duplicates, order kept. */
    static List<String> clean(List<String> pkgs) {
        List<String> out = new ArrayList<>();
        for (String p : pkgs) if (p != null && !p.isEmpty() && !out.contains(p) && out.size() < MAX) out.add(p);
        return out;
    }

    /** Saves the order (at most MAX, duplicates dropped) and tells listeners on the main thread. */
    static synchronized boolean save(Context ctx, List<String> pkgs) {
        StringBuilder sb = new StringBuilder("# Dock apps, left to right (edited on the TV or phone)\n");
        for (String p : clean(pkgs)) sb.append(p).append('\n');
        String text = sb.toString();
        File shared = sharedFile(ctx);
        if (shared != null) writeShared(ctx, shared, text);
        if (!write(file(ctx), text)) return false;
        main.post(() -> {
            for (Listener l : listeners) l.onDockChanged();
        });
        return true;
    }
}
