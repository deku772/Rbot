package app.rbot.ui.screen

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.hilt.navigation.compose.hiltViewModel
import app.rbot.core.AuthManager
import app.rbot.ui.viewmodel.PermissionsViewModel

/**
 * 权限页 Screen — 替代旧版 PermissionsActivity（338 行）。
 * 每个权限一张卡片，Material 3 风格。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PermissionsScreen(
    onNavigateBack: () -> Unit = {},
    onStartInstall: () -> Unit = {},
    viewModel: PermissionsViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current

    // 每次 resume 都刷新（用户可能从 su 授权弹窗回来）
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                viewModel.refreshAll()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // 权限请求回调后刷新
    val storageLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { viewModel.refreshAll() }

    val notificationLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { viewModel.refreshAll() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("权限设置") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // ─── 电池优化 ───
            PermissionCard(
                title = "电池优化",
                description = if (uiState.batteryOptimized) "已优化"
                    else "未关闭 — 服务可能被系统自动停止",
                isGranted = uiState.batteryOptimized,
                actionLabel = if (uiState.batteryOptimized) "已优化" else "优化电池设置",
                onAction = {
                    try {
                        val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                            data = Uri.parse("package:${context.packageName}")
                        }
                        context.startActivity(intent)
                    } catch (_: Exception) { }
                }
            )

            // ─── 存储权限 ───
            PermissionCard(
                title = "存储权限",
                description = if (uiState.storageGranted) "已授予"
                    else "未授予 — rootfs 和备份需要此权限",
                isGranted = uiState.storageGranted,
                actionLabel = if (uiState.storageGranted) "已授予" else "授予存储权限",
                onAction = {
                    try {
                        val intent = Intent(
                            Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                            Uri.parse("package:${context.packageName}")
                        )
                        context.startActivity(intent)
                    } catch (_: Exception) { }
                }
            )

            // ─── 通知权限 ───
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                PermissionCard(
                    title = "通知权限",
                    description = if (uiState.notificationGranted) "已授予"
                        else "未授予 — 无法显示服务通知",
                    isGranted = uiState.notificationGranted,
                    actionLabel = if (uiState.notificationGranted) "已授予" else "授予通知权限",
                    onAction = {
                        notificationLauncher.launch(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS))
                    }
                )
            }

            // ─── Root 状态 ───
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Root 权限", style = MaterialTheme.typography.titleSmall)
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        when {
                            uiState.authMode == AuthManager.AuthMode.PROOT -> "无需 — PRoot 模式不需要 Root"
                            uiState.rootAvailable -> "可用"
                            else -> "不可用 — 可使用 PRoot 免 Root 模式"
                        },
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }

            // ─── 运行模式选择 ───
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("运行模式", style = MaterialTheme.typography.titleSmall)
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            onClick = { viewModel.setChrootMode() },
                            enabled = uiState.rootAvailable && uiState.authMode != AuthManager.AuthMode.ROOT
                                    && uiState.authMode != AuthManager.AuthMode.SHIZUKU
                        ) {
                            Text(if (uiState.authMode == AuthManager.AuthMode.ROOT || uiState.authMode == AuthManager.AuthMode.SHIZUKU) "Chroot (已选)" else "Chroot 模式")
                        }
                        Button(
                            onClick = { viewModel.setProotMode() },
                            enabled = uiState.authMode != AuthManager.AuthMode.PROOT
                        ) {
                            Text(if (uiState.authMode == AuthManager.AuthMode.PROOT) "PRoot (已选)" else "PRoot 模式")
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.weight(1f))

            // ─── 开始安装 ───
            Button(
                onClick = onStartInstall,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("开始安装")
            }
        }
    }
}

@Composable
private fun PermissionCard(
    title: String,
    description: String,
    isGranted: Boolean,
    actionLabel: String,
    onAction: () -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleSmall)
                Spacer(modifier = Modifier.height(2.dp))
                Text(description, style = MaterialTheme.typography.bodySmall)
            }
            Spacer(modifier = Modifier.width(8.dp))
            if (isGranted) {
                Text("OK", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelMedium)
            } else {
                TextButton(onClick = onAction) { Text(actionLabel) }
            }
        }
    }
}
