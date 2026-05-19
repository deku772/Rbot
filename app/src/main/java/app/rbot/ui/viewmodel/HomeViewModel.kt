package app.rbot.ui.viewmodel

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.rbot.core.*
import app.rbot.data.model.HomeUiState
import app.rbot.data.model.SshInfo
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.net.Inet4Address
import java.net.NetworkInterface
import javax.inject.Inject

/**
 * 主页 ViewModel — 管理首页所有状态。
 * 替代旧版 MainActivity 中 734 行的 God Activity 逻辑。
 * 增加: SSH 控制、定时状态刷新、更新检查、日志尾部读取。
 */
@HiltViewModel
class HomeViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val botBridge: BotBridge
) : ViewModel() {

    private val _uiState = MutableStateFlow(HomeUiState())
    val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()

    private var refreshJob: Job? = null
    private val prootManager: PRootManager
        get() = PRootManager.getInstance(context)

    init {
        refreshState()
        startAutoRefresh()
    }

    // ─── 状态刷新 ───

    fun refreshState() {
        // 所有 shell 操作必须在 IO 线程，避免主线程 ANR
        viewModelScope.launch(Dispatchers.IO) {
            // 轻量检查：有 root 权限但当前不是 ROOT 模式 → 异步重新检测
            val needsRootRedetect = AuthManager.instance.currentMode != AuthManager.AuthMode.ROOT
                && AuthManager.instance.userChoice != AuthManager.UserModeChoice.PROOT
                && ChrootManager.isSuBinaryPresent()

            if (needsRootRedetect) {
                if (ChrootManager.isRootAvailable()) {
                    AuthManager.instance.detectAndSetMode()
                }
            }

            doRefreshState()
        }
    }

    private fun doRefreshState() {
        val status = if (AuthManager.instance.isProotMode) {
            ChrootManager.getFullStatus().let { fs ->
                // PRoot 模式用 ChrootManager 的 getFullStatus 获取基本状态
                // 但 rootfs/astrbot 实际由 PRootManager 管理
                fs.copy(
                    rootfsReady = prootManager.isRootfsReady(),
                    astrBotInstalled = prootManager.isAstrBotInstalled(),
                    astrBotRunning = prootManager.isAstrBotRunning()
                )
            }
        } else {
            ChrootManager.getFullStatus()
        }

        val componentStatus = botBridge.getComponentStatus()
        val authMode = AuthManager.instance.currentMode
        val lanIp = getLanIpAddress()

        val sshRunning = if (AuthManager.instance.isProotMode) {
            prootManager.isSshRunning()
        } else {
            ChrootManager.isSshRunning()
        }

        val sshInfo = SshInfo(
            host = "127.0.0.1",
            port = if (AuthManager.instance.isProotMode) PRootManager.SSH_PORT else 22,
            user = "root",
            password = if (AuthManager.instance.isProotMode) {
                RbotPaths.DEFAULT_SSH_PASSWORD
            } else {
                ChrootManager.getRootPassword().ifEmpty { RbotPaths.DEFAULT_SSH_PASSWORD }
            },
            lanHost = lanIp,
            isRunning = sshRunning
        )

        _uiState.value = _uiState.value.copy(
            botState = when {
                status.astrBotRunning -> BotBridge.State.RUNNING
                status.astrBotInstalled -> BotBridge.State.READY
                else -> BotBridge.State.NOT_INSTALLED
            },
            isRunning = status.astrBotRunning,
            isInstalled = status.astrBotInstalled,
            componentStatus = componentStatus,
            webUIUrl = botBridge.getWebUIUrl(),
            webUILanUrl = if (lanIp.isNotEmpty()) "http://$lanIp:6185" else "",
            sshInfo = sshInfo,
            authMode = authMode
        )
    }

    /** 启动自动状态刷新（每 15 秒） */
    private fun startAutoRefresh() {
        refreshJob?.cancel()
        refreshJob = viewModelScope.launch(Dispatchers.IO) {
            while (isActive) {
                delay(15_000)
                refreshState()
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        refreshJob?.cancel()
    }

    // ─── Bot 控制 ───

    fun startBot() {
        viewModelScope.launch(Dispatchers.IO) {
            _uiState.value = _uiState.value.copy(isStarting = true, errorMessage = null)
            val result = botBridge.startBot()
            val errorMsg = if (!result.success) {
                if (result.stderr.isNotEmpty()) result.stderr
                else if (result.stdout.isNotEmpty()) result.stdout
                else "启动失败（无详细信息）"
            } else null
            _uiState.value = _uiState.value.copy(
                isStarting = false,
                errorMessage = errorMsg
            )
            refreshState()
        }
    }fun stopBot() {
        viewModelScope.launch(Dispatchers.IO) {
            _uiState.value = _uiState.value.copy(isStopping = true)
            botBridge.stopBot()
            _uiState.value = _uiState.value.copy(isStopping = false)
            refreshState()
        }
    }

    fun restartBot() {
        viewModelScope.launch(Dispatchers.IO) {
            _uiState.value = _uiState.value.copy(isStarting = true, errorMessage = null)
            val result = botBridge.restartBot()
            val errorMsg = if (!result.success) {
                if (result.stderr.isNotEmpty()) result.stderr
                else if (result.stdout.isNotEmpty()) result.stdout
                else "重启失败（无详细信息）"
            } else null
            _uiState.value = _uiState.value.copy(
                isStarting = false,
                errorMessage = errorMsg
            )
            refreshState()
        }
    }

    fun startSsh() {
        viewModelScope.launch(Dispatchers.IO) {
            val result = if (AuthManager.instance.isProotMode) {
                prootManager.startSshService()
            } else {
                ChrootManager.startSshService()
            }
            if (!result.success) {
                val msg = if (result.stderr.isNotEmpty()) result.stderr
                    else if (result.stdout.isNotEmpty()) result.stdout
                    else "SSH 启动失败（无详细信息）"
                _uiState.value = _uiState.value.copy(errorMessage = msg)
            }
            refreshState()
        }
    }fun stopSsh() {
        viewModelScope.launch(Dispatchers.IO) {
            if (AuthManager.instance.isProotMode) {
                prootManager.stopSshService()
            } else {
                ChrootManager.stopSshService()
            }
            refreshState()
        }
    }

    fun setSshPassword(password: String) {
        viewModelScope.launch(Dispatchers.IO) {
            if (AuthManager.instance.isProotMode) {
                prootManager.runInProot(
                    "echo 'root:$password' | chpasswd 2>/dev/null; " +
                    "printf '%s' '$password' > /root/.rbot_pass && chmod 600 /root/.rbot_pass", 10
                )
            } else {
                ChrootManager.setRootPassword(password)
            }
            refreshState()
        }
    }

    // ─── 更新检查 ───

    fun checkForUpdates() {
        viewModelScope.launch(Dispatchers.IO) {
            val updateInfo = UpdateChecker.check(context)
            updateInfo?.let {
                _uiState.value = _uiState.value.copy(
                    latestVersion = it.latestVersion,
                    isUpdateAvailable = true
                )
            }
        }
    }

    fun dismissUpdate(version: String) {
        UpdateChecker.dismissVersion(context, version)
        _uiState.value = _uiState.value.copy(isUpdateAvailable = false)
    }

    // ─── 日志 ───

    private val _logContent = MutableStateFlow("")
    val logContent: StateFlow<String> = _logContent.asStateFlow()

    fun refreshLog() {
        viewModelScope.launch(Dispatchers.IO) {
            val log = if (AuthManager.instance.isProotMode) {
                PRootManager.readTail(prootManager.astrBotLogFile, 100)
            } else {
                val result = ChrootManager.execRoot("tail -100 ${RbotPaths.ASTRBOT_LOG_FILE}", 5)
                if (result.success) result.stdout else ""
            }
            _logContent.value = log
        }
    }

    // ─── 辅助 ───

    fun dismissError() {
        _uiState.value = _uiState.value.copy(errorMessage = null)
    }

    private fun getLanIpAddress(): String {
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces() ?: return ""
            for (intf in interfaces) {
                for (addr in intf.inetAddresses) {
                    if (!addr.isLoopbackAddress && addr is Inet4Address) {
                        return addr.hostAddress ?: ""
                    }
                }
            }
        } catch (_: Exception) { }
        return ""
    }
}
