package my.rafhan.app.tvhome;

import android.accessibilityservice.AccessibilityService;
import android.content.Intent;
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

    /** GLOBAL_ACTION_BACK / GLOBAL_ACTION_HOME. False if the service isn't enabled. */
    static boolean global(int action) {
        RemoteAccessibilityService s = instance;
        return s != null && s.performGlobalAction(action);
    }

    @Override
    protected void onServiceConnected() {
        instance = this;
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
