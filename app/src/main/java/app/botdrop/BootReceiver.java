package app.botdrop;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.util.Log;

/**
 * Receiver that starts the GatewayMonitorService on device boot.
 * This ensures AstrBot auto-starts after reboot if it was running before.
 */
public class BootReceiver extends BroadcastReceiver {

    private static final String TAG = "BootReceiver";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (!Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) {
            return;
        }

        // Only auto-start if AstrBot is installed
        if (!ChrootManager.isAstrBotInstalled()) {
            Log.i(TAG, "AstrBot not installed, skipping auto-start");
            return;
        }

        Log.i(TAG, "Boot completed, starting GatewayMonitorService");
        Intent serviceIntent = new Intent(context, GatewayMonitorService.class);
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(serviceIntent);
            } else {
                context.startService(serviceIntent);
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to start GatewayMonitorService on boot: " + e.getMessage());
        }
    }
}
