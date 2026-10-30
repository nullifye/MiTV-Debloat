package my.rafhan.app.tvhome;

import android.service.dreams.DreamService;

/**
 * "Prayer time": a screen saver with a big clock, the Gregorian and Hijri dates and today's
 * prayer times on black, drifting against burn-in (DreamView). The same picture LogDream falls
 * back to when it can't read the log. Chosen in the TV's Settings → Screen saver.
 */
public class ClockDream extends DreamService {
    private DreamView view;

    @Override
    public void onAttachedToWindow() {
        super.onAttachedToWindow();
        setInteractive(false); // any key wakes the TV
        setFullscreen(true);
        setScreenBright(false);
        view = new DreamView(UiScale.wrap(this)); // starts as the big clock
        setContentView(view);
    }

    @Override
    public void onDreamingStarted() {
        super.onDreamingStarted();
        view.start();
    }

    @Override
    public void onDreamingStopped() {
        view.stop();
        super.onDreamingStopped();
    }

    @Override
    public void onDetachedFromWindow() {
        if (view != null) view.stop();
        super.onDetachedFromWindow();
    }
}
