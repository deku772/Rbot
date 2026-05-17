package app.rbot.core

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import app.rbot.MainActivity

/**
 * 前台保活服务 — 替代旧版 RbotService + GatewayMonitorService。
 * 合并为一个 Service，使用 lifecycle-service 的协程支持。
 */
class BotService : Service() {

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "BotService created")
        createNotificationChannels()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.i(TAG, "BotService started")
        startForeground(NOTIFICATION_ID, buildNotification("Rbot 正在运行"))
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.i(TAG, "BotService destroyed")
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ─── Notification ───

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java) ?: return

        val serviceChannel = NotificationChannel(
            CHANNEL_SERVICE,
            "Rbot 服务状态",
            NotificationManager.IMPORTANCE_LOW
        ).apply { description = "Rbot 运行状态" }

        val updateChannel = NotificationChannel(
            CHANNEL_UPDATES,
            "Rbot 更新",
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply { description = "检查并通知 Rbot 新版本" }

        manager.createNotificationChannels(listOf(serviceChannel, updateChannel))
    }

    private fun buildNotification(contentText: String): Notification {
        val notificationIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }

        var flags = PendingIntent.FLAG_UPDATE_CURRENT
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            flags = flags or PendingIntent.FLAG_IMMUTABLE
        }
        val pendingIntent = PendingIntent.getActivity(this, 0, notificationIntent, flags)

        return NotificationCompat.Builder(this, CHANNEL_SERVICE)
            .setContentTitle("Rbot")
            .setContentText(contentText)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setShowWhen(false)
            .apply {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
                }
            }
            .build()
    }

    companion object {
        private const val TAG = "BotService"
        private const val NOTIFICATION_ID = 1001
        const val CHANNEL_SERVICE = "rbot_service"
        const val CHANNEL_UPDATES = "rbot_updates"
    }
}
