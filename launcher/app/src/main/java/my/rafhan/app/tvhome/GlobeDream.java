package my.rafhan.app.tvhome;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.service.dreams.DreamService;
import android.view.KeyEvent;
import android.widget.FrameLayout;

/**
 * "Globe": a screen saver with the Earth turning, lit as it is right now, with the prayer lines
 * and live dots (GlobeView). Its two maps are downloaded the first time (GlobeFeeds); until they
 * are, it shows the Prayer time picture (DreamView), like Live log does when it can't read the
 * log, and switches over once they're in. Chosen in TV Home's Settings → Screen saver.
 *
 * The remote's arrows turn the globe (GlobeView.key); any other key ends the screen saver.
 */
public class GlobeDream extends DreamService {
    private final Handler main = new Handler(Looper.getMainLooper());
    private GlobeFeeds feeds;
    private Context ui;
    private GlobeView globe;
    private GlobeView.Overlay overlay;
    private DreamView fallback;
    private boolean dreaming;
    private int exitKey = KeyEvent.KEYCODE_UNKNOWN; // pressed here; the screen saver ends on its release

    @Override
    public void onAttachedToWindow() {
        super.onAttachedToWindow();
        setInteractive(true); // keys come here: the arrows turn the globe, the rest end it (dispatchKeyEvent)
        setFullscreen(true);
        setScreenBright(false);
        ui = UiScale.wrap(this);
        feeds = new GlobeFeeds(this);
        if (GlobeFeeds.mapsReady(this)) {
            showGlobe();
        } else {
            fallback = new DreamView(ui); // the big clock
            setContentView(fallback);
        }
    }

    private void showGlobe() {
        FrameLayout root = new FrameLayout(ui);
        root.setBackgroundColor(0xFF000000);
        globe = new GlobeView(ui, feeds);
        overlay = new GlobeView.Overlay(ui, feeds);
        root.addView(globe);
        root.addView(overlay);
        setContentView(root);
    }

    @Override
    public void onDreamingStarted() {
        super.onDreamingStarted();
        dreaming = true;
        if (globe != null) {
            startGlobe();
            return;
        }
        fallback.start();
        Context app = getApplicationContext();
        Thread t = new Thread(() -> {
            if (GlobeFeeds.fetchMaps(app)) main.post(() -> {
                if (!dreaming || globe != null) return;
                fallback.stop();
                fallback = null;
                showGlobe();
                startGlobe();
            });
        }, "globe-maps");
        t.setDaemon(true);
        t.start();
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        if (globe != null && globe.key(e)) {
            if (overlay != null) overlay.touched();
            return true;
        }
        switch (e.getKeyCode()) {
            case KeyEvent.KEYCODE_DPAD_LEFT:
            case KeyEvent.KEYCODE_DPAD_RIGHT:
            case KeyEvent.KEYCODE_DPAD_UP:
            case KeyEvent.KEYCODE_DPAD_DOWN:
                return true; // the clock before the maps are in: nothing to turn
            default:
                // Any other key ends it, on its release: ending on the press would hand the release to
                // the app underneath, and releasing OK there clicks whatever is focused. A release
                // without its press here (OK on Settings' "Start now") is ignored.
                if (e.getAction() == KeyEvent.ACTION_DOWN) {
                    exitKey = e.getKeyCode();
                } else if (e.getAction() == KeyEvent.ACTION_UP && e.getKeyCode() == exitKey) {
                    finish();
                }
                return true;
        }
    }

    private void startGlobe() {
        globe.start();
        overlay.start();
        feeds.start();
    }

    @Override
    public void onDreamingStopped() {
        stopAll();
        super.onDreamingStopped();
    }

    @Override
    public void onDetachedFromWindow() {
        stopAll();
        super.onDetachedFromWindow();
    }

    private void stopAll() {
        dreaming = false;
        main.removeCallbacksAndMessages(null);
        if (feeds != null) feeds.stop();
        if (globe != null) globe.stop();
        if (overlay != null) overlay.stop();
        if (fallback != null) fallback.stop();
    }
}
