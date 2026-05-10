package app.rbot;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.text.TextUtils;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

import app.rbot.R;

/**
 * Foreground service for the active bot.
 *
 * Responsibilities:
 * 1. Persistent notification showing bot status (Running/Stopped)
 * 2. Manual start/stop/restart from UI
 * 3. Background app update check (every 6h)
 *
 * No periodic health checks — bot state is only checked on user action.
 * The service is START_STICKY so Android restarts it if killed.
 */
public class GatewayMonitorService extends Service {

    private static final String TAG = "GatewayMonitorService";
    private static final int NOTIFICATION_ID = 1001;
    private static final int APP_UPDATE_NOTIFICATION_ID = 1002;
    private static final long APP_UPDATE_CHECK_INTERVAL_MS = 6 * 60 * 60 * 1000L; // 6 hours
    private static final String APP_UPDATE_PREFS_NAME = "rbot_update";
    private static final String KEY_BG_LAST_APP_UPDATE_CHECK = "bg_last_app_update_check_time";
    private static final String KEY_BG_LAST_APP_UPDATE_NOTIFIED = "bg_last_app_update_notified_version";
    private static final String KEY_DISMISSED_VERSION = "dismissed_version";
    private static final String UPDATE_NOTIFICATION_CHANNEL_ID = "rbot_updates";

    private static String sCurrentStatus = "Starting...";
    private static NotificationManager sNotificationManager;

    private Handler mHandler = new Handler(Looper.getMainLooper());
    private Runnable mAppUpdateCheckRunnable;
    private boolean mRestartInFlight = false;

    @Override
    public void onCreate() {
        super.onCreate();
        Log.i(TAG, "GatewayMonitorService created (battery-optimized)");
        createNotificationChannels();
        sNotificationManager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Log.i(TAG, "GatewayMonitorService started");
        startForeground(NOTIFICATION_ID, buildNotification("Rbot 正在运行"));
        startAppUpdateChecking();
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        stopAppUpdateChecking();
        mHandler.removeCallbacksAndMessages(null);
        Log.i(TAG, "GatewayMonitorService destroyed");
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    // ─── App update check ──────────────────────────────────────────────────────

    private void startAppUpdateChecking() {
        if (mAppUpdateCheckRunnable != null) {
            mHandler.removeCallbacks(mAppUpdateCheckRunnable);
        }
        mAppUpdateCheckRunnable = new Runnable() {
            @Override
            public void run() {
                maybeCheckForAppUpdate();
                // Check again in 6 hours
                mHandler.postDelayed(this, APP_UPDATE_CHECK_INTERVAL_MS);
            }
        };
        // First check after 10 seconds, then every 6 hours
        mHandler.postDelayed(mAppUpdateCheckRunnable, 10_000L);
    }

    private void stopAppUpdateChecking() {
        if (mAppUpdateCheckRunnable != null) {
            mHandler.removeCallbacks(mAppUpdateCheckRunnable);
            mAppUpdateCheckRunnable = null;
        }
    }

    private void maybeCheckForAppUpdate() {
        if (UpdateChecker.isUpdateManagementDisabled(this)) {
            return;
        }
        SharedPreferences prefs = getSharedPreferences(APP_UPDATE_PREFS_NAME, MODE_PRIVATE);
        long lastCheck = prefs.getLong(KEY_BG_LAST_APP_UPDATE_CHECK, 0);
        long now = System.currentTimeMillis();
        if (now - lastCheck < APP_UPDATE_CHECK_INTERVAL_MS) {
            return;
        }
        prefs.edit().putLong(KEY_BG_LAST_APP_UPDATE_CHECK, now).apply();

        UpdateChecker.forceCheckWithFeedback(this, (updateAvailable, latestVersion, downloadUrl, notes, message) -> {
            if (!updateAvailable || TextUtils.isEmpty(latestVersion)) return;
            String dismissedVersion = prefs.getString(KEY_DISMISSED_VERSION, null);
            if (latestVersion.equals(dismissedVersion)) return;
            String notifiedVersion = prefs.getString(KEY_BG_LAST_APP_UPDATE_NOTIFIED, null);
            if (latestVersion.equals(notifiedVersion)) return;
            postAppUpdateNotification(latestVersion, downloadUrl);
            prefs.edit().putString(KEY_BG_LAST_APP_UPDATE_NOTIFIED, latestVersion).apply();
        });
    }

    private void postAppUpdateNotification(String latestVersion, String downloadUrl) {
        Intent openIntent;
        if (!TextUtils.isEmpty(downloadUrl)) {
            openIntent = new Intent(Intent.ACTION_VIEW, Uri.parse(downloadUrl));
        } else {
            openIntent = new Intent(this, MainActivity.class);
            openIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        }

        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            flags |= PendingIntent.FLAG_IMMUTABLE;
        }
        PendingIntent pi = PendingIntent.getActivity(this, 101, openIntent, flags);

        NotificationCompat.Builder builder = new NotificationCompat.Builder(this, UPDATE_NOTIFICATION_CHANNEL_ID)
            .setContentTitle(getString(R.string.rbot_update_title))
            .setContentText(getString(R.string.rbot_new_version_detected, latestVersion))
            .setSmallIcon(R.drawable.ic_service_notification)
            .setContentIntent(pi)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT);

        NotificationManager manager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager != null) {
            manager.notify(APP_UPDATE_NOTIFICATION_ID, builder.build());
        }
    }

    // ─── Manual start/stop (called from UI) ───────────────────────────────────

    /**
     * Manually start the active bot from UI button.
     * Called by MainActivity when user taps "启动".
     */
    public void manualStartBot() {
        if (mRestartInFlight) return;
        mRestartInFlight = true;
        updateStatusInternal("Starting...");

        new Thread(() -> {
            try {
                BotAdapter activeBot = BotManager.getInstance(this).getActiveBot();
                ChrootManager.CommandResult result = activeBot.start();
                mRestartInFlight = false;

                if (result.success()) {
                    Log.i(TAG, activeBot.getName() + " started successfully via manualStart");
                    mHandler.post(() -> updateStatusInternal("Running"));
                } else {
                    Log.e(TAG, "Failed to start " + activeBot.getName() + ": " + result.stderr());
                    mHandler.post(() -> updateStatusInternal("Failed"));
                }
            } catch (Exception e) {
                mRestartInFlight = false;
                Log.e(TAG, "Error starting bot: " + e.getMessage());
                mHandler.post(() -> updateStatusInternal("Error"));
            }
        }).start();
    }

    public void manualStopBot() {
        BotAdapter activeBot = BotManager.getInstance(this).getActiveBot();
        activeBot.stop();
        updateStatusInternal("Stopped");
    }

    /**
     * Manually restart the active bot from UI button.
     */
    public void manualRestartBot() {
        if (mRestartInFlight) return;

        mRestartInFlight = true;
        updateStatusInternal("Restarting...");

        new Thread(() -> {
            BotAdapter activeBot = BotManager.getInstance(this).getActiveBot();
            activeBot.stop();
            try {
                Thread.sleep(1000);
            } catch (InterruptedException ignored) {}

            ChrootManager.CommandResult result = activeBot.start();
            mRestartInFlight = false;

            if (result.success()) {
                mHandler.post(() -> updateStatusInternal("Running"));
            } else {
                mHandler.post(() -> updateStatusInternal("Failed"));
            }
        }).start();
    }

    private void updateStatusInternal(String status) {
        sCurrentStatus = status;
        if (sNotificationManager != null) {
            String botName = BotManager.getInstance(this).getActiveBot().getName();
            Notification notification = buildNotification(botName + ": " + status);
            sNotificationManager.notify(NOTIFICATION_ID, notification);
        }
    }

    // ─── Notification channels ────────────────────────────────────────────────

    private void createNotificationChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        NotificationManager manager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager == null) return;

        // Gateway channel
        NotificationChannel gatewayChannel = new NotificationChannel(
            MainActivity.NOTIFICATION_CHANNEL_ID,
            "Rbot 服务状态", NotificationManager.IMPORTANCE_LOW);
        gatewayChannel.setDescription("Rbot 运行状态");
        manager.createNotificationChannel(gatewayChannel);

        // Update channel
        NotificationChannel updateChannel = new NotificationChannel(
            UPDATE_NOTIFICATION_CHANNEL_ID,
            getString(R.string.rbot_update_channel_name),
            NotificationManager.IMPORTANCE_DEFAULT);
        updateChannel.setDescription(getString(R.string.rbot_update_channel_description));
        manager.createNotificationChannel(updateChannel);
    }

    private Notification buildNotification(String contentText) {
        Intent notificationIntent = new Intent(this, MainActivity.class);
        notificationIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);

        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            flags |= PendingIntent.FLAG_IMMUTABLE;
        }
        PendingIntent pendingIntent = PendingIntent.getActivity(this, 0, notificationIntent, flags);

        NotificationCompat.Builder builder = new NotificationCompat.Builder(
            this, MainActivity.NOTIFICATION_CHANNEL_ID)
            .setContentTitle("Rbot")
            .setContentText(contentText)
            .setSmallIcon(R.drawable.ic_service_notification)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setShowWhen(false);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            builder.setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE);
        }

        return builder.build();
    }
}
