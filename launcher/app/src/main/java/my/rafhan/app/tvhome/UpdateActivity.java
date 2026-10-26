package my.rafhan.app.tvhome;

import android.app.Activity;
import android.app.PendingIntent;
import android.content.ActivityNotFoundException;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageInstaller;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.core.content.ContextCompat;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;

/**
 * "TV Home x.y is available" (the bell): downloads the release APK from GitHub and hands it to
 * Android's installer (a PackageInstaller session), which asks the user to confirm. Android
 * accepts it only if it's signed with the installed app's key, so settings are kept and nothing
 * else can be slipped in. Installing replaces this app, so the process ends and Home comes back
 * with the new version.
 */
public class UpdateActivity extends Activity {
    static final String EXTRA_VERSION = "version", EXTRA_URL = "url", EXTRA_SIZE = "size", EXTRA_SHA = "sha256";
    private static final String ACTION_STATUS = "my.rafhan.app.tvhome.UPDATE_STATUS";

    private TextView title, status, primary, cancel;
    private String version, url, sha;
    private long size;
    private volatile boolean cancelled;
    private boolean started, waitingForPermission;

    static Intent intent(Context ctx, UpdateCheck.Release r) {
        return new Intent(ctx, UpdateActivity.class)
                .putExtra(EXTRA_VERSION, r.version).putExtra(EXTRA_URL, r.url)
                .putExtra(EXTRA_SIZE, r.size).putExtra(EXTRA_SHA, r.sha256);
    }

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        Intent in = getIntent();
        version = in.getStringExtra(EXTRA_VERSION);
        url = in.getStringExtra(EXTRA_URL);
        size = in.getLongExtra(EXTRA_SIZE, 0);
        sha = in.getStringExtra(EXTRA_SHA);
        buildCard();
        ContextCompat.registerReceiver(this, statusReceiver, new IntentFilter(ACTION_STATUS),
                ContextCompat.RECEIVER_NOT_EXPORTED);
        if (url == null || version == null) {
            fail("There's nothing to update.");
        } else {
            begin();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Back from the "install unknown apps" setting: carry on if it was allowed.
        if (waitingForPermission && getPackageManager().canRequestPackageInstalls()) begin();
    }

    private void begin() {
        if (started) return;
        if (!getPackageManager().canRequestPackageInstalls()) {
            waitingForPermission = true;
            show("TV Home needs your OK to install its own updates (once). In the next screen, "
                    + "switch on \"TV Home\" (Allow from this source), then press Back.");
            button(primary, "Open settings", this::openUnknownSources);
            return;
        }
        waitingForPermission = false;
        started = true;
        primary.setVisibility(View.GONE);
        button(cancel, "Cancel", this::finish);
        cancel.requestFocus();
        show("Downloading…");
        Thread t = new Thread(this::download, "update-download");
        t.setDaemon(true);
        t.start();
    }

    @Override
    protected void onDestroy() {
        cancelled = true;
        try {
            unregisterReceiver(statusReceiver);
        } catch (RuntimeException ignored) {
        }
        super.onDestroy();
    }

    // ---------------------------------------------------------------- download and install

    private void download() {
        File apk = new File(getCacheDir(), "update.apk");
        try {
            HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
            c.setConnectTimeout(20_000);
            c.setReadTimeout(30_000);
            c.setRequestProperty("User-Agent", "tvhome");
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            long got = 0;
            try {
                if (c.getResponseCode() != 200) throw new IllegalStateException("HTTP " + c.getResponseCode());
                long total = size > 0 ? size : c.getContentLengthLong();
                int lastPct = -1;
                try (InputStream in = c.getInputStream(); OutputStream out = new FileOutputStream(apk)) {
                    byte[] buf = new byte[32 * 1024];
                    for (int r; (r = in.read(buf)) > 0; ) {
                        if (cancelled) return;
                        out.write(buf, 0, r);
                        md.update(buf, 0, r);
                        got += r;
                        int pct = total > 0 ? (int) (got * 100 / total) : -1;
                        if (pct != lastPct) {
                            lastPct = pct;
                            runOnUiThread(() -> show(pct >= 0 ? "Downloading…  " + pct + " %" : "Downloading…"));
                        }
                    }
                }
            } finally {
                c.disconnect();
            }
            if (size > 0 && got != size) throw new IllegalStateException("the download was incomplete");
            if (sha != null && !sha.equals(hex(md.digest()))) throw new IllegalStateException("the download was damaged");
            runOnUiThread(() -> show("Installing…"));
            install(apk);
        } catch (Exception e) {
            apk.delete();
            if (!cancelled) runOnUiThread(() -> fail("The download didn't work. Check the TV is online and try again."));
        }
    }

    private void install(File apk) throws Exception {
        PackageInstaller pi = getPackageManager().getPackageInstaller();
        PackageInstaller.SessionParams params = new PackageInstaller.SessionParams(
                PackageInstaller.SessionParams.MODE_FULL_INSTALL);
        params.setAppPackageName(getPackageName());
        params.setSize(apk.length());
        int id = pi.createSession(params);
        try (PackageInstaller.Session s = pi.openSession(id)) {
            try (InputStream in = new FileInputStream(apk); OutputStream out = s.openWrite("base.apk", 0, apk.length())) {
                byte[] buf = new byte[64 * 1024];
                for (int r; (r = in.read(buf)) > 0; ) out.write(buf, 0, r);
                s.fsync(out);
            }
            Intent cb = new Intent(ACTION_STATUS).setPackage(getPackageName());
            PendingIntent sender = PendingIntent.getBroadcast(this, id, cb,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_MUTABLE);
            s.commit(sender.getIntentSender());
        }
        apk.delete();
    }

    private final BroadcastReceiver statusReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent i) {
            int st = i.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE);
            String msg = String.valueOf(i.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE));
            switch (st) {
                case PackageInstaller.STATUS_PENDING_USER_ACTION:
                    Intent confirm = i.getParcelableExtra(Intent.EXTRA_INTENT);
                    show("Choose Update on the next screen.");
                    try {
                        if (confirm != null) startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                    } catch (ActivityNotFoundException | SecurityException e) {
                        fail("This TV can't install updates itself. Update TV Home with the setup app on a computer.");
                    }
                    break;
                case PackageInstaller.STATUS_SUCCESS:
                    show("Updated. TV Home restarts in a moment.");
                    break;
                case PackageInstaller.STATUS_FAILURE_ABORTED:
                    fail("Update cancelled. Open the notification again any time.");
                    break;
                case PackageInstaller.STATUS_FAILURE_STORAGE:
                    fail("The TV is out of space. Remove an app you don't need and try again.");
                    break;
                case PackageInstaller.STATUS_FAILURE_CONFLICT:
                case PackageInstaller.STATUS_FAILURE_INCOMPATIBLE:
                    fail(msg.contains("UPDATE_INCOMPATIBLE") || msg.contains("signatures")
                            ? "This TV Home was installed from a computer build, so it can't take this update. "
                              + "Update it with the setup app on the computer."
                            : "The update couldn't be installed: " + msg);
                    break;
                default:
                    fail("The update couldn't be installed: " + msg);
            }
        }
    };

    private void openUnknownSources() {
        Intent[] tries = {
                new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + getPackageName())),
                new Intent(Settings.ACTION_SECURITY_SETTINGS),
                new Intent(Settings.ACTION_SETTINGS),
        };
        for (Intent t : tries) {
            try {
                startActivity(t);
                return;
            } catch (ActivityNotFoundException | SecurityException ignored) {
            }
        }
    }

    private static String hex(byte[] b) {
        StringBuilder sb = new StringBuilder();
        for (byte x : b) sb.append(String.format("%02x", x));
        return sb.toString();
    }

    // ---------------------------------------------------------------- the card

    private void buildCard() {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(0x99000000);
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(px(36), px(30), px(36), px(28));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xF01C1C20);
        bg.setStroke(px(1), 0x29FFFFFF);
        bg.setCornerRadius(px(24));
        card.setBackground(bg);

        title = text(24, 0xFFFFFFFF, 600);
        title.setText("Updating TV Home" + (version != null ? " to " + version : ""));
        status = text(17, 0xCCFFFFFF, 400);
        status.setPadding(0, px(12), 0, px(22));
        LinearLayout buttons = new LinearLayout(this);
        buttons.setGravity(Gravity.END);
        primary = text(16, 0xFFFFFFFF, 500);
        primary.setVisibility(View.GONE);
        cancel = text(16, 0xFFFFFFFF, 500);
        button(cancel, "Cancel", this::finish);
        buttons.addView(primary);
        buttons.addView(cancel);
        card.addView(title);
        card.addView(status);
        card.addView(buttons);

        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(px(620), ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER);
        root.addView(card, lp);
        setContentView(root);
        cancel.requestFocus();
    }

    private TextView text(int sp, int color, int weight) {
        TextView t = new TextView(this);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        t.setTypeface(Fonts.outfit(this, weight));
        return t;
    }

    /** A pill button: lit while focused, OK (or a click) runs {@code action}. */
    private void button(TextView b, String label, Runnable action) {
        b.setText(label);
        b.setVisibility(View.VISIBLE);
        b.setFocusable(true);
        b.setPadding(px(22), px(10), px(22), px(10));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMarginStart(px(12));
        b.setLayoutParams(lp);
        GradientDrawable off = new GradientDrawable(), on = new GradientDrawable();
        off.setColor(0x1FFFFFFF);
        off.setCornerRadius(px(22));
        on.setColor(0xFFFFFFFF);
        on.setCornerRadius(px(22));
        b.setBackground(b.isFocused() ? on : off);
        b.setTextColor(b.isFocused() ? 0xFF000000 : 0xFFFFFFFF);
        b.setOnFocusChangeListener((v, has) -> {
            v.setBackground(has ? on : off);
            ((TextView) v).setTextColor(has ? 0xFF000000 : 0xFFFFFFFF);
        });
        b.setOnClickListener(v -> action.run());
        if (b == primary) b.requestFocus();
    }

    private void show(String s) {
        status.setText(s);
    }

    private void fail(String s) {
        show(s);
        started = false;
        if (url != null && !waitingForPermission) {
            button(primary, "Try again", this::begin);
        }
        button(cancel, "Close", this::finish);
    }

    private int px(int dp) {
        return Math.round(dp * getResources().getDisplayMetrics().density);
    }
}
