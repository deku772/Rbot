package app.rbot.data.model

import app.rbot.core.AuthManager
import app.rbot.core.BotBridge
import app.rbot.core.GitHubProxyManager

/**
 * UI 状态模型 — 所有 Screen 的状态都在这里定义。
 * 使用 data class + StateFlow 驱动 Compose 重组。
 */

/** 主页状态 */
data class HomeUiState(
    val botState: BotBridge.State = BotBridge.State.NOT_INSTALLED,
    val isRunning: Boolean = false,
    val isInstalled: Boolean = false,
    val componentStatus: BotBridge.ComponentStatus = BotBridge.ComponentStatus(),
    val webUIUrl: String = "",
    val webUILanUrl: String = "",
    val sshInfo: SshInfo = SshInfo(),
    val isStarting: Boolean = false,
    val isStopping: Boolean = false,
    val errorMessage: String? = null,
    val authMode: AuthManager.AuthMode = AuthManager.AuthMode.UNAVAILABLE,
    val isUpdateAvailable: Boolean = false,
    val latestVersion: String? = null
)

data class SshInfo(
    val host: String = "127.0.0.1",
    val port: Int = 8022,
    val user: String = "root",
    val password: String = "",
    val lanHost: String = "",
    val isRunning: Boolean = false
)

/** 日志页状态 */
data class LogUiState(
    val logLines: List<LogLine> = emptyList(),
    val isLoading: Boolean = false,
    val isAutoScroll: Boolean = true
)

data class LogLine(
    val text: String,
    val timestamp: Long = System.currentTimeMillis(),
    val isError: Boolean = false
)

/** 设置页状态 */
data class SettingsUiState(
    val proxyInfo: List<GitHubProxyManager.ProxyInfo> = emptyList(),
    val isTestingProxy: Boolean = false,
    val customProxy: String = "",
    val appVersion: String = "",
    val astrbotVersion: String? = null,
    val isUpdateAvailable: Boolean = false,
    val latestVersion: String? = null
)

/** 终端页状态 */
data class TerminalUiState(
    val isConnected: Boolean = false,
    val isProotMode: Boolean = false
)

/** 安装向导状态 */
data class SetupUiState(
    val currentStep: Int = 0,
    val totalSteps: Int = 4,
    val stepLabel: String = "",
    val isInstalling: Boolean = false,
    val progress: Float = 0f,
    val errorMessage: String? = null,
    val isComplete: Boolean = false,
    // 环境检测
    val isDetecting: Boolean = true,
    val rootfsReady: Boolean = false,
    val botInstalled: Boolean = false,
    val useProot: Boolean = false,
    // 用户复选框
    val reinstallRootfs: Boolean = false,
    val reinstallDeps: Boolean = false,
    val reinstallBot: Boolean = false
)
