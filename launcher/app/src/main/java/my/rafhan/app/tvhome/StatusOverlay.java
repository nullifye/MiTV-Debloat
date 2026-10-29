package my.rafhan.app.tvhome;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.PixelFormat;
import android.graphics.drawable.GradientDrawable;
import android.net.TrafficStats;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.TextView;

import androidx.core.content.ContextCompat;

import java.util.Calendar;
import java.util.Locale;

/**
 * Settings → Info overlay: network speed, uptime, a clock and the time since leaving home, in a
 * thin bar over other apps (like the NetSpeedTime app), never over the home screen. Left, centre and right each show one of
 * them, at the top or bottom of the screen. Not focusable or touchable, so it can't take keys
 * from the app underneath.
 *
 * It only exists while it's wanted: switched on, screen on, home not in front. Then one tick a
 * second updates the clock; speed (TrafficStats deltas) every 2 s, uptime every minute. Hidden,
 * nothing runs.
 */
final class StatusOverlay implements LauncherSettings.Listener {
    private static final String[] KEYS = {LauncherSettings.STATUS_LEFT, LauncherSettings.STATUS_CENTER,
            LauncherSettings.STATUS_RIGHT};
    private static final int BG = 0x80000000;

    // Choices for Settings (TV and phone).
    static final int[] SLOT_VALUES = {LauncherSettings.SLOT_SPEED, LauncherSettings.SLOT_UPTIME,
            LauncherSettings.SLOT_CLOCK, LauncherSettings.SLOT_ELAPSED, LauncherSettings.SLOT_NONE};
    static final String[] SLOT_NAMES = {"Network speed", "Uptime", "Clock", "Elapsed time", "Nothing"};
    static final int[] POS_VALUES = {0, 1};
    static final String[] POS_NAMES = {"Top", "Bottom"};
    static final int[] SIZE_VALUES = {10, 12, 14, 18};
    static final String[] SIZE_NAMES = {"Tiny", "Small", "Medium", "Large"};

    private final Context ctx;
    private final WindowManager wm;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Runnable homeChanged = this::update;
    private final Runnable tick = this::tick;
    private final TextView[] slots = new TextView[3];
    private final int[] kinds = new int[3];
    private View view;
    private boolean screenOn = true;
    private int ticks;
    private long lastRx = -1, lastTx, lastAt;
    private long since; // elapsedRealtime when the bar appeared (left home, screen on); 0 = hidden
    private String speed = "↓ 0 Kb  ↑ 0 Kb";

    private final BroadcastReceiver screen = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent i) {
            screenOn = Intent.ACTION_SCREEN_ON.equals(i.getAction());
            update();
        }
    };

    StatusOverlay(Context ctx) {
        this.ctx = ctx;
        wm = ctx.getSystemService(WindowManager.class);
    }

    void start() {
        PowerManager pm = ctx.getSystemService(PowerManager.class);
        screenOn = pm == null || pm.isInteractive();
        IntentFilter f = new IntentFilter(Intent.ACTION_SCREEN_ON);
        f.addAction(Intent.ACTION_SCREEN_OFF);
        ContextCompat.registerReceiver(ctx, screen, f, ContextCompat.RECEIVER_EXPORTED);
        LauncherSettings.addListener(this);
        RemoteBridge.addHomeListener(homeChanged);
        update();
    }

    void stop() {
        RemoteBridge.removeHomeListener(homeChanged);
        LauncherSettings.removeListener(this);
        try {
            ctx.unregisterReceiver(screen);
        } catch (RuntimeException ignored) {
        }
        hide();
    }

    @Override
    public void onSettingChanged(String key) {
        if (!key.startsWith("status_")) return;
        hide(); // position, size, background or slots may have changed: rebuild
        update();
    }

    /** Shows or hides the bar to match the setting, the screen and what's in front. Main thread. */
    private void update() {
        boolean any = false;
        for (int i = 0; i < 3; i++) {
            kinds[i] = LauncherSettings.choice(ctx, KEYS[i]);
            any |= kinds[i] != LauncherSettings.SLOT_NONE;
        }
        boolean want = any && screenOn && !RemoteBridge.isHome()
                && LauncherSettings.flag(ctx, LauncherSettings.STATUS_BAR) && Settings.canDrawOverlays(ctx);
        if (!want) since = 0; // elapsed time starts again next time (a settings rebuild keeps it)
        else if (since == 0) since = SystemClock.elapsedRealtime();
        if (want && view == null) show();
        else if (!want && view != null) hide();
    }

    private void show() {
        float dp = ctx.getResources().getDisplayMetrics().density;
        int size = LauncherSettings.choice(ctx, LauncherSettings.STATUS_SIZE);
        boolean bottom = LauncherSettings.choice(ctx, LauncherSettings.STATUS_POS) == 1;
        boolean bg = LauncherSettings.flag(ctx, LauncherSettings.STATUS_BG);
        FrameLayout bar = new FrameLayout(ctx);
        int[] gravity = {Gravity.START, Gravity.CENTER_HORIZONTAL, Gravity.END};
        for (int i = 0; i < 3; i++) {
            TextView t = new TextView(ctx);
            t.setTextSize(size);
            t.setTextColor(0xFFFFFFFF);
            t.setTypeface(Fonts.outfit(ctx, 500));
            t.setFontFeatureSettings("tnum"); // fixed-width digits: the clock doesn't jiggle
            t.setSingleLine(true);
            t.setIncludeFontPadding(false);
            // Edge slots hug the screen edge: 4 dp on the outside, 7 dp inside (past the rounded
            // corner), so the background stays snug. No padding above or below: the text sits
            // right at the screen edge, as in NetSpeedTime.
            int out = Math.round(4 * dp), in = Math.round(7 * dp), v = 0;
            if (i == 0) t.setPadding(out, v, in, v);
            else if (i == 1) t.setPadding(in, v, in, v);
            else t.setPadding(in, v, out, v);
            if (bg) t.setBackground(background(i, 10 * dp));
            t.setVisibility(kinds[i] == LauncherSettings.SLOT_NONE ? View.GONE : View.VISIBLE);
            bar.addView(t, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT, gravity[i] | (bottom ? Gravity.BOTTOM : Gravity.TOP)));
            slots[i] = t;
        }
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        lp.gravity = bottom ? Gravity.BOTTOM : Gravity.TOP;
        try {
            wm.addView(bar, lp);
            view = bar;
        } catch (RuntimeException e) {
            view = null;
            return;
        }
        lastRx = -1;
        speed = "↓ 0 Kb  ↑ 0 Kb";
        ticks = 0;
        tick();
    }

    private void hide() {
        main.removeCallbacks(tick);
        if (view != null) {
            try {
                wm.removeView(view);
            } catch (RuntimeException ignored) {
            }
            view = null;
        }
    }

    /** Edge slots are square against the screen edge and rounded inside; the centre is a pill. */
    private static GradientDrawable background(int slot, float r) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(BG);
        float left = slot == 0 ? 0 : r, right = slot == 2 ? 0 : r;
        // top-left, top-right, bottom-right, bottom-left (x, y each)
        d.setCornerRadii(new float[] {left, left, right, right, right, right, left, left});
        return d;
    }

    private void tick() {
        if (view == null) return;
        if (ticks % 2 == 0) readSpeed();
        String uptime = null, clock = null, elapsed = null;
        for (int i = 0; i < 3; i++) {
            String text;
            switch (kinds[i]) {
                case LauncherSettings.SLOT_SPEED:
                    text = speed;
                    break;
                case LauncherSettings.SLOT_UPTIME:
                    if (uptime == null) uptime = formatUptime(SystemClock.elapsedRealtime());
                    text = uptime;
                    break;
                case LauncherSettings.SLOT_CLOCK:
                    if (clock == null) clock = formatClock(Calendar.getInstance());
                    text = clock;
                    break;
                case LauncherSettings.SLOT_ELAPSED:
                    if (elapsed == null) elapsed = formatElapsed(SystemClock.elapsedRealtime() - since);
                    text = elapsed;
                    break;
                default:
                    continue;
            }
            if (!text.contentEquals(slots[i].getText())) slots[i].setText(text);
        }
        ticks++;
        // On the next whole second, so the clock's seconds change on time.
        main.postDelayed(tick, 1000 - System.currentTimeMillis() % 1000);
    }

    private void readSpeed() {
        long rx = TrafficStats.getTotalRxBytes(), tx = TrafficStats.getTotalTxBytes();
        long now = SystemClock.elapsedRealtime();
        if (rx == TrafficStats.UNSUPPORTED || tx == TrafficStats.UNSUPPORTED) return;
        if (lastRx >= 0 && now > lastAt) {
            double secs = (now - lastAt) / 1000.0;
            speed = "↓ " + formatSpeed(Math.max(0, rx - lastRx) * 8 / secs)
                    + "  ↑ " + formatSpeed(Math.max(0, tx - lastTx) * 8 / secs);
        }
        lastRx = rx;
        lastTx = tx;
        lastAt = now;
    }

    // ---------------------------------------------------------------- formatting (unit tested)

    /** Bits per second, decimal: "850 Kb", "12.4 Mb", "1.2 Gb" (as NetSpeedTime shows it). */
    static String formatSpeed(double bitsPerSec) {
        if (bitsPerSec >= 1e9) return String.format(Locale.US, "%.1f Gb", bitsPerSec / 1e9);
        if (bitsPerSec >= 1e6) return String.format(Locale.US, "%.1f Mb", bitsPerSec / 1e6);
        return (long) (bitsPerSec / 1e3) + " Kb";
    }

    /** "23h6m", or "2d3h15m" past a day. */
    static String formatUptime(long elapsedMs) {
        long m = elapsedMs / 60_000, d = m / 1440, h = (m / 60) % 24, min = m % 60;
        return d > 0 ? d + "d" + h + "h" + min + "m" : h + "h" + min + "m";
    }

    /** Time since leaving home: "04:07", or "1:04:07" past an hour. */
    static String formatElapsed(long ms) {
        long s = Math.max(0, ms) / 1000, h = s / 3600, m = (s / 60) % 60, sec = s % 60;
        return h > 0 ? String.format(Locale.US, "%d:%02d:%02d", h, m, sec)
                : String.format(Locale.US, "%02d:%02d", m, sec);
    }

    /** Always 24-hour with seconds: "07:05:09". */
    static String formatClock(Calendar c) {
        return String.format(Locale.US, "%02d:%02d:%02d",
                c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE), c.get(Calendar.SECOND));
    }
}
