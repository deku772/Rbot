package app.rbot;

import android.app.Application;
import android.util.Log;

/**
 * Rbot Application class.
 * Initializes chroot tmp directory and AuthManager (Root + Shizuku dual auth).
 */
public class RbotApplication extends Application {

    private static final String TAG = "RbotApplication";

    @Override
    public void onCreate() {
        super.onCreate();
        Log.i(TAG, "Rbot starting (chroot mode)");

        // Initialize AuthManager — detects root/Shizuku and sets execution mode
        AuthManager.getInstance().init(this);

        // Ensure /data/rbot tmp directory exists on host
        // (may use su or Shizuku depending on detected auth mode)
        ChrootManager.execRoot("mkdir -p " + RbotConstants.RBOT_TMP);
    }
}
