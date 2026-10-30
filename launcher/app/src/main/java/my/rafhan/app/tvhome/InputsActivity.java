package my.rafhan.app.tvhome;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.os.Bundle;

/**
 * The remote's Input button: opened by RemoteAccessibilityService (which catches the key), and
 * for Android TV's "show inputs" (com.android.tv.action.VIEW_INPUTS) when it's asked for without
 * naming the stock launcher. Opens the TV's own input picker when it has one (PatchWall's, on
 * Xiaomi), else TV Home's Inputs menu on the home screen. No window of its own: it hands over
 * and finishes.
 */
public class InputsActivity extends Activity {
    static final String PATCHWALL_INPUTS = "com.mitv.tvhome.atv.app.tv.INPUTSOURCE_POPUP";

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        Intent popup = new Intent(PATCHWALL_INPUTS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        boolean shown = false;
        if (popup.resolveActivity(getPackageManager()) != null) {
            try {
                startActivity(popup);
                shown = true;
            } catch (ActivityNotFoundException | SecurityException ignored) { // disabled meanwhile
            }
        }
        if (!shown) {
            startActivity(new Intent(this, MainActivity.class).setAction(MainActivity.ACTION_INPUTS)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP));
        }
        finish();
    }
}
