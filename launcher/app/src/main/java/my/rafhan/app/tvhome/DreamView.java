package my.rafhan.app.tvhome;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.view.View;
import android.view.animation.DecelerateInterpolator;

import java.time.LocalDate;
import java.util.ArrayDeque;
import java.util.Calendar;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * What the screen savers show, drawn on a Canvas (hundreds of lines a minute are too many for
 * TextViews):
 *  - the TV's log, newest at the bottom, with a small clock (LogDream, while it can read the log);
 *  - a big clock with the Gregorian and Hijri dates and today's prayer times (ClockDream, and
 *    LogDream when the log can't be read).
 * Against burn-in: no white, everything drifts. The log block slides to a new offset every
 * minute, and the clock jumps to a new spot, fading. New log lines glide in from below
 * instead of jumping a line at a time.
 */
final class DreamView extends View {
    /** One `logcat -v time` line, split for drawing. */
    static final class LogLine {
        final String time, tag, msg;
        final char level;

        LogLine(String time, char level, String tag, String msg) {
            this.time = time;
            this.level = level;
            this.tag = tag;
            this.msg = msg;
        }
    }

    // "10-30 06:30:17.456 I/Finsky  (32118): message"
    private static final Pattern LINE = Pattern.compile(
            "^\\d\\d-\\d\\d (\\d\\d:\\d\\d:\\d\\d)\\.\\d+ ([VDIWEFA])/(.*?)\\s*\\(\\s*\\d+\\): ?(.*)$");

    /** The parts of a log line; null for logcat's own "--------- beginning of main" markers. */
    static LogLine parse(String raw) {
        if (raw == null || raw.startsWith("---------")) return null;
        Matcher m = LINE.matcher(raw);
        if (m.matches()) return new LogLine(m.group(1), m.group(2).charAt(0), m.group(3).trim(), m.group(4));
        return new LogLine("", ' ', "", raw.trim()); // a continuation without its own header
    }

    private static final int KEEP = 300;
    private static final int GREY = 0xFF737373, DIM = 0xFF5E5E5E, DARK = 0xFF4A4A4A, AMBER = 0xFF9A7A3A,
            RED = 0xFFA04848;
    private static final int MAX_MSG = 300; // characters: more never fits on a line
    private static final long MOVE_EVERY_MS = 60_000, SLIDE_MS = 2_000;

    private final ArrayDeque<LogLine> lines = new ArrayDeque<>();
    private final Paint mono = new Paint(Paint.ANTI_ALIAS_FLAG), clockPaint = new Paint(Paint.ANTI_ALIAS_FLAG),
            bigTime = new Paint(Paint.ANTI_ALIAS_FLAG), bigDate = new Paint(Paint.ANTI_ALIAS_FLAG),
            clockBox = new Paint();
    private final Random random = new Random();
    private final float density;
    private boolean logMode;
    // Log block offset (slides) and the clock's spot (fades).
    private float driftX, driftY, clockFx = 0.5f, clockFy = 0.5f, clockAlpha = 1f;
    private String hijri; // big clock, refreshed every minute
    private java.time.LocalTime[] prayers; // today's five times (PrayerPanel.NAMES), or null
    private final android.graphics.drawable.Drawable[] prayerIcons =
            new android.graphics.drawable.Drawable[PrayerPanel.NAMES.length];
    private final Paint prayerName = new Paint(Paint.ANTI_ALIAS_FLAG), prayerTime = new Paint(Paint.ANTI_ALIAS_FLAG);
    // Smooth scrolling: how far below their place the newest lines still are (px), eased to 0.
    private float scrollBy;
    private long lastFrameNs;
    private ValueAnimator slide, fade;

    DreamView(Context ctx) {
        super(ctx);
        density = ctx.getResources().getDisplayMetrics().density;
        setBackgroundColor(Color.BLACK);
        mono.setTypeface(Typeface.MONOSPACE);
        mono.setTextSize(sp(11));
        clockPaint.setTypeface(Fonts.outfit(ctx, 500));
        clockPaint.setTextSize(sp(16));
        clockPaint.setColor(GREY);
        clockPaint.setFontFeatureSettings("tnum");
        bigTime.setTypeface(Fonts.outfit(ctx, 300));
        bigTime.setTextSize(sp(96));
        bigTime.setColor(GREY);
        bigTime.setFontFeatureSettings("tnum"); // even-width digits, where the font has them
        bigDate.setTypeface(Fonts.outfit(ctx, 400));
        bigDate.setTextSize(sp(22));
        bigDate.setColor(DIM);
        prayerName.setTypeface(Fonts.outfit(ctx, 500));
        prayerName.setTextSize(sp(13));
        prayerName.setTextAlign(Paint.Align.CENTER);
        prayerName.setLetterSpacing(0.08f);
        prayerTime.setTypeface(Fonts.outfit(ctx, 400));
        prayerTime.setTextSize(sp(22));
        prayerTime.setTextAlign(Paint.Align.CENTER);
        prayerTime.setFontFeatureSettings("tnum");
        // The prayer card's icons (sunrise, sun, …), tinted per draw.
        for (int i = 0; i < prayerIcons.length; i++) {
            android.graphics.drawable.Drawable d = ctx.getDrawable(PrayerPanel.icon(PrayerPanel.NAMES[i]));
            prayerIcons[i] = d == null ? null : d.mutate();
        }
        bigDate.setTextAlign(Paint.Align.CENTER);
        refreshHijri();
    }

    private float sp(float v) {
        return v * getResources().getDisplayMetrics().scaledDensity;
    }

    private float dp(float v) {
        return v * density;
    }

    /**
     * The log layout (true: lines, or waiting for the first ones), or the big clock with the
     * prayer times (false: ClockDream, or LogDream when it can't read the log). Starts false.
     */
    void setLogMode(boolean on) {
        if (logMode == on) return;
        logMode = on;
        // The small clock starts top right; the big one in the middle.
        clockFx = on ? 0.92f : 0.5f;
        clockFy = on ? 0.08f : 0.5f;
        invalidate();
    }

    void add(List<LogLine> batch) {
        for (LogLine l : batch) {
            lines.addLast(l);
            if (lines.size() > KEEP) lines.removeFirst();
        }
        // The new lines start below the block and glide up; a burst never backs up past a screen.
        scrollBy = Math.min(scrollBy + batch.size() * mono.getFontSpacing(), Math.max(1, getHeight()));
        if (lastFrameNs == 0) lastFrameNs = System.nanoTime();
        postInvalidateOnAnimation();
    }

    /** Eases scrollBy towards 0: quicker the more is still to come, at least 40 dp a second. */
    private void stepScroll() {
        long now = System.nanoTime();
        float dt = Math.min(0.1f, (now - lastFrameNs) / 1e9f);
        lastFrameNs = now;
        if (scrollBy <= 0) return;
        scrollBy = Math.max(0, scrollBy - Math.max(scrollBy * 6f * dt, dp(40) * dt));
        if (scrollBy > 0) postInvalidateOnAnimation();
        else lastFrameNs = 0;
    }

    /** Every minute: a new log offset and clock spot, and the dates again. */
    private final Runnable move = new Runnable() {
        @Override
        public void run() {
            slideTo(dp(24) * (random.nextFloat() * 2 - 1), dp(24) * (random.nextFloat() * 2 - 1));
            jumpClock();
            refreshHijri();
            postDelayed(this, MOVE_EVERY_MS);
        }
    };

    void start() {
        removeCallbacks(move);
        postDelayed(move, MOVE_EVERY_MS);
        ticker.run();
    }

    void stop() {
        removeCallbacks(move);
        removeCallbacks(ticker);
        if (slide != null) slide.cancel();
        if (fade != null) fade.cancel();
    }

    /** Redraws each second for the clock. */
    private final Runnable ticker = new Runnable() {
        @Override
        public void run() {
            invalidate();
            postDelayed(this, 1000 - System.currentTimeMillis() % 1000);
        }
    };

    private void slideTo(float x, float y) {
        float fromX = driftX, fromY = driftY;
        if (slide != null) slide.cancel();
        slide = ValueAnimator.ofFloat(0f, 1f).setDuration(SLIDE_MS);
        slide.setInterpolator(new DecelerateInterpolator());
        slide.addUpdateListener(a -> {
            float f = (float) a.getAnimatedValue();
            driftX = fromX + (x - fromX) * f;
            driftY = fromY + (y - fromY) * f;
            invalidate();
        });
        slide.start();
    }

    /** Fades out, moves to a new spot, fades in. */
    private void jumpClock() {
        if (fade != null) fade.cancel();
        float nx, ny;
        if (logMode) { // the small clock: near an edge or corner
            nx = random.nextBoolean() ? 0.03f + random.nextFloat() * 0.1f : 0.87f + random.nextFloat() * 0.1f;
            ny = random.nextBoolean() ? 0.05f + random.nextFloat() * 0.08f : 0.86f + random.nextFloat() * 0.08f;
        } else { // the big clock with its dates and prayer row: anywhere it fits
            nx = 0.36f + random.nextFloat() * 0.28f;
            ny = 0.28f + random.nextFloat() * 0.26f;
        }
        fade = ValueAnimator.ofFloat(1f, 0f, 1f).setDuration(SLIDE_MS);
        fade.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            boolean moved;

            @Override
            public void onAnimationUpdate(ValueAnimator a) {
                clockAlpha = (float) a.getAnimatedValue();
                if (!moved && a.getAnimatedFraction() >= 0.5f) {
                    moved = true;
                    clockFx = nx;
                    clockFy = ny;
                }
                invalidate();
            }
        });
        fade.start();
    }

    /** The Hijri date and prayer times for today, from the home screen's prayer cache. */
    private void refreshHijri() {
        Context ctx = getContext();
        String zone = LauncherSettings.zone(ctx);
        hijri = PrayerPanel.cachedHijri(ctx, zone, LocalDate.now());
        prayers = PrayerPanel.cachedTimes(ctx, zone, LocalDate.now());
    }

    @Override
    protected void onDraw(Canvas c) {
        super.onDraw(c);
        Calendar now = Calendar.getInstance();
        if (logMode) {
            drawLog(c);
            drawSmallClock(c, now);
        } else {
            drawBigClock(c, now);
        }
    }

    private void drawLog(Canvas c) {
        stepScroll();
        float pad = dp(36), lineH = mono.getFontSpacing();
        float bottom = getHeight() - pad + driftY;
        float x = pad + driftX, y = bottom + scrollBy;
        // Lines glide in from under the block's lower edge rather than over the margin.
        c.save();
        c.clipRect(0, 0, getWidth(), bottom + mono.descent());
        float timeW = mono.measureText("00:00:00  "), levelW = mono.measureText("W  "),
                tagW = mono.measureText("WWWWWWWWWWWWWW  ");
        float msgX = x + timeW + levelW + tagW, maxMsg = getWidth() - pad - msgX;
        java.util.Iterator<LogLine> it = lines.descendingIterator();
        while (it.hasNext() && y > pad) {
            LogLine l = it.next();
            int color = l.level == 'E' || l.level == 'F' || l.level == 'A' ? RED : l.level == 'W' ? AMBER : GREY;
            mono.setColor(DARK);
            c.drawText(l.time, x, y, mono);
            mono.setColor(color);
            c.drawText(String.valueOf(l.level), x + timeW, y, mono);
            String tag = l.tag.length() > 14 ? l.tag.substring(0, 14) : l.tag;
            c.drawText(tag, x + timeW + levelW, y, mono);
            String msg = l.msg.length() > MAX_MSG ? l.msg.substring(0, MAX_MSG) : l.msg;
            int n = mono.breakText(msg, true, Math.max(0, maxMsg), null);
            c.drawText(msg, 0, n, msgX, y, mono);
            y -= lineH;
        }
        c.restore();
    }

    private void drawSmallClock(Canvas c, Calendar now) {
        boolean h24 = LauncherSettings.use24h(getContext());
        String text = String.format(Locale.getDefault(), "%s  ·  %ta %<td %<tb",
                formatTime(now, h24), now);
        float w = clockPaint.measureText(text);
        // Placed by a fixed-width template, so the clock doesn't shuffle as the seconds change.
        float place = clockPaint.measureText(text.replaceAll("\\d", "8"));
        float x = Math.max(dp(24), Math.min(getWidth() - place - dp(24), clockFx * getWidth() - place / 2));
        float y = Math.max(dp(40), Math.min(getHeight() - dp(24), clockFy * getHeight()));
        clockPaint.setAlpha(Math.round(255 * clockAlpha));
        // A black box behind it, so log lines don't run through the clock.
        clockBox.setColor(Color.argb(Math.round(230 * clockAlpha), 0, 0, 0));
        c.drawRect(x - dp(10), y - clockPaint.getTextSize() - dp(4), x + Math.max(w, place) + dp(10), y + dp(10),
                clockBox);
        c.drawText(text, x, y, clockPaint);
    }

    private void drawBigClock(Canvas c, Calendar now) {
        boolean h24 = LauncherSettings.use24h(getContext());
        float cx = clockFx * getWidth(), cy = clockFy * getHeight();
        int a = Math.round(255 * clockAlpha);
        bigTime.setAlpha(a);
        bigDate.setAlpha(a);
        // Left-aligned from where the widest time would start: the digits don't wobble each second.
        String time = formatTime(now, h24);
        c.drawText(time, cx - bigTime.measureText(time.replaceAll("\\d", "8")) / 2, cy, bigTime);
        String date = String.format(Locale.getDefault(), "%tA, %<te %<tB %<tY", now);
        float y = cy + bigDate.getFontSpacing() * 1.4f;
        c.drawText(date, cx, y, bigDate);
        if (hijri != null) {
            y += bigDate.getFontSpacing();
            c.drawText(hijri, cx, y, bigDate);
        }
        if (prayers != null) drawPrayers(c, cx, y + dp(70), h24, a);
    }

    /** Today's five prayers in a row (icon, name, time), the next one in amber like the home screen's card. */
    private void drawPrayers(Canvas c, float cx, float y, boolean h24, int alpha) {
        int next = -1;
        java.time.LocalTime now = java.time.LocalTime.now();
        for (int i = 0; i < prayers.length; i++) {
            if (prayers[i] != null && prayers[i].isAfter(now)) {
                next = i;
                break;
            }
        }
        float col = dp(108), x0 = cx - col * (prayers.length - 1) / 2f;
        for (int i = 0; i < prayers.length; i++) {
            if (prayers[i] == null) continue;
            int color = i == next ? AMBER : DIM;
            prayerName.setColor(color);
            prayerTime.setColor(i == next ? AMBER : GREY);
            prayerName.setAlpha(alpha);
            prayerTime.setAlpha(alpha);
            float x = x0 + col * i;
            android.graphics.drawable.Drawable icon = prayerIcons[i];
            if (icon != null) {
                int size = Math.round(dp(22)), bottom = Math.round(y - prayerName.getTextSize() - dp(8));
                icon.setBounds(Math.round(x - size / 2f), bottom - size, Math.round(x + size / 2f), bottom);
                icon.setTint(color);
                icon.setAlpha(alpha);
                icon.draw(c);
            }
            c.drawText(PrayerPanel.NAMES[i].toUpperCase(Locale.ROOT), x, y, prayerName);
            int h = prayers[i].getHour(), m = prayers[i].getMinute();
            String t = h24 ? String.format(Locale.ROOT, "%02d:%02d", h, m)
                    : String.format(Locale.ROOT, "%d:%02d", h % 12 == 0 ? 12 : h % 12, m);
            c.drawText(t, x, y + prayerTime.getFontSpacing(), prayerTime);
        }
    }

    private static String formatTime(Calendar now, boolean h24) {
        int h = now.get(Calendar.HOUR_OF_DAY), m = now.get(Calendar.MINUTE);
        int sec = now.get(Calendar.SECOND);
        if (h24) return String.format(Locale.ROOT, "%02d:%02d:%02d", h, m, sec);
        int h12 = h % 12 == 0 ? 12 : h % 12;
        return String.format(Locale.ROOT, "%d:%02d:%02d", h12, m, sec);
    }
}
