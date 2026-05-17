package app.rbot.ui.viewmodel

import android.Manifest
import android.content.Context
import android.os.Build
import android.os.Environment
import android.os.PowerManager
import android.provider.Settings
import androidx.core.content.ContextCompat
import androidx.core.content.PermissionChecker
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.rbot.core.AuthManager
import app.rbot.core.ChrootManager
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * 权限页 ViewModel — 替代旧版 PermissionsActivity。
 *
 * 关键修复：首次启动时 su 弹授权框会导致 isRootAvailable() 超时返回 false，
 * AuthManager 检测到 PROOT。用户授权 su 后，refreshAll() 需要重新触发
 * detectAndSetMode() 以更新 authMode。
 */
@HiltViewModel
class PermissionsViewModel @Inject constructor(
    @ApplicationContext private val context: Context
) : ViewModel() {

    data class PermissionsUiState(
        val batteryOptimized: Boolean = false,
        val storageGranted: Boolean = false,
        val notificationGranted: Boolean = false,
        val rootAvailable: Boolean = false,
        val authMode: AuthManager.AuthMode = AuthManager.AuthMode.UNAVAILABLE,
        val isProotBinaryAvailable: Boolean = false,
        val isProotDownloading: Boolean = false
    )

    private val _uiState = MutableStateFlow(PermissionsUiState())
    val uiState: StateFlow<PermissionsUiState> = _uiState.asStateFlow()

    fun refreshAll() {
        viewModelScope.launch(Dispatchers.IO) {
            val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
            val batteryOpt = pm?.isIgnoringBatteryOptimizations(context.packageName) ?: true

            val storageGranted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                Environment.isExternalStorageManager()
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE) ==
                    PermissionChecker.PERMISSION_GRANTED
            } else true

            val notifGranted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
                    PermissionChecker.PERMISSION_GRANTED
            } else true

            val rootAvailable = ChrootManager.isRootAvailable()

            // 关键：如果 root 从不可用变为可用，需要重新检测 AuthManager 的模式
            // 因为首次启动时 su 授权弹窗会导致超时，AuthManager 会落入 PROOT，
            // 用户授权后再次进入此页面时，需要让 AuthManager 重新评估
            if (rootAvailable && AuthManager.instance.currentMode == AuthManager.AuthMode.PROOT
                && !AuthManager.instance.forceProot
            ) {
                AuthManager.instance.detectAndSetMode()
            }

            _uiState.value = PermissionsUiState(
                batteryOptimized = batteryOpt,
                storageGranted = storageGranted,
                notificationGranted = notifGranted,
                rootAvailable = rootAvailable,
                authMode = AuthManager.instance.currentMode,
                isProotBinaryAvailable = true
            )
        }
    }

    fun setChrootMode() {
        AuthManager.instance.forceProot = false
        // 重新检测：可能 su 刚被授权
        AuthManager.instance.detectAndSetMode()
        refreshAll()
    }

    fun setProotMode() {
        AuthManager.instance.forceProot = true
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isProotDownloading = true)
            _uiState.value = _uiState.value.copy(isProotDownloading = false)
        }
        refreshAll()
    }
}
