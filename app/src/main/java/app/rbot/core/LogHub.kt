package app.rbot.core

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * 全局实时日志流 — 替代旧版 OpLog + BufferedReader 轮询。
 * 使用 SharedFlow 实现"一写多读"的响应式日志推送。
 *
 * 所有 core 层组件通过此单例推送日志，
 * UI 层 (LogViewModel) 通过 logFlow 订阅实时更新。
 */
object LogHub {

    private const val TAG = "LogHub"
    private const val MAX_BUFFER = 2000

    data class LogEntry(
        val text: String,
        val timestamp: Long = System.currentTimeMillis(),
        val isError: Boolean = false,
        val category: String = "general"
    )

    /** 历史日志缓冲（用于 LogScreen 的初始加载） */
    private val buffer = ConcurrentLinkedQueue<LogEntry>()

    /** 实时日志流（Compose UI 订阅此 Flow） */
    private val _logFlow = MutableSharedFlow<LogEntry>(extraBufferCapacity = 128)
    val logFlow: SharedFlow<LogEntry> = _logFlow.asSharedFlow()

    /** 记录普通日志 */
    fun log(text: String, category: String = "general") {
        val entry = LogEntry(text, category = category)
        addToBuffer(entry)
        _logFlow.tryEmit(entry)
        Log.d(TAG, text)
    }

    /** 记录错误日志 */
    fun error(text: String, category: String = "general") {
        val entry = LogEntry(text, isError = true, category = category)
        addToBuffer(entry)
        _logFlow.tryEmit(entry)
        Log.e(TAG, text)
    }

    /** 记录进度日志（时间戳由调用者提供上下文） */
    fun progress(text: String, category: String = "general") {
        val entry = LogEntry("  $text", category = category)
        addToBuffer(entry)
        _logFlow.tryEmit(entry)
    }

    /** 获取历史日志（用于 LogScreen 初始加载） */
    fun getRecentLogs(limit: Int = 500): List<LogEntry> {
        val all = buffer.toList()
        return if (all.size <= limit) all else all.takeLast(limit)
    }

    /** 清空日志缓冲 */
    fun clear() {
        buffer.clear()
    }

    private fun addToBuffer(entry: LogEntry) {
        buffer.add(entry)
        while (buffer.size > MAX_BUFFER) {
            buffer.poll()
        }
    }
}
