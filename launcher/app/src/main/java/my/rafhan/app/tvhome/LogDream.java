package my.rafhan.app.tvhome;

import android.Manifest;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.service.dreams.DreamService;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;

/**
 * "Live log": a screen saver showing the TV's log live on black (DreamView), chosen in the
 * TV's Settings → Screen saver. Reads the log like the bell's Play Store reader (PlayUpdates),
 * but as a stream: its own logcat with READ_LOGS below Android 13 (13+ asks on screen whenever an
 * app reads the log), else over the launcher's own ADB connection (AdbKeys) while USB debugging is
 * on. Neither works: the big clock with the dates and prayer times instead (what ClockDream
 * shows), trying the log again every 30 s.
 */
public class LogDream extends DreamService {
    private static final String LOGCAT = "logcat -v time -T 150";
    private static final long RETRY_MS = 30_000, FLUSH_MS = 100, ADB_WAIT_MS = 5_000;

    private final Handler main = new Handler(Looper.getMainLooper());
    private final List<DreamView.LogLine> pending = new ArrayList<>();
    private DreamView view;
    private volatile boolean running;
    private volatile Process process;
    private volatile AdbKeys.Hid hid;
    private Thread reader;

    @Override
    public void onAttachedToWindow() {
        super.onAttachedToWindow();
        setInteractive(false); // any key wakes the TV
        setFullscreen(true);
        setScreenBright(false);
        view = new DreamView(UiScale.wrap(this));
        // The log layout (black, small clock) while the first lines are on their way: starting
        // the reader takes a moment, and the prayer-time picture is only for when it can't.
        view.setLogMode(true);
        setContentView(view);
    }

    @Override
    public void onDreamingStarted() {
        super.onDreamingStarted();
        running = true;
        view.start();
        reader = new Thread(this::readLoop, "dream-log");
        reader.setDaemon(true);
        reader.start();
    }

    @Override
    public void onDreamingStopped() {
        stopReading();
        super.onDreamingStopped();
    }

    @Override
    public void onDetachedFromWindow() {
        stopReading();
        super.onDetachedFromWindow();
    }

    private void stopReading() {
        running = false;
        if (view != null) view.stop();
        Process p = process;
        if (p != null) p.destroy(); // unblocks the reader
        AdbKeys.Hid h = hid;
        if (h != null) AdbKeys.get(this).stop(h);
        main.removeCallbacksAndMessages(null);
    }

    private void readLoop() {
        while (running) {
            boolean read = readOwn() || readOverAdb();
            if (!running) break;
            if (!read) main.post(() -> view.setLogMode(false)); // the big clock
            try {
                Thread.sleep(read ? 1_000 : RETRY_MS);
            } catch (InterruptedException e) {
                return;
            }
        }
    }

    /** Its own logcat, below Android 13 with READ_LOGS. True if it got any lines. */
    private boolean readOwn() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                || checkSelfPermission(Manifest.permission.READ_LOGS) != PackageManager.PERMISSION_GRANTED) {
            return false;
        }
        boolean any = false;
        try {
            process = new ProcessBuilder(LOGCAT.split(" ")).redirectErrorStream(true).start();
            try (BufferedReader r = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                for (String line; running && (line = r.readLine()) != null; ) any |= offer(line);
            }
        } catch (Exception ignored) {
        } finally {
            Process p = process;
            process = null;
            if (p != null) p.destroy();
        }
        return any;
    }

    /** Over the launcher's own ADB connection, while USB debugging is on. True if it got lines. */
    private boolean readOverAdb() {
        AdbKeys adb = AdbKeys.get(this);
        adb.ensure();
        long until = System.currentTimeMillis() + ADB_WAIT_MS;
        while (running && adb.state() != AdbKeys.State.READY && System.currentTimeMillis() < until) {
            try {
                Thread.sleep(200);
            } catch (InterruptedException e) {
                return false;
            }
        }
        if (!running || adb.state() != AdbKeys.State.READY) return false;
        AdbKeys.Hid h = adb.shell(LOGCAT);
        if (h == null) return false;
        hid = h;
        boolean any = false;
        try {
            for (String line; running && (line = h.stream.getSource().readUtf8Line()) != null; ) any |= offer(line);
        } catch (Exception ignored) {
        } finally {
            hid = null;
            adb.stop(h);
        }
        return any;
    }

    /** Queues a line for the view; batches go to the screen at most 10 times a second. */
    private boolean offer(String raw) {
        DreamView.LogLine l = DreamView.parse(raw);
        if (l == null) return false;
        synchronized (pending) {
            boolean first = pending.isEmpty();
            pending.add(l);
            if (first) main.postDelayed(this::flush, FLUSH_MS);
        }
        return true;
    }

    private void flush() {
        List<DreamView.LogLine> batch;
        synchronized (pending) {
            batch = new ArrayList<>(pending);
            pending.clear();
        }
        if (batch.isEmpty() || view == null) return;
        view.setLogMode(true);
        view.add(batch);
    }
}
