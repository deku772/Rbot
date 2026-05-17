package app.rbot.ui.viewmodel

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.rbot.core.BotBridge
import app.rbot.core.GitHubProxyManager
import app.rbot.data.model.SettingsUiState
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
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

    private val _uiState = MutableStateFlow(SettingsUiState())
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

    init {
        loadAppInfo()
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

    fun setCustomProxy(url: String) {
        GitHubProxyManager.setCustomProxy(url)
        _uiState.value = _uiState.value.copy(customProxy = url)
    }
}
