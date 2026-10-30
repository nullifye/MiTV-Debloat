package my.rafhan.app.tvhome;

import android.Manifest;
import android.accessibilityservice.AccessibilityService;
import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.KeyEvent;
import android.view.accessibility.AccessibilityEvent;

/**
 * Gives the phone remote Back and Home inside any app (performGlobalAction), and makes the
 * remote's Input button work: the TV's hotkey dispatcher opens one named screen of the stock
 * launcher for it (com.google.android.tvlauncher/.inputs.InputsPanelActivity), which is
 * disabled once TV Home replaces it, so the button did nothing. This catches that one key first
 * and opens InputsActivity instead; every other key passes through untouched. It doesn't read
 * window content (canRetrieveWindowContent=false) and ignores events.
 * Enabled by build-and-install.sh, next to Button Mapper's service.
 */
public class RemoteAccessibilityService extends AccessibilityService {
    private static volatile RemoteAccessibilityService instance;

    static boolean isOn() {
        return instance != null;
    }

    private static boolean checkScheduled, reconnected;

    /**
     * reconnect() if it isn't connected, once per process start, 10 s from now (time for Android
     * to connect it by itself first). Its own Handler: MainActivity clears its queue when it's
     * destroyed, which an update can do.
     */
    static synchronized void checkSoon(Context ctx) {
        if (checkScheduled) return;
        checkScheduled = true;
        Context app = ctx.getApplicationContext();
        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            if (!isOn()) reconnect(app);
        }, 10_000);
    }

    /**
     * Switched on in Settings but not connected, or connected without the abilities this version
     * asks for (an update that adds one, like catching the Input key: Android keeps the old ones
     * until the service is switched off and on): takes it off the list and puts it back a second
     * later, which reconnects it with its current abilities. The other services stay on
     * throughout. Needs WRITE_SECURE_SETTINGS (given for the screen saver); switched off by the
     * user: left off. Once per process, so it can't loop.
     */
    static synchronized void reconnect(Context ctx) {
        if (reconnected || ctx.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS)
                != PackageManager.PERMISSION_GRANTED) {
            return;
        }
        ContentResolver cr = ctx.getContentResolver();
        String all = Settings.Secure.getString(cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        if (all == null) return;
        ComponentName ours = new ComponentName(ctx, RemoteAccessibilityService.class);
        StringBuilder others = new StringBuilder();
        boolean listed = false;
        for (String s : all.split(":")) {
            ComponentName c = ComponentName.unflattenFromString(s);
            if (ours.equals(c)) {
                listed = true;
            } else if (!s.isEmpty()) {
                if (others.length() > 0) others.append(':');
                others.append(s);
            }
        }
        if (!listed) return;
        reconnected = true;
        try {
            Settings.Secure.putString(cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, others.toString());
            new Handler(Looper.getMainLooper()).postDelayed(() -> {
                try {
                    Settings.Secure.putString(cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, all);
                } catch (SecurityException ignored) {
                }
            }, 1_000);
            android.util.Log.i("TvHome", "accessibility service: reconnecting it");
        } catch (SecurityException ignored) { // the permission was taken away meanwhile
        }
    }

    /** GLOBAL_ACTION_BACK / GLOBAL_ACTION_HOME. False if the service isn't enabled. */
    static boolean global(int action) {
        RemoteAccessibilityService s = instance;
        return s != null && s.performGlobalAction(action);
    }

    @Override
    protected void onServiceConnected() {
        instance = this;
        // Connected with an older version's abilities (no key filtering): reconnect once.
        android.accessibilityservice.AccessibilityServiceInfo info = getServiceInfo();
        if (info != null && (info.getCapabilities()
                & android.accessibilityservice.AccessibilityServiceInfo.CAPABILITY_CAN_REQUEST_FILTER_KEY_EVENTS) == 0) {
            reconnect(this);
        }
    }

    @Override
    public boolean onUnbind(Intent intent) {
        instance = null;
        return super.onUnbind(intent);
    }

    @Override
    public void onDestroy() {
        instance = null;
        super.onDestroy();
    }

    /** The Input button: taken (down and up), and the picker opened on its release. */
    @Override
    protected boolean onKeyEvent(KeyEvent e) {
        if (e.getKeyCode() != KeyEvent.KEYCODE_TV_INPUT) return false;
        if (e.getAction() == KeyEvent.ACTION_UP) {
            try {
                startActivity(new Intent(this, InputsActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            } catch (RuntimeException ignored) { // can't open from here: the key is lost, as before
            }
        }
        return true;
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
    }

    @Override
    public void onInterrupt() {
    }
}
