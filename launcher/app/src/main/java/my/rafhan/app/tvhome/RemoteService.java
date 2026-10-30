package my.rafhan.app.tvhome;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.IBinder;

/**
 * Keeps the phone remote's HTTP server alive while other apps are on screen. Started by
 * MainActivity (the launcher is started at boot, so no boot receiver is needed).
 */
public class RemoteService extends Service implements LauncherSettings.Listener {
    private static final String CHANNEL = "remote";
    private RemoteServer server;
    private NetBanner netBanner;
    private AlertSources alertSources;
    private Azan azan;
    private SleepTimer sleepTimer;
    private StatusOverlay statusBar;
    // Cleaners (Xiaomi's TV Manager) delete files in apps' shared folders. The dock and the
    // remote's disable log live in private storage; their shared copies (for ADB, restore.sh and
    // the setup app) are written again at start and every hour.
    private static final long SHARED_COPIES_EVERY_MS = 60 * 60_000L;
    private final android.os.Handler main = new android.os.Handler(android.os.Looper.getMainLooper());
    private final Runnable sharedCopies = new Runnable() {
        @Override
        public void run() {
            Context ctx = RemoteService.this;
            Thread t = new Thread(() -> {
                AppManager.syncShared(ctx);
                DockConfig.load(ctx); // writes its shared copy back if it's gone
            }, "shared-copies");
            t.setDaemon(true);
            t.start();
            main.postDelayed(this, SHARED_COPIES_EVERY_MS);
        }
    };

    /** Banners and the PIN card over other apps keep the launcher's proportions (UiScale). */
    @Override
    protected void attachBaseContext(Context base) {
        super.attachBaseContext(UiScale.wrap(base));
    }

    @Override
    public void onCreate() {
        super.onCreate();
        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel(CHANNEL, "Phone remote", NotificationManager.IMPORTANCE_MIN));
        startForeground(1, notification());
        netBanner = new NetBanner(this);
        netBanner.start();
        sleepTimer = new SleepTimer(this, netBanner);
        // Settings → Phone remote can switch the server off; the banner and alerts keep running.
        if (LauncherSettings.flag(this, LauncherSettings.REMOTE)) startServer();
        LauncherSettings.addListener(this);
        alertSources = new AlertSources(this, netBanner);
        alertSources.start();
        azan = new Azan(this, netBanner);
        azan.start();
        statusBar = new StatusOverlay(this);
        statusBar.start();
        main.post(sharedCopies);
    }

    private Notification notification() {
        boolean on = LauncherSettings.flag(this, LauncherSettings.REMOTE);
        return new Notification.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.ic_home)
                .setContentTitle("Phone remote")
                .setContentText(on ? "Port " + RemoteServer.PORT : "Off")
                .build();
    }

    private void startServer() {
        server = new RemoteServer(this, sleepTimer);
        server.start();
    }

    @Override
    public void onSettingChanged(String key) {
        if (!LauncherSettings.REMOTE.equals(key)) return;
        boolean on = LauncherSettings.flag(this, LauncherSettings.REMOTE);
        if (on && server == null) startServer();
        if (!on && server != null) {
            server.stop();
            server = null;
        }
        updateNotification();
    }

    /**
     * The service's own notification (the one startForeground shows). On Android 13+ a plain
     * notify() needs POST_NOTIFICATIONS; without the grant it would be dropped anyway, so skip it.
     */
    private void updateNotification() {
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            return;
        }
        getSystemService(NotificationManager.class).notify(1, notification());
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY;
    }

    @Override
    public void onTrimMemory(int level) {
        super.onTrimMemory(level);
        if (level >= TRIM_MEMORY_UI_HIDDEN && server != null) server.trim();
    }

    @Override
    public void onDestroy() {
        main.removeCallbacks(sharedCopies);
        LauncherSettings.removeListener(this);
        if (server != null) server.stop();
        if (netBanner != null) netBanner.stop();
        if (alertSources != null) alertSources.stop();
        if (azan != null) azan.stop();
        if (sleepTimer != null) sleepTimer.stop();
        if (statusBar != null) statusBar.stop();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
