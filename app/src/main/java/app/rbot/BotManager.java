package app.rbot;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.HashMap;
import java.util.Map;

/**
 * Central singleton that manages bot engine switching.
 *
 * Stores the active bot ID in SharedPreferences so it persists across app restarts.
 * All UI code should go through BotManager instead of calling adapters directly.
 *
 * Usage:
 *   BotAdapter bot = BotManager.getInstance(context).getActiveBot();
 *   bot.start();
 */
public class BotManager {

    private static final String PREFS_NAME = "rbot_bot_prefs";
    private static final String KEY_ACTIVE_BOT = "active_bot";

    /** Cached singleton instance */
    private static BotManager sInstance;

    private final SharedPreferences mPrefs;
    private final Map<String, BotAdapter> mAdapters;

    private BotManager(Context context) {
        mPrefs = context.getApplicationContext()
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);

        // Register all known adapters
        mAdapters = new HashMap<>();
        registerAdapter(new AstrBotAdapter(context));
    }

    /** Get the singleton instance */
    public synchronized static BotManager getInstance(Context context) {
        if (sInstance == null) {
            sInstance = new BotManager(context);
        }
        return sInstance;
    }

    /** Register an adapter (called during construction) */
    private void registerAdapter(BotAdapter adapter) {
        mAdapters.put(adapter.getId(), adapter);
    }

    // ─── Active bot management ───

    /**
     * Get the currently active bot adapter.
     * Defaults to AstrBot if no preference is set.
     */
    public BotAdapter getActiveBot() {
        String botId = mPrefs.getString(KEY_ACTIVE_BOT, BotAdapter.ID_ASTRBOT);
        BotAdapter adapter = mAdapters.get(botId);
        return adapter != null ? adapter : getDefaultBot();
    }

    /** Get default bot (AstrBot) */
    public BotAdapter getDefaultBot() {
        return mAdapters.get(BotAdapter.ID_ASTRBOT);
    }

    /**
     * Switch to a different bot engine.
     * This stops the currently running bot (if any) and marks the new one as active.
     * Does NOT start the new bot — caller decides when to start.
     *
     * @param botId the bot ID to switch to
     * @return true if switch was successful (botId was valid), false otherwise
     */
    public boolean switchTo(String botId) {
        BotAdapter newBot = mAdapters.get(botId);
        if (newBot == null) {
            return false;
        }

        // Stop currently running bot first
        BotAdapter current = getActiveBot();
        if (current != null && current.isRunning()) {
            current.stop();
        }

        // Persist preference
        mPrefs.edit().putString(KEY_ACTIVE_BOT, botId).apply();
        return true;
    }

    /** @return the bot ID that is currently stored as active */
    public String getActiveBotId() {
        return mPrefs.getString(KEY_ACTIVE_BOT, BotAdapter.ID_ASTRBOT);
    }

    /** Get a specific bot adapter by ID, or null if not found */
    public BotAdapter getBot(String botId) {
        return mAdapters.get(botId);
    }

    // ─── Convenience delegates (forwarded to active bot) ───

    public boolean isInstalled() {
        return getActiveBot().isInstalled();
    }

    public boolean isRunning() {
        return getActiveBot().isRunning();
    }

    public ChrootManager.CommandResult start() {
        return getActiveBot().start();
    }

    public ChrootManager.CommandResult stop() {
        return getActiveBot().stop();
    }

    public String getWebUIUrl() {
        return getActiveBot().getWebUIUrl();
    }

    public String getLogFile() {
        return getActiveBot().getLogFile();
    }

    public String getHomePath() {
        return getActiveBot().getHomePath();
    }

    // ─── Query all bots ───

    /** @return all registered adapters */
    public Map<String, BotAdapter> getAllAdapters() {
        return mAdapters;
    }

    /**
     * @return display info for all bots: id, name, installed, running
     */
    public BotInfo[] getAllBotInfo() {
        BotInfo[] result = new BotInfo[mAdapters.size()];
        int i = 0;
        for (BotAdapter adapter : mAdapters.values()) {
            result[i++] = new BotInfo(
                adapter.getId(),
                adapter.getName(),
                adapter.isInstalled(),
                adapter.isRunning(),
                adapter.getSupportedPlatforms()
            );
        }
        return result;
    }

    // ─── Lightweight data class ───

    public static class BotInfo {
        public final String id;
        public final String name;
        public final boolean installed;
        public final boolean running;
        public final String platforms;

        public BotInfo(String id, String name, boolean installed, boolean running, String platforms) {
            this.id = id;
            this.name = name;
            this.installed = installed;
            this.running = running;
            this.platforms = platforms;
        }
    }
}
