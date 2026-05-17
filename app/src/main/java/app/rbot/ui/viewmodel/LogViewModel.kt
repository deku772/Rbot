package app.rbot.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.rbot.core.BotBridge
import app.rbot.core.LogHub
import app.rbot.data.model.LogLine
import app.rbot.data.model.LogUiState
import app.rbot.data.repository.OpLogRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * 日志 ViewModel — 替代旧版 LogActivity。
 * 订阅 LogHub 的实时日志流 + Room 持久化。
 */
@HiltViewModel
class LogViewModel @Inject constructor(
    private val botBridge: BotBridge,
    private val opLogRepository: OpLogRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(LogUiState())
    val uiState: StateFlow<LogUiState> = _uiState.asStateFlow()

    private val logBuffer = mutableListOf<LogLine>()
    private val MAX_LOG_LINES = 2000

    init {
        // 加载历史日志
        loadHistoricalLogs()

        // 订阅 LogHub 实时日志流
        viewModelScope.launch {
            LogHub.logFlow.collect { entry ->
                val logLine = LogLine(entry.text, entry.timestamp, entry.isError)
                appendLogLine(logLine)
            }
        }

        // 订阅 Room 日志（可选的持久化视图）
        viewModelScope.launch {
            opLogRepository.getRecentLogs(500).collect { entities ->
                // Room 日志作为补充数据源，不覆盖 LogHub 实时流
            }
        }
    }

    private fun loadHistoricalLogs() {
        val historical = LogHub.getRecentLogs(500)
        historical.forEach { entry ->
            logBuffer.add(LogLine(entry.text, entry.timestamp, entry.isError))
        }
        _uiState.value = _uiState.value.copy(logLines = logBuffer.toList())
    }

    /** 手动刷新 — 从 Room 加载 */
    fun refreshLogs() {
        viewModelScope.launch(Dispatchers.IO) {
            // 刷新操作由 Flow 自动推送，此处可触发 Room 重新查询
        }
    }

    /** 添加一条日志到缓冲 */
    private fun appendLogLine(line: LogLine) {
        logBuffer.add(line)
        if (logBuffer.size > MAX_LOG_LINES) {
            logBuffer.removeAt(0)
        }
        _uiState.value = _uiState.value.copy(logLines = logBuffer.toList())
    }

    fun clearLogs() {
        logBuffer.clear()
        LogHub.clear()
        viewModelScope.launch { opLogRepository.clearAll() }
        _uiState.value = _uiState.value.copy(logLines = emptyList())
    }

    fun toggleAutoScroll() {
        _uiState.value = _uiState.value.copy(isAutoScroll = !_uiState.value.isAutoScroll)
    }
}
