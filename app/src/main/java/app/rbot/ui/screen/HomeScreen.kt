package app.rbot.ui.screen

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import app.rbot.core.AuthManager
import app.rbot.core.BotBridge
import app.rbot.ui.viewmodel.HomeViewModel

/**
 * 主页 Screen — 替代旧版 MainActivity (734 行)。
 * 声明式 UI，状态驱动渲染，无需手动 setText / setOnClickListener。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    onNavigateToPermissions: () -> Unit = {},
    viewModel: HomeViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Rbot") },
                actions = {
                    IconButton(onClick = onNavigateToPermissions) {
                        Icon(Icons.Filled.Settings, contentDescription = "权限设置")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // ─── 状态卡片 ───
            StatusCard(uiState)

            // ─── 控制按钮 ───
            ControlButtons(uiState, onStart = viewModel::startBot, onStop = viewModel::stopBot)

            // ─── WebUI 面板 ───
            WebUIPanel(uiState)

            // ─── SSH 面板 ───
            SSHPanel(uiState, viewModel)

            // ─── 授权模式 + 切换按钮 ───
            AuthModeLabel(uiState.authMode, onNavigateToPermissions)

            // ─── 错误提示 ───
            uiState.errorMessage?.let { error ->
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer
                    )
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Filled.Error, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(error, style = MaterialTheme.typography.bodyMedium)
                        Spacer(modifier = Modifier.weight(1f))
                        TextButton(onClick = viewModel::dismissError) {
                            Text("关闭")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun StatusCard(uiState: app.rbot.data.model.HomeUiState) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = when (uiState.botState) {
                    BotBridge.State.RUNNING -> Icons.Filled.CheckCircle
                    BotBridge.State.ERROR -> Icons.Filled.Error
                    else -> Icons.Filled.PlayArrow
                },
                contentDescription = null,
                tint = when (uiState.botState) {
                    BotBridge.State.RUNNING -> MaterialTheme.colorScheme.primary
                    BotBridge.State.ERROR -> MaterialTheme.colorScheme.error
                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                }
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column {
                Text(
                    text = when (uiState.botState) {
                        BotBridge.State.RUNNING -> "AstrBot 运行中"
                        BotBridge.State.READY -> "AstrBot 已就绪"
                        BotBridge.State.NOT_INSTALLED -> "AstrBot 未安装"
                        BotBridge.State.INSTALLING -> "安装中..."
                        BotBridge.State.UPDATING -> "更新中..."
                        BotBridge.State.ERROR -> "出错"
                    },
                    style = MaterialTheme.typography.titleMedium
                )
                Text(
                    text = "模式: ${when (uiState.authMode) {
                        AuthManager.AuthMode.ROOT -> "Root (chroot)"
                        AuthManager.AuthMode.PROOT -> "PRoot (免 Root)"
                        AuthManager.AuthMode.UNAVAILABLE -> "不可用"
                    }}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun ControlButtons(
    uiState: app.rbot.data.model.HomeUiState,
    onStart: () -> Unit,
    onStop: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Button(
            onClick = onStart,
            enabled = uiState.isInstalled && !uiState.isRunning && !uiState.isStarting,
            modifier = Modifier.weight(1f)
        ) {
            if (uiState.isStarting) {
                CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                Spacer(modifier = Modifier.width(8.dp))
            }
            Text("启动")
        }

        OutlinedButton(
            onClick = onStop,
            enabled = uiState.isRunning && !uiState.isStopping,
            modifier = Modifier.weight(1f)
        ) {
            if (uiState.isStopping) {
                CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                Spacer(modifier = Modifier.width(8.dp))
            }
            Text("停止")
        }
    }
}

@Composable
private fun ComponentStatusGrid(status: BotBridge.ComponentStatus) {
    Text("组件状态", style = MaterialTheme.typography.titleSmall)
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        ComponentChip("二进制", status.binaries, Modifier.weight(1f))
        ComponentChip("rootfs", status.rootfs, Modifier.weight(1f))
    }
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        ComponentChip("初始化", status.bootstrap, Modifier.weight(1f))
        ComponentChip("AstrBot", status.astrbot, Modifier.weight(1f))
    }
}

@Composable
private fun ComponentChip(
    label: String,
    state: BotBridge.ComponentState,
    modifier: Modifier = Modifier
) {
    AssistChip(
        onClick = { },
        label = { Text(label, style = MaterialTheme.typography.labelSmall) },
        leadingIcon = {
            Box(
                modifier = Modifier.size(8.dp),
                contentAlignment = Alignment.Center
            ) {
                Surface(
                    modifier = Modifier.size(8.dp),
                    shape = MaterialTheme.shapes.extraSmall,
                    color = when (state) {
                        BotBridge.ComponentState.READY -> MaterialTheme.colorScheme.primary
                        BotBridge.ComponentState.ERROR -> MaterialTheme.colorScheme.error
                        BotBridge.ComponentState.INSTALLING -> MaterialTheme.colorScheme.tertiary
                        BotBridge.ComponentState.NOT_INSTALLED -> MaterialTheme.colorScheme.outline
                    }
                ) {}
            }
        },
        modifier = modifier
    )
}

@Composable
private fun WebUIPanel(uiState: app.rbot.data.model.HomeUiState) {
    if (uiState.webUIUrl.isNotEmpty()) {
        val context = LocalContext.current
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("WebUI 管理面板", style = MaterialTheme.typography.titleSmall)
                Spacer(modifier = Modifier.height(4.dp))
                Text(uiState.webUIUrl, style = MaterialTheme.typography.bodyMedium)
                if (uiState.webUILanUrl.isNotEmpty()) {
                    Text(uiState.webUILanUrl, style = MaterialTheme.typography.bodySmall)
                }
                Spacer(modifier = Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = {
                        openInBrowser(context, uiState.webUIUrl)
                    }) {
                        Text("本机打开")
                    }
                    if (uiState.webUILanUrl.isNotEmpty()) {
                        OutlinedButton(onClick = {
                            openInBrowser(context, uiState.webUILanUrl)
                        }) {
                            Text("局域网打开")
                        }
                    }
                }
            }
        }
    }
}

/** 用浏览器打开 URL，避免被 WebView 等组件拦截导致黑屏 */
private fun openInBrowser(context: Context, url: String) {
    try {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
            addCategory(Intent.CATEGORY_BROWSABLE)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        context.startActivity(intent)
    } catch (e: Exception) {
        // 没有浏览器可用时，尝试普通 ACTION_VIEW 回退
        try {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (_: Exception) { }
    }
}

@Composable
private fun SSHPanel(
    uiState: app.rbot.data.model.HomeUiState,
    viewModel: HomeViewModel
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("SSH 访问", style = MaterialTheme.typography.titleSmall)
                if (uiState.sshInfo.isRunning) {
                    OutlinedButton(onClick = viewModel::stopSsh) { Text("停止 SSH") }
                } else {
                    Button(onClick = viewModel::startSsh) { Text("启动 SSH") }
                }
            }
            Spacer(modifier = Modifier.height(4.dp))
            Text("主机: ${uiState.sshInfo.host}", style = MaterialTheme.typography.bodyMedium)
            Text("端口: ${uiState.sshInfo.port}", style = MaterialTheme.typography.bodyMedium)
            Text("用户: ${uiState.sshInfo.user}", style = MaterialTheme.typography.bodyMedium)
            Text("密码: ${uiState.sshInfo.password}", style = MaterialTheme.typography.bodyMedium)
            if (uiState.sshInfo.lanHost.isNotEmpty()) {
                Text("局域网: ${uiState.sshInfo.lanHost}:${uiState.sshInfo.port}",
                    style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun AuthModeLabel(authMode: AuthManager.AuthMode, onNavigateToPermissions: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = "授权模式: ${authMode.name}",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        TextButton(onClick = onNavigateToPermissions) {
            Text("安装向导", style = MaterialTheme.typography.labelSmall)
        }
    }
}
