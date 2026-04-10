package app.botdrop;

import android.app.Application;
import android.util.Log;

/**
 * BotDrop Application class.
 * Lightweight replacement for TermuxApplication — no bootstrap,
 * no proot setup, no Shizuku. Just app initialization.
 */
public class BotDropApplication extends Application {

    private static final String TAG = "BotDropApplication";

    @Override
    public void onCreate() {
        super.onCreate();
        Log.i(TAG, "BotDrop starting (chroot mode)");

        // Ensure /data/botdrop tmp directory exists on host
        ChrootManager.execRoot("mkdir -p " + BotDropConstants.BOTDROP_TMP);
    }
}
