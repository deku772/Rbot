package app.andbott;

import android.content.BroadcastReceiver;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

/**
 * BroadcastReceiver that sets the system clipboard from a shell broadcast.
 * Usage: am broadcast -a app.andbott.SET_CLIPBOARD --es text "你好世界"
 */
public class ClipboardReceiver extends BroadcastReceiver {

    private static final String TAG = "ClipboardReceiver";
    public static final String ACTION_SET_CLIPBOARD = "app.andbott.SET_CLIPBOARD";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || !ACTION_SET_CLIPBOARD.equals(intent.getAction())) return;

        final String text = intent.getStringExtra("text");
        if (text == null) {
            Log.w(TAG, "SET_CLIPBOARD received but 'text' extra is missing");
            return;
        }

        new Handler(Looper.getMainLooper()).post(() -> {
            try {
                ClipboardManager cm = (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
                if (cm == null) return;
                ClipData clip = ClipData.newPlainText("botdrop", text);
                cm.setPrimaryClip(clip);
            } catch (Exception e) {
                Log.e(TAG, "Failed to set clipboard: " + e.getMessage());
            }
        });
    }
}
