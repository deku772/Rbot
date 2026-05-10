package app.rbot;

import android.app.Application;
import android.util.Log;

/**
 * Rbot Application class.
 * Lightweight — no bootstrap, no proot, no Shizuku.
 * Just initializes the chroot tmp directory.
 */
public class RbotApplication extends Application {

    private static final String TAG = "RbotApplication";

    @Override
    public void onCreate() {
        super.onCreate();
        Log.i(TAG, "Rbot starting (chroot mode)");

        // Ensure /data/rbot tmp directory exists on host
        ChrootManager.execRoot("mkdir -p " + RbotConstants.RBOT_TMP);
    }
}
