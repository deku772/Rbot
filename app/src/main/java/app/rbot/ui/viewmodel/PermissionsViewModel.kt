package app.rbot.ui.viewmodel

import android.Manifest
import android.content.Context
import android.content.Intent
import android.net.Uri
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
import app.rbot.core.PRootManager
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

            _uiState.value = PermissionsUiState(
                batteryOptimized = batteryOpt,
                storageGranted = storageGranted,
                notificationGranted = notifGranted,
                rootAvailable = rootAvailable,
                authMode = AuthManager.instance.currentMode,
                isProotBinaryAvailable = true // simplified
            )
        }
    }

    fun setChrootMode() {
        AuthManager.instance.forceProot = false
        refreshAll()
    }

    fun setProotMode() {
        AuthManager.instance.forceProot = true
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isProotDownloading = true)
            // PRoot 二进制下载逻辑
            _uiState.value = _uiState.value.copy(isProotDownloading = false)
        }
        refreshAll()
    }
}
