package app.rbot;

import android.util.Log;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Global operation log — all app-initiated actions (install, start, SSH, etc.)
 * are recorded here so LogActivity can display them alongside bot runtime logs.
 *
 * Uses root to write since the log file lives under /data/rbot (root:root 700).
 */
public final class OpLog {

    private static final String TAG = "OpLog";

    private OpLog() {}

    private static final SimpleDateFormat TIMESTAMP_FMT =
        new SimpleDateFormat("HH:mm:ss", Locale.getDefault());

    /** Append a timestamped line to the operation log */
    public static void log(String message) {
        String ts = TIMESTAMP_FMT.format(new Date());
        String line = "[" + ts + "] " + message;
        Log.d(TAG, line);

        // Write via root (file is under /data/rbot, app process can't write directly)
        // Use >> to append, create if not exists
        ChrootManager.execRoot(
            "echo '" + line.replace("'", "'\\''") + "' >> " + RbotConstants.OP_LOG_FILE, 3);
    }

    /** Append a progress line (no separate timestamp — caller provides context) */
    public static void progress(String message) {
        Log.d(TAG, message);
        ChrootManager.execRoot(
            "echo '  " + message.replace("'", "'\\''") + "' >> " + RbotConstants.OP_LOG_FILE, 3);
    }

    /** Append an error line */
    public static void error(String message) {
        String ts = TIMESTAMP_FMT.format(new Date());
        String line = "[" + ts + "] ❌ " + message;
        Log.w(TAG, line);
        ChrootManager.execRoot(
            "echo '" + line.replace("'", "'\\''") + "' >> " + RbotConstants.OP_LOG_FILE, 3);
    }

    /** Read the last N lines of the operation log */
    public static String readTail(int lines) {
        ChrootManager.CommandResult result = ChrootManager.execRoot(
            "tail -n " + lines + " '" + RbotConstants.OP_LOG_FILE + "' 2>/dev/null", 5);
        return result.success() ? result.stdout() : "";
    }

    /** Clear the operation log */
    public static void clear() {
        ChrootManager.execRoot("rm -f " + RbotConstants.OP_LOG_FILE, 3);
    }
}
