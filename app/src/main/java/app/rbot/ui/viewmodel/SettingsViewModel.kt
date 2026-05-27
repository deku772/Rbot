package app.rbot.ui.viewmodel

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.rbot.core.*
import app.rbot.data.model.SettingsUiState
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * 设置 ViewModel — 替代旧版 SettingsActivity。
 */
@HiltViewModel
class SettingsViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val botBridge: BotBridge
) : ViewModel() {

    private val prefs = context.getSharedPreferences("rbot_settings", Context.MODE_PRIVATE)

    private val _uiState = MutableStateFlow(SettingsUiState())
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

    // ─── 备份状态 ───

    private val _backupState = MutableStateFlow<BackupState>(BackupState.Idle)
    val backupState: StateFlow<BackupState> = _backupState.asStateFlow()

    sealed class BackupState {
        object Idle : BackupState()
        data class InProgress(val msg: String) : BackupState()
        data class Done(val msg: String) : BackupState()
        data class Error(val msg: String) : BackupState()
    }

    init {
        loadAppInfo()
        loadAutoStartSetting()
        // 不自动测速，等用户手动点击
    }

    private fun loadAppInfo() {
        val appVersion = try {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "未知"
        } catch (_: Exception) { "未知" }

        val astrbotVersion = if (botBridge.isBotInstalled()) {
            try {
                val homePath = botBridge.getHomePath()
                val versionFile = java.io.File("$homePath/astrbot-version.txt")
                if (versionFile.exists()) versionFile.readText().trim() else null
            } catch (_: Exception) { null }
        } else null

        _uiState.value = _uiState.value.copy(
            appVersion = appVersion,
            astrbotVersion = astrbotVersion
        )
    }

    private fun loadAutoStartSetting() {
        val autoStart = prefs.getBoolean("auto_start_on_boot", false)
        _uiState.value = _uiState.value.copy(autoStartOnBoot = autoStart)
    }

    fun setAutoStartOnBoot(enabled: Boolean) {
        prefs.edit().putBoolean("auto_start_on_boot", enabled).apply()
        _uiState.value = _uiState.value.copy(autoStartOnBoot = enabled)
    }

    // ─── 代理管理 ───

    fun testProxies() {
        viewModelScope.launch(Dispatchers.IO) {
            _uiState.value = _uiState.value.copy(isTestingProxy = true)
            val results = GitHubProxyManager.testProxies()
            _uiState.value = _uiState.value.copy(
                proxyInfo = results,
                isTestingProxy = false,
                customProxy = GitHubProxyManager.getCustomProxy()
            )
        }
    }

    fun addProxy(name: String, url: String) {
        GitHubProxyManager.addProxy(name, url)
        testProxies()
    }

    fun editProxy(index: Int, name: String, url: String) {
        GitHubProxyManager.updateProxy(index, name, url)
        // 刷新测速
        testProxies()
    }

    fun removeProxy(index: Int) {
        GitHubProxyManager.removeProxy(index)
        testProxies()
    }

    fun setCustomProxy(url: String) {
        GitHubProxyManager.setCustomProxy(url)
        _uiState.value = _uiState.value.copy(customProxy = url)
    }

    // ─── 备份与恢复 ───

    fun backupAstrBot() {
        viewModelScope.launch(Dispatchers.IO) {
            _backupState.value = BackupState.InProgress("正在备份...")
            val callback = object : ChrootManager.FullProgressCallback {
                override fun onProgress(msg: String) { _backupState.value = BackupState.InProgress(msg) }
                override fun onError(msg: String) { _backupState.value = BackupState.Error(msg) }
            }
            val file = ChrootManager.backupAstrBotData(callback)
            if (file != null) {
                _backupState.value = BackupState.Done("备份完成: $file")
            } else if (_backupState.value !is BackupState.Error) {
                _backupState.value = BackupState.Error("备份失败")
            }
        }
    }

    fun listBackups(): List<String> {
        return ChrootManager.listBackups().toList()
    }

    fun restoreAstrBot(backupFile: String) {
        viewModelScope.launch(Dispatchers.IO) {
            _backupState.value = BackupState.InProgress("正在恢复...")
            val callback = object : ChrootManager.FullProgressCallback {
                override fun onProgress(msg: String) { _backupState.value = BackupState.InProgress(msg) }
                override fun onError(msg: String) { _backupState.value = BackupState.Error(msg) }
            }
            // 注意：restoreAstrBotData 返回 true=失败, false=成功（旧 Java 惯例）
            val failed = ChrootManager.restoreAstrBotData(backupFile, callback)
            if (!failed) {
                _backupState.value = BackupState.Done("恢复完成")
            } else if (_backupState.value !is BackupState.Error) {
                _backupState.value = BackupState.Error("恢复失败")
            }
        }
    }

    fun deleteBackup(backupFile: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val file = File(backupFile)
            if (file.exists()) file.delete()
        }
    }

    fun resetBackupState() {
        _backupState.value = BackupState.Idle
    }
}
