package app.rbot;

import android.app.AlarmManager;
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
import android.os.PowerManager;
import android.text.TextUtils;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

import app.rbot.R;

/**
 * Foreground service that monitors and keeps the AstrBot chroot process alive.
 *
 * Battery-optimized architecture (2026-04 refactor):
 *
 * OLD (removed):
 * - Permanent WifiLock (WIFI_MODE_FULL_LOW_LATENCY) — kept WiFi射频 24/7 at full power
 * - Permanent WakeLock (PARTIAL_WAKE_LOCK, 15min timeout, 10min reacquire) — CPU never slept
 * - Handler.postDelayed every 30s — required WakeLock to fire at all
 *
     * NEW:
     * - No WifiLock — isAstrBotRunning() is a fast PID file check, needs no persistent network
     * - No permanent WakeLock — a 10s one is only acquired in MonitorAlarmReceiver during the check
     * - AlarmManager.setExactAndAllowWhileIdle (~30s) — precise timing, reliable in Doze
 * - Foreground service persists only to show the notification and keep app alive for user interactions
 *
 * The foreground service stays running (START_STICKY) because:
 * 1. Users need a persistent notification showing AstrBot status
 * 2. The app UI needs the service to be available for start/stop/restart commands
 * 3. START_STICKY ensures Android restarts it if killed (e.g., after app update)
 *
 * Monitoring logic lives in MonitorAlarmReceiver instead — it wakes the device briefly,
 * does the check (~1-2s), updates the notification, then the device sleeps again.
 *
 * App update checks (every 6h) still run inside this service via Handler (infrequent, low impact).
 */
public class GatewayMonitorService extends Service {

    private static final String TAG = "GatewayMonitorService";
    private static final int NOTIFICATION_ID = 1001;
    private static final int APP_UPDATE_NOTIFICATION_ID = 1002;
    private static final int MONITOR_INTERVAL_MS = 30_000; // 30 seconds
    private static final int RESTART_DELAY_MS = 5_000; // 5 seconds
    private static final int MAX_RESTART_ATTEMPTS = 5;
    private static final long APP_UPDATE_CHECK_INTERVAL_MS = 6 * 60 * 60 * 1000L; // 6 hours
    private static final String APP_UPDATE_PREFS_NAME = "rbot_update";
    private static final String KEY_BG_LAST_APP_UPDATE_CHECK = "bg_last_app_update_check_time";
    private static final String KEY_BG_LAST_APP_UPDATE_NOTIFIED = "bg_last_app_update_notified_version";
    private static final String KEY_DISMISSED_VERSION = "dismissed_version";
    private static final String UPDATE_NOTIFICATION_CHANNEL_ID = "rbot_updates";

    private static String sCurrentStatus = "Starting...";
    private static NotificationManager sNotificationManager;

    // App update check — infrequent, use Handler (low impact)
    private Handler mHandler = new Handler(Looper.getMainLooper());
    private Runnable mAppUpdateCheckRunnable;

    private boolean mIsMonitoring = false;
    private int mRestartAttempts = 0;
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
        Notification notification = buildNotification("Rbot 正在运行");
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
        // Cancel scheduled alarm when service is permanently stopped
        MonitorAlarmReceiver.cancelAlarm(this);
        Log.i(TAG, "GatewayMonitorService destroyed");
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    // ─── Monitoring ───────────────────────────────────────────────────────────

    /**
     * Start periodic monitoring via AlarmManager.
     * MonitorAlarmReceiver handles each tick and updates the notification.
     *
     * The old Handler-based approach with permanent WakeLock is removed.
     */
    private void startMonitoring() {
        if (mIsMonitoring) return;
        mIsMonitoring = true;

        Log.i(TAG, "Starting AstrBot monitoring (AlarmManager-based, no WakeLock/WifiLock)");

        // Schedule periodic alarms
        MonitorAlarmReceiver.scheduleNextAlarm(this);

        // Also start app update check (every 6 hours, infrequent, Handler is fine)
        startAppUpdateChecking();
    }

    private void stopMonitoring() {
        if (!mIsMonitoring) return;
        mIsMonitoring = false;
        MonitorAlarmReceiver.cancelAlarm(this);
        stopAppUpdateChecking();
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

    // ─── Manual start/stop (called from UI) ───────────────────────────────────

    /**
     * Manually start AstrBot from UI button.
     * Called by MainActivity when user taps "启动".
     */
    public void manualStartAstrBot() {
        if (mRestartInFlight) return;

        mRestartAttempts = 0;
        mRestartInFlight = true;
        updateStatusInternal("Starting...");

        new Thread(() -> {
            try {
                ChrootManager.CommandResult result = ChrootManager.startAstrBot();
                mRestartInFlight = false;

                if (result.success()) {
                    Log.i(TAG, "AstrBot started successfully via manualStart");
                    mRestartAttempts = 0;
                    mHandler.post(() -> updateStatusInternal("Running"));
                } else {
                    Log.e(TAG, "Failed to start AstrBot: " + result.stderr());
                    mHandler.post(() -> updateStatusInternal("Failed"));
                }
            } catch (Exception e) {
                mRestartInFlight = false;
                Log.e(TAG, "Error starting AstrBot: " + e.getMessage());
                mHandler.post(() -> updateStatusInternal("Error"));
            }
        }).start();
    }

    /**
     * Manually stop AstrBot from UI button.
     * Called by MainActivity when user taps "停止".
     */
    public void manualStopAstrBot() {
        ChrootManager.stopAstrBot();
        updateStatusInternal("Stopped");
        mRestartAttempts = 0;
    }

    /**
     * Manually restart AstrBot from UI button.
     */
    public void manualRestartAstrBot() {
        if (mRestartInFlight) return;

        mRestartInFlight = true;
        updateStatusInternal("Restarting...");

        new Thread(() -> {
            ChrootManager.stopAstrBot();
            try {
                Thread.sleep(1000);
            } catch (InterruptedException ignored) {}

            ChrootManager.CommandResult result = ChrootManager.startAstrBot();
            mRestartInFlight = false;

            if (result.success()) {
                mHandler.post(() -> updateStatusInternal("Running"));
            } else {
                mHandler.post(() -> updateStatusInternal("Failed"));
            }
        }).start();
    }

    // ─── Status update ─────────────────────────────────────────────────────────

    /**
     * Called by MonitorAlarmReceiver to update the foreground notification.
     * This is static so the receiver doesn't need a service instance.
     */
    public static void updateNotificationStatus(Context context, String status) {
        sCurrentStatus = status;
        NotificationManager manager = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager == null) return;

        Intent notificationIntent = new Intent(context, MainActivity.class);
        notificationIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);

        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            flags |= PendingIntent.FLAG_IMMUTABLE;
        }
        PendingIntent pendingIntent = PendingIntent.getActivity(context, 0, notificationIntent, flags);

        Notification notification = new NotificationCompat.Builder(context, MainActivity.NOTIFICATION_CHANNEL_ID)
            .setContentTitle("Rbot")
            .setContentText("AstrBot: " + status)
            .setSmallIcon(R.drawable.ic_service_notification)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setShowWhen(false)
            .build();

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            notification.extras.putInt("android.foregroundServiceBehavior", NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE);
        }

        manager.notify(NOTIFICATION_ID, notification);
    }

    private void updateStatusInternal(String status) {
        sCurrentStatus = status;
        if (sNotificationManager != null) {
            Notification notification = buildNotification("AstrBot: " + status);
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
        gatewayChannel.setDescription("Rbot AstrBot 运行状态");
        manager.createNotificationChannel(gatewayChannel);

        // Update channel
        NotificationChannel updateChannel = new NotificationChannel(
            UPDATE_NOTIFICATION_CHANNEL_ID,
            getString(R.string.botdrop_update_channel_name),
            NotificationManager.IMPORTANCE_DEFAULT);
        updateChannel.setDescription(getString(R.string.botdrop_update_channel_description));
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
