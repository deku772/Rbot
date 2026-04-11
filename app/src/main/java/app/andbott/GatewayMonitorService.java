package app.andbott;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.text.TextUtils;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

import app.andbott.R;

/**
 * Foreground service that monitors and keeps the AstrBot chroot process alive.
 *
 * - Runs as a foreground service with persistent notification
 * - Starts AstrBot if not running
 * - Monitors AstrBot process and restarts if it dies
 * - Handles Android Doze mode with partial wake lock
 * - Shows AstrBot status in notification
 */
public class GatewayMonitorService extends Service {

    private static final String TAG = "GatewayMonitorService";
    private static final int NOTIFICATION_ID = 1001;
    private static final int APP_UPDATE_NOTIFICATION_ID = 1002;
    private static final int MONITOR_INTERVAL_MS = 30000; // 30 seconds
    private static final int RESTART_DELAY_MS = 5000; // 5 seconds
    private static final int MAX_RESTART_ATTEMPTS = 5;
    private static final long WAKELOCK_TIMEOUT_MS = 15 * 60 * 1000L;
    private static final long WAKELOCK_REACQUIRE_INTERVAL_MS = 10 * 60 * 1000L;
    private static final long APP_UPDATE_CHECK_INTERVAL_MS = 6 * 60 * 60 * 1000L;
    private static final String APP_UPDATE_PREFS_NAME = "botdrop_update";
    private static final String KEY_BG_LAST_APP_UPDATE_CHECK = "bg_last_app_update_check_time";
    private static final String KEY_BG_LAST_APP_UPDATE_NOTIFIED = "bg_last_app_update_notified_version";
    private static final String KEY_DISMISSED_VERSION = "dismissed_version";
    private static final String UPDATE_NOTIFICATION_CHANNEL_ID = "botdrop_updates";

    private Handler mHandler = new Handler(Looper.getMainLooper());
    private Runnable mMonitorRunnable;
    private PowerManager.WakeLock mWakeLock;
    private WifiManager.WifiLock mWifiLock;
    private long mWakeLockLastAcquired = 0;
    private boolean mIsMonitoring = false;
    private String mCurrentStatus = "Starting...";
    private int mRestartAttempts = 0;
    private boolean mRestartInFlight = false;

    @Override
    public void onCreate() {
        super.onCreate();
        Log.i(TAG, "GatewayMonitorService created");
        createNotificationChannels();

        // Initialize wake lock
        PowerManager powerManager = (PowerManager) getSystemService(Context.POWER_SERVICE);
        if (powerManager != null) {
            mWakeLock = powerManager.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK, "BotDrop::GatewayMonitor");
            mWakeLock.setReferenceCounted(false);
            acquireWakeLock();
        }

        // Keep Wi-Fi from power-save
        try {
            WifiManager wifiManager = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            if (wifiManager != null) {
                mWifiLock = wifiManager.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "BotDrop::GatewayWifi");
                mWifiLock.setReferenceCounted(false);
                if (!mWifiLock.isHeld()) {
                    mWifiLock.acquire();
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "Failed to acquire WifiLock: " + e.getMessage());
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Log.i(TAG, "GatewayMonitorService started");
        Notification notification = buildNotification("BotDrop 正在运行");
        startForeground(NOTIFICATION_ID, notification);

        if (!mIsMonitoring) {
            startMonitoring();
        }

        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        stopMonitoring();
        mHandler.removeCallbacksAndMessages(null);

        if (mWakeLock != null && mWakeLock.isHeld()) {
            mWakeLock.release();
        }
        if (mWifiLock != null && mWifiLock.isHeld()) {
            try { mWifiLock.release(); } catch (Exception ignored) {}
        }
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    // ─── Monitoring ───

    private void startMonitoring() {
        mIsMonitoring = true;
        Log.i(TAG, "Starting AstrBot monitoring");

        mMonitorRunnable = new Runnable() {
            @Override
            public void run() {
                reacquireWakeLockIfNeeded();
                checkAndRestartAstrBot();
                maybeCheckForAppUpdate();
                if (mIsMonitoring) {
                    mHandler.postDelayed(this, MONITOR_INTERVAL_MS);
                }
            }
        };
        mHandler.post(mMonitorRunnable);
    }

    private void stopMonitoring() {
        mIsMonitoring = false;
        if (mMonitorRunnable != null) {
            mHandler.removeCallbacks(mMonitorRunnable);
        }
    }

    /**
     * Check if AstrBot is running and restart if needed.
     * Uses ChrootManager directly — no service binding required.
     */
    private void checkAndRestartAstrBot() {
        if (mRestartInFlight) {
            return;
        }

        try {
            boolean isRunning = ChrootManager.isAstrBotRunning();
            if (isRunning) {
                mRestartAttempts = 0;
                updateStatus("Running");
            } else {
                Log.i(TAG, "AstrBot is not running, attempting restart");
                updateStatus("Restarting...");
                restartAstrBot();
            }
        } catch (Exception e) {
            Log.e(TAG, "Error checking AstrBot status: " + e.getMessage());
        }
    }

    private void restartAstrBot() {
        if (mRestartInFlight) return;

        if (mRestartAttempts >= MAX_RESTART_ATTEMPTS) {
            Log.e(TAG, "Max restart attempts reached");
            updateStatus("Failed - manual restart required");
            return;
        }

        mRestartAttempts++;
        mRestartInFlight = true;
        Log.i(TAG, "Restart attempt " + mRestartAttempts + "/" + MAX_RESTART_ATTEMPTS);

        // Run on background thread to avoid blocking monitor
        new Thread(() -> {
            try {
                ChrootManager.CommandResult result = ChrootManager.startAstrBot();
                mRestartInFlight = false;

                if (result.success()) {
                    Log.i(TAG, "AstrBot started successfully");
                    mRestartAttempts = 0;
                    mHandler.post(() -> updateStatus("Running"));
                } else {
                    Log.e(TAG, "Failed to start AstrBot: " + result.stderr());
                    mHandler.post(() -> updateStatus("Failed (attempt " + mRestartAttempts + "/" + MAX_RESTART_ATTEMPTS + ")"));
                    if (mRestartAttempts < MAX_RESTART_ATTEMPTS) {
                        mHandler.postDelayed(this::restartAstrBot, RESTART_DELAY_MS);
                    }
                }
            } catch (Exception e) {
                mRestartInFlight = false;
                Log.e(TAG, "Error restarting AstrBot: " + e.getMessage());
            }
        }).start();
    }

    // ─── App update check ───

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
            .setContentTitle(getString(R.string.botdrop_update_title))
            .setContentText(getString(R.string.botdrop_new_version_detected, latestVersion))
            .setSmallIcon(R.drawable.ic_service_notification)
            .setContentIntent(pi)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT);

        NotificationManager manager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager != null) {
            manager.notify(APP_UPDATE_NOTIFICATION_ID, builder.build());
        }
    }

    // ─── Notification ───

    private void createNotificationChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        NotificationManager manager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager == null) return;

        // Gateway channel
        NotificationChannel gatewayChannel = new NotificationChannel(
            MainActivity.NOTIFICATION_CHANNEL_ID,
            "BotDrop 服务状态", NotificationManager.IMPORTANCE_LOW);
        gatewayChannel.setDescription("BotDrop AstrBot 运行状态");
        manager.createNotificationChannel(gatewayChannel);

        // Update channel
        NotificationChannel updateChannel = new NotificationChannel(
            UPDATE_NOTIFICATION_CHANNEL_ID,
            getString(R.string.botdrop_update_channel_name),
            NotificationManager.IMPORTANCE_DEFAULT);
        updateChannel.setDescription(getString(R.string.botdrop_update_channel_description));
        manager.createNotificationChannel(updateChannel);
    }

    private void updateStatus(String status) {
        mCurrentStatus = status;
        Notification notification = buildNotification("AstrBot: " + status);
        NotificationManager manager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager != null) {
            manager.notify(NOTIFICATION_ID, notification);
        }
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
            .setContentTitle("BotDrop")
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

    // ─── WakeLock ───

    private void acquireWakeLock() {
        if (mWakeLock != null && !mWakeLock.isHeld()) {
            mWakeLock.acquire(WAKELOCK_TIMEOUT_MS);
            mWakeLockLastAcquired = System.currentTimeMillis();
        }
    }

    private void reacquireWakeLockIfNeeded() {
        if (mWakeLock == null) return;
        long timeSinceLastAcquire = System.currentTimeMillis() - mWakeLockLastAcquired;
        if (timeSinceLastAcquire >= WAKELOCK_REACQUIRE_INTERVAL_MS) {
            if (mWakeLock.isHeld()) mWakeLock.release();
            acquireWakeLock();
        }
    }
}
