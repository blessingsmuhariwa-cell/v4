package com.voltoverlay;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.provider.Settings;

/**
 * Starts OverlayService automatically after device boot when overlay permission
 * is granted and the user did not explicitly stop the overlay.
 */
public class BootReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        if (context == null || intent == null) {
            return;
        }
        String action = intent.getAction();
        if (!Intent.ACTION_BOOT_COMPLETED.equals(action)
                && !Intent.ACTION_LOCKED_BOOT_COMPLETED.equals(action)) {
            return;
        }

        SharedPreferences prefs = context.getSharedPreferences("gauge", Context.MODE_PRIVATE);
        boolean stoppedByUser = prefs.getBoolean("user_stopped", false);
        if (stoppedByUser || !Settings.canDrawOverlays(context)) {
            return;
        }

        Intent serviceIntent = new Intent(context, OverlayService.class);
        context.startForegroundService(serviceIntent);
    }
}
