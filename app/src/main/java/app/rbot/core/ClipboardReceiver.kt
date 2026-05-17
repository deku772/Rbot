package app.rbot.core

import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * 剪贴板广播接收器 — 从 Java ClipboardReceiver 迁移。
 * 用法: am broadcast -a app.rbot.SET_CLIPBOARD --es text "你好世界"
 */
class ClipboardReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        if (intent == null || ACTION_SET_CLIPBOARD != intent.action) return

        val text = intent.getStringExtra("text") ?: run {
            Log.w(TAG, "SET_CLIPBOARD received but 'text' extra is missing")
            return
        }

        android.os.Handler(android.os.Looper.getMainLooper()).post {
            try {
                val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return@post
                val clip = ClipData.newPlainText("rbot", text)
                cm.setPrimaryClip(clip)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to set clipboard: ${e.message}")
            }
        }
    }

    companion object {
        private const val TAG = "ClipboardReceiver"
        const val ACTION_SET_CLIPBOARD = "app.rbot.SET_CLIPBOARD"
    }
}
