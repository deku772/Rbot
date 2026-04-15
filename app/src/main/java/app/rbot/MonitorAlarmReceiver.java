package app.rbot;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.PowerManager;
import android.util.Log;

/**
 * Alarm-based monitor that replaces the old Handler + permanent WakeLock + WifiLock approach.
 *
 * How it works:
 * - AlarmManager.setExactAndAllowWhileIdle schedules the next check (~30s)
 * - When alarm fires, this receiver briefly wakes the device
 * - Acquires a short WakeLock (10s) only during the actual check execution
 * - After the check (~1-2s), releases the WakeLock and reschedules → device sleeps again
 *
 * No WifiLock needed — isAstrBotRunning() is a fast PID file check that exits immediately.
 * No permanent WakeLock — only a 10s one during the brief check window.
 * Uses setExactAndAllowWhileIdle for reliable timing even in Doze mode.
 *
 * SCHEDULE_EXACT_ALARM permission: Required on Android 12+. If not granted, falls back to
 * setInexactRepeating (less accurate but still works). The system may further batch inexact
 * alarms during Doze, so response time could be 1-10 minutes longer.
 */
public class MonitorAlarmReceiver extends BroadcastReceiver {

    private static final String TAG = "MonitorAlarmReceiver";
    public static final String ACTION_MONITOR_TICK = "app.rbot.ACTION_MONITOR_TICK";
    private static final long WAKELOCK_TIMEOUT_MS = 10_000L; // 10 seconds — more than enough for isAstrBotRunning()
    private static final long MONITOR_INTERVAL_MS = 30_000L; // 30 seconds between checks

    @Override
    public void onReceive(Context context, Intent intent) {
        if (!ACTION_MONITOR_TICK.equals(intent.getAction())) {
            return;
        }

        Log.d(TAG, "Monitor tick received");

        // Brief WakeLock: only holds during the status check
        PowerManager powerManager = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
        if (powerManager == null) {
            scheduleNextAlarm(context);
            return;
        }

        PowerManager.WakeLock wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK, "Rbot::MonitorTick");
        wakeLock.acquire(WAKELOCK_TIMEOUT_MS);

        try {
            // Do the actual status check — this is a fast ~1-2s shell command
            BotAdapter activeBot = BotManager.getInstance(context).getActiveBot();
            boolean isRunning = activeBot.isRunning();
            String status = isRunning ? "Running" : "Stopped";

            // Update the foreground notification with current status
            GatewayMonitorService.updateNotificationStatus(context, status);

            Log.d(TAG, "Status check done: " + status);
        } catch (Exception e) {
            Log.e(TAG, "Error during monitor tick: " + e.getMessage());
        } finally {
            if (wakeLock.isHeld()) {
                wakeLock.release();
            }
            // Schedule next alarm regardless of check result
            scheduleNextAlarm(context);
        }
    }

    /**
     * Schedule the next monitor alarm using AlarmManager.setExactAndAllowWhileIdle.
     *
     * Uses ELAPSED_REALTIME_WAKEUP so the alarm fires based on elapsed time since boot,
     * not wall-clock time (which is unreliable in Doze).
     *
     * Falls back to setInexactRepeating if SCHEDULE_EXACT_ALARM is not granted
     * (Android 12+ requires explicit user consent).
     */
    public static void scheduleNextAlarm(Context context) {
        AlarmManager alarmManager = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        if (alarmManager == null) return;

        Intent intent = new Intent(context, MonitorAlarmReceiver.class);
        intent.setAction(ACTION_MONITOR_TICK);

        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
            flags |= PendingIntent.FLAG_IMMUTABLE;
        }

        PendingIntent pendingIntent = PendingIntent.getBroadcast(
            context, 0, intent, flags);

        // Cancel any existing alarm first
        alarmManager.cancel(pendingIntent);

        long triggerAtMillis = android.os.SystemClock.elapsedRealtime() + MONITOR_INTERVAL_MS;

        // setExactAndAllowWhileIdle: precise ~30s interval, works even in Doze
        // Requires SCHEDULE_EXACT_ALARM permission (auto-granted on install for most apps,
        // but user can revoke it in battery settings → falls back gracefully)
        try {
            alarmManager.setExactAndAllowWhileIdle(
                AlarmManager.ELAPSED_REALTIME_WAKEUP,
                triggerAtMillis,
                pendingIntent);
            Log.d(TAG, "Exact alarm scheduled in " + MONITOR_INTERVAL_MS + "ms");
        } catch (SecurityException e) {
            // SCHEDULE_EXACT_ALARM not granted — fall back to inexact
            Log.w(TAG, "SCHEDULE_EXACT_ALARM not granted, using inexact alarm: " + e.getMessage());
            alarmManager.setInexactRepeating(
                AlarmManager.ELAPSED_REALTIME_WAKEUP,
                triggerAtMillis,
                MONITOR_INTERVAL_MS,
                pendingIntent);
        }
    }

    /**
     * Cancel the scheduled monitor alarm.
     * Call this when the service is permanently stopped.
     */
    public static void cancelAlarm(Context context) {
        AlarmManager alarmManager = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        if (alarmManager == null) return;

        Intent intent = new Intent(context, MonitorAlarmReceiver.class);
        intent.setAction(ACTION_MONITOR_TICK);

        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
            flags |= PendingIntent.FLAG_IMMUTABLE;
        }

        PendingIntent pendingIntent = PendingIntent.getBroadcast(
            context, 0, intent, flags);

        alarmManager.cancel(pendingIntent);
        Log.d(TAG, "Alarm cancelled");
    }
}
