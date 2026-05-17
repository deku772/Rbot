package app.rbot.core

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log

/**
 * 开机自启广播接收器 — 从 Java BootReceiver 迁移。
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (Intent.ACTION_BOOT_COMPLETED != intent.action) return

        val bridge = BotBridge.getInstance(context)
        val activeBot = bridge.getActiveBot()

        if (!activeBot.isInstalled) {
            Log.i(TAG, "${activeBot.engine.displayName} not installed, skipping auto-start")
            return
        }

        Log.i(TAG, "Boot completed, starting ${activeBot.engine.displayName}")
        Thread {
            val result = bridge.startBot()
            if (result.success) {
                Log.i(TAG, "${activeBot.engine.displayName} started on boot")
                startServiceAndShow(context)
            } else {
                Log.e(TAG, "Failed to start on boot: ${result.stderr}")
            }
        }.start()
    }

    private fun startServiceAndShow(context: Context) {
        val serviceIntent = Intent(context, BotService::class.java)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(serviceIntent)
            } else {
                context.startService(serviceIntent)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start BotService on boot: ${e.message}")
        }
    }

    companion object {
        private const val TAG = "BootReceiver"
    }
}
