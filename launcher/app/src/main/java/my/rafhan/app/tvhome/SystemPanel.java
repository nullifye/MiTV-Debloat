package my.rafhan.app.tvhome;

import android.content.Context;
import android.content.res.ColorStateList;
import android.database.ContentObserver;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.net.Network;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.io.BufferedReader;
import java.io.FileReader;
import java.util.Locale;
import java.util.concurrent.Executor;

/**
 * The techie card under the pills: CPU clock, RAM, storage, uptime, IP, Wi-Fi signal,
 * Private DNS and USB debugging.
 *
 * What an app can read on this firmware decides what is shown: SELinux blocks /proc/stat,
 * /proc/loadavg and the thermal zone, and the TV has no thermal HAL, so there is no true CPU %
 * or temperature. The per-core clock (ondemand governor) is shown instead.
 *
 * Only CPU/RAM/uptime are sampled (every 2 s, and only while the home screen is on screen).
 * Network and USB-debugging values are event driven.
 */
final class SystemPanel {
    private static final int TEXT = 0xFFFFFFFF, DIM = 0x8CFFFFFF, ICON = 0xA6FFFFFF;
    private static final int GOOD = 0xFF63D489, WARN = 0xFFFFB340, BAD = 0xFFFF6B5E;
    private static final long TICK_MS = 5000;

    private final Context ctx;
    private final float dp;
    private final Executor worker;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Cell cpu, ram, disk, uptime, ip, signal, dns, adb;
    private final int cores;
    private final long maxKhz;
    private final ContentObserver adbObserver;
    private boolean resumed, visible = true, ticking;
    private int ticks;

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            refreshFast();
            if (ticks++ % 6 == 0) refreshDisk(); // every 30 s
            main.postDelayed(this, TICK_MS);
        }
    };

    SystemPanel(Context ctx, LinearLayout root, Executor worker) {
        this.ctx = ctx;
        this.worker = worker;
        dp = ctx.getResources().getDisplayMetrics().density;
        cores = Runtime.getRuntime().availableProcessors();
        maxKhz = readLong("/sys/devices/system/cpu/cpu0/cpufreq/cpuinfo_max_freq");

        LinearLayout header = new LinearLayout(ctx);
        header.setGravity(Gravity.CENTER_VERTICAL);
        // A small icon before the title, like the other cards' (the TV: this card is about it).
        ImageView mark = new ImageView(ctx);
        mark.setImageResource(R.drawable.ic_tv);
        mark.setImageTintList(ColorStateList.valueOf(DIM));
        LinearLayout.LayoutParams mlp = new LinearLayout.LayoutParams(px(12), px(12));
        mlp.setMarginEnd(px(5));
        header.addView(mark, mlp);
        TextView title = small("SYSTEM", DIM);
        header.addView(title);
        TextView device = small(Build.MODEL + " · Android " + Build.VERSION.RELEASE
                + " · " + cores + "×" + (maxKhz > 0 ? fmt1(maxKhz / 1e6) + "GHz" : "CPU"), DIM);
        device.setGravity(Gravity.END);
        device.setLetterSpacing(0.02f);
        header.addView(device, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        root.addView(header);

        LinearLayout row1 = row(root, 10), row2 = row(root, 12);
        cpu = new Cell(row1, R.drawable.ic_cpu, false, 1f);
        ram = new Cell(row1, R.drawable.ic_ram, true, 1f);
        disk = new Cell(row1, R.drawable.ic_storage, true, 1f);
        uptime = new Cell(row1, R.drawable.ic_uptime, false, 1f);
        ip = new Cell(row2, R.drawable.ic_ip, false, 0f);
        signal = new Cell(row2, R.drawable.ic_signal, false, 1f);
        dns = new Cell(row2, R.drawable.ic_dns, false, 1f);
        adb = new Cell(row2, R.drawable.ic_usb, false, 1f);

        adbObserver = new ContentObserver(main) {
            @Override
            public void onChange(boolean selfChange) {
                refreshAdb();
            }
        };
    }

    void onResume() {
        resumed = true;
        ctx.getContentResolver().registerContentObserver(
                Settings.Global.getUriFor(Settings.Global.ADB_ENABLED), false, adbObserver);
        refreshAdb();
        refreshDisk();
        refreshNetwork();
        updateTicking();
    }

    void onPause() {
        resumed = false;
        ctx.getContentResolver().unregisterContentObserver(adbObserver);
        updateTicking();
    }

    /** Hidden while the app grid has the screen: stop sampling then. */
    void setVisible(boolean v) {
        visible = v;
        updateTicking();
    }

    private void updateTicking() {
        boolean want = resumed && visible;
        if (want == ticking) return;
        ticking = want;
        main.removeCallbacks(tick);
        if (want) main.post(tick);
    }

    // ---------------------------------------------------------------- sampled

    private void refreshFast() {
        double[] clock = SysInfo.cpu(cores);
        int online = (int) clock[1];
        if (online > 0) {
            cpu.set(fmt2(clock[0]) + " GHz", "CPU · " + online + " CORE", TEXT, -1);
        } else {
            cpu.set("—", "CPU", TEXT, -1);
        }

        long[] mem = SysInfo.ram(ctx); // installed RAM; the firmware's video reserve counts as used
        long used = mem[0], total = mem[1];
        float f = total > 0 ? used / (float) total : 0;
        ram.set(Math.round(f * 100) + "%", "RAM " + gb(used) + "/" + gbRound(total / 1073741824.0) + "G", TEXT, f);

        long s = SystemClock.elapsedRealtime() / 60000;
        long d = s / 1440, h = (s / 60) % 24, m = s % 60;
        uptime.set(d > 0 ? d + "d " + h + "h" : h + "h " + m + "m", "UPTIME", TEXT, -1);
    }

    /** The whole storage chip, as Settings shows it (system partitions count as used); decimal GB. */
    private void refreshDisk() {
        long[] d = SysInfo.disk(ctx);
        long used = d[0], total = d[1];
        float f = total > 0 ? used / (float) total : 0;
        disk.set(Math.round(f * 100) + "%", "DISK " + fmt1(used / 1e9) + "/" + gbRound(total / 1e9) + "G", TEXT, f);
    }

    // ---------------------------------------------------------------- event driven

    void refreshNetwork() {
        SysInfo.Net n = SysInfo.network(ctx);
        // The Wi-Fi button is icon-only, so the network's name lives here.
        String where = n.ethernet ? "LAN" : n.wifi && n.name != null ? n.name : n.wifi ? "WI-FI" : "OFFLINE";
        ip.set(n.ip != null ? n.ip : "—", "IP · " + where, n.ip != null ? TEXT : WARN, -1);
        if (n.wifi) {
            int color = n.rssi >= -65 ? TEXT : n.rssi >= -75 ? WARN : BAD;
            signal.set(n.rssi > -127 ? n.rssi + " dBm" : "—", n.linkMbps > 0 ? n.linkMbps + " MBPS" : "SIGNAL", color, -1);
        } else {
            signal.set(n.ethernet ? "Wired" : "—", "LINK", TEXT, -1);
        }
        if (n.dnsHost != null) {
            dns.set(n.dnsHost.contains("adguard") ? "AdGuard" : n.dnsHost, "DNS · DoT", GOOD, -1);
        } else if (n.dnsAuto) {
            dns.set("Auto", "DNS · TLS", TEXT, -1);
        } else {
            dns.set("System", "DNS", TEXT, -1);
        }
    }

    private void refreshAdb() {
        if (!SysInfo.adbOn(ctx)) {
            adb.set("OFF", "USB DEBUG", GOOD, -1);
            return;
        }
        adb.set("ON", "USB DEBUG", WARN, -1);
        // Network ADB port: a system property, readable through getprop. Off the main thread.
        worker.execute(() -> {
            String port = SysInfo.adbPort();
            main.post(() -> {
                adb.set(!port.isEmpty() ? "ON :" + port : "ON", "USB DEBUG", WARN, -1);
            });
        });
    }

    // ---------------------------------------------------------------- helpers

    private static long readLong(String path) {
        try (BufferedReader r = new BufferedReader(new FileReader(path))) {
            return Long.parseLong(r.readLine().trim());
        } catch (Exception e) {
            return -1;
        }
    }

    private static String gb(long bytes) {
        return fmt1(bytes / 1073741824.0);
    }

    /** "2" / "16" for whole sizes, "1.5" otherwise. */
    private static String gbRound(double g) {
        return Math.abs(g - Math.round(g)) < 0.05 ? String.valueOf(Math.round(g)) : fmt1(g);
    }

    private static String fmt1(double v) {
        return String.format(Locale.US, "%.1f", v);
    }

    private static String fmt2(double v) {
        return String.format(Locale.US, "%.2f", v);
    }

    private int px(float dps) {
        return Math.round(dps * dp);
    }

    private TextView small(String text, int color) {
        TextView t = new TextView(ctx);
        t.setText(text);
        t.setTextSize(10.5f);
        t.setTextColor(color);
        t.setLetterSpacing(0.1f);
        t.setTypeface(Fonts.outfit(ctx, 500));
        t.setSingleLine(true);
        t.setEllipsize(TextUtils.TruncateAt.END);
        return t;
    }

    private LinearLayout row(LinearLayout root, int topMargin) {
        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = px(topMargin);
        root.addView(row, lp);
        return row;
    }

    private final class Cell {
        final ImageView icon;
        final TextView value, label;
        final Bar bar;

        Cell(LinearLayout row, int iconRes, boolean withBar, float weight) {
            LinearLayout cell = new LinearLayout(ctx);
            cell.setOrientation(LinearLayout.HORIZONTAL);
            // weight 0: as wide as its text (the IP address), plus a fixed gap.
            LinearLayout.LayoutParams clp = weight > 0
                    ? new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, weight)
                    : new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            if (weight <= 0) clp.setMarginEnd(px(14));
            row.addView(cell, clp);

            icon = new ImageView(ctx);
            icon.setImageResource(iconRes);
            icon.setImageTintList(ColorStateList.valueOf(ICON));
            LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(px(16), px(16));
            ilp.topMargin = px(1);
            ilp.setMarginEnd(px(6));
            cell.addView(icon, ilp);

            LinearLayout col = new LinearLayout(ctx);
            col.setOrientation(LinearLayout.VERTICAL);
            cell.addView(col, weight > 0
                    ? new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                    : new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

            value = new TextView(ctx);
            value.setTextSize(14f);
            value.setTypeface(Fonts.outfit(ctx, 500));
            value.setTextColor(TEXT);
            value.setSingleLine(true);
            value.setEllipsize(TextUtils.TruncateAt.END);
            value.setIncludeFontPadding(false);
            // Explicit WRAP_CONTENT: the vertical default (MATCH_PARENT) would squeeze it to the label's width.
            col.addView(value, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

            label = small("", DIM);
            label.setTextSize(8.5f);
            label.setLetterSpacing(0.06f);
            LinearLayout.LayoutParams llp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            llp.topMargin = px(2);
            col.addView(label, llp);

            if (withBar) {
                bar = new Bar(ctx);
                LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, px(3));
                blp.setMarginEnd(px(10));
                blp.topMargin = px(5);
                col.addView(bar, blp);
            } else {
                bar = null;
            }
        }

        /** {@code fraction} < 0: no bar. Bars turn amber over 80 %, red over 92 %. */
        void set(String v, String l, int color, float fraction) {
            if (!v.contentEquals(value.getText())) value.setText(v);
            if (!l.contentEquals(label.getText())) label.setText(l);
            int c = color;
            if (fraction >= 0) c = fraction > 0.92f ? BAD : fraction > 0.8f ? WARN : TEXT;
            value.setTextColor(c);
            icon.setImageTintList(ColorStateList.valueOf(c == TEXT ? ICON : c));
            if (bar != null && fraction >= 0) bar.set(fraction, c == TEXT ? 0xB3FFFFFF : c);
        }
    }

    private static final class Bar extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF r = new RectF();
        private float fraction;
        private int color = 0xB3FFFFFF;

        Bar(Context c) {
            super(c);
        }

        void set(float f, int c) {
            f = Math.max(0f, Math.min(1f, f));
            if (Math.abs(f - fraction) < 0.002f && c == color) return;
            fraction = f;
            color = c;
            invalidate();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            float h = getHeight(), rad = h / 2f;
            r.set(0, 0, getWidth(), h);
            paint.setColor(0x26FFFFFF);
            canvas.drawRoundRect(r, rad, rad, paint);
            r.right = getWidth() * fraction;
            paint.setColor(color);
            canvas.drawRoundRect(r, rad, rad, paint);
        }
    }
}
