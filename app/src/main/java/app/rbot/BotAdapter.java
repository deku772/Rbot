package app.rbot;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

/**
 * Abstract base class for bot engine adapters.
 * Each supported bot (AstrBot, Hermes Agent) implements this interface.
 *
 * Design: Abstract class instead of interface to provide common infrastructure
 * (Handler, Context) and avoid duplicate boilerplate across implementations.
 */
public abstract class BotAdapter {

    public static final String ID_ASTRBOT = "astrbot";
    public static final String ID_HERMES = "hermes";

    protected final Context mContext;
    protected final Handler mHandler;

    protected BotAdapter(Context context) {
        mContext = context.getApplicationContext();
        mHandler = new Handler(Looper.getMainLooper());
    }

    // ─── Identity ───

    /** Unique string ID for this bot (e.g. "astrbot", "hermes") */
    public abstract String getId();

    /** Human-readable display name (e.g. "AstrBot", "Hermes Agent") */
    public abstract String getName();

    /** Human-readable short label for status cards (e.g. "ASTRBOT", "HERMES") */
    public abstract String getStatusLabel();

    // ─── Installation ───

    /** True if this bot is installed in the chroot environment */
    public abstract boolean isInstalled();

    /** True if this bot is currently running */
    public abstract boolean isRunning();

    /**
     * Install this bot inside the chroot.
     * Called on a background thread; subclasses should post progress via mHandler.
     * @param callback progress callback (may be null)
     * @return true on failure, false on success
     */
    public abstract boolean install(ChrootManager.ProgressCallback callback);

    /** Re-install (delete + re-clone), preserving data directory */
    public abstract boolean reinstall(ChrootManager.ProgressCallback callback, String version, int proxyIndex);

    // ─── Lifecycle ───

    /** Start this bot inside chroot in the background */
    public abstract ChrootManager.CommandResult start();

    /** Stop this bot (kill process) */
    public abstract ChrootManager.CommandResult stop();

    /** Restart: stop then start */
    public ChrootManager.CommandResult restart() {
        stop();
        return start();
    }

    // ─── Accessors ───

    /** WebUI/Gateway URL for this bot (e.g. "http://127.0.0.1:6185") */
    public abstract String getWebUIUrl();

    /** Log file path inside chroot for this bot */
    public abstract String getLogFile();

    /** PID file path inside chroot (null if not applicable) */
    public abstract String getPidFile();

    // ─── Boot ───

    /**
     * Enable or disable auto-start when chroot boots (via termux-boot).
     * @param enable true to enable autostart, false to disable
     */
    public abstract void setBootStart(boolean enable);

    /** @return true if this bot is configured to start on chroot boot */
    public abstract boolean isBootStartEnabled();

    // ─── Utilities ───

    /** Path to the bot's home directory inside chroot (e.g. /root/astrbot) */
    public abstract String getHomePath();

    /** Install command string shown in UI (e.g. "pip install astrbot") */
    public abstract String getInstallCommand();

    /** Start command string shown in UI (e.g. "python -m astrbot") */
    public abstract String getStartCommand();

    /** Platform/channel this bot supports (e.g. "QQ", "微信/飞书/Telegram") */
    public abstract String getSupportedPlatforms();
}