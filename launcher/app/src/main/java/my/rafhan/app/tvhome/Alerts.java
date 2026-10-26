package my.rafhan.app.tvhome;

import android.content.Intent;
import android.os.Handler;
import android.os.Looper;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * The bell's notifications, in memory (like Matinee's): TV Home and Play Store updates, new
 * HDMI/Bluetooth devices and low memory, fed by AlertSources in RemoteService and shown by MainActivity.
 */
final class Alerts {
    static final int PLAY = 0, HDMI = 1, BLUETOOTH = 2, MEMORY = 3, UPDATE = 4;
    private static final int MAX = 20;

    static final class Alert {
        final String id;
        final int type;
        final String title, text;
        final long time;
        final Intent action; // null: nothing to open

        Alert(String id, int type, String title, String text, long time, Intent action) {
            this.id = id;
            this.type = type;
            this.title = title;
            this.text = text;
            this.time = time;
            this.action = action;
        }
    }

    interface Listener {
        void onAlertsChanged();
    }

    private static final Alerts INSTANCE = new Alerts();
    private final List<Alert> items = new ArrayList<>();
    private final Set<String> seen = new HashSet<>();
    private final List<Listener> listeners = new CopyOnWriteArrayList<>();
    private final Handler main = new Handler(Looper.getMainLooper());

    static Alerts get() {
        return INSTANCE;
    }

    void addListener(Listener l) {
        listeners.add(l);
    }

    void removeListener(Listener l) {
        listeners.remove(l);
    }

    /** Newest first. */
    synchronized List<Alert> list() {
        return new ArrayList<>(items);
    }

    synchronized int unread() {
        int n = 0;
        for (Alert a : items) if (!seen.contains(a.id)) n++;
        return n;
    }

    void add(Alert a) {
        synchronized (this) {
            for (Alert x : items) if (x.id.equals(a.id)) return;
            items.add(0, a);
            while (items.size() > MAX) seen.remove(items.remove(items.size() - 1).id);
        }
        changed();
    }

    /** Replaces every alert of {@code type} with {@code now} (Play updates mirror the Play Store). */
    void sync(int type, List<Alert> now) {
        synchronized (this) {
            Set<String> keep = new HashSet<>();
            for (Alert a : now) keep.add(a.id);
            boolean same = true;
            for (Alert a : items) if (a.type == type && !keep.contains(a.id)) same = false;
            Set<String> have = new HashSet<>();
            for (Alert a : items) if (a.type == type) have.add(a.id);
            if (!have.containsAll(keep)) same = false;
            if (same) return;
            List<Alert> next = new ArrayList<>();
            for (Alert a : items) if (a.type != type) next.add(a);
            next.addAll(now);
            next.sort((x, y) -> Long.compare(y.time, x.time));
            items.clear();
            items.addAll(next.subList(0, Math.min(MAX, next.size())));
        }
        changed();
    }

    void dismiss(Alert a) {
        synchronized (this) {
            items.remove(a);
            seen.remove(a.id);
        }
        changed();
    }

    void clear() {
        synchronized (this) {
            items.clear();
            seen.clear();
        }
        changed();
    }

    void markAllSeen() {
        synchronized (this) {
            for (Alert a : items) seen.add(a.id);
        }
        changed();
    }

    private void changed() {
        main.post(() -> {
            for (Listener l : listeners) l.onAlertsChanged();
        });
    }
}
