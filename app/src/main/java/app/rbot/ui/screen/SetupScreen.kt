package app.rbot.ui.screen

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import app.rbot.ui.viewmodel.ProxyPickerState
import app.rbot.ui.viewmodel.SetupViewModel
import app.rbot.ui.viewmodel.VersionPickerState

/**
 * 安装向导 Screen — 替代旧版 SetupActivity。
 * 进入时自动检测现有环境，用户可选择性重装。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SetupScreen(
    onNavigateBack: () -> Unit = {},
    onInstallComplete: () -> Unit = {},
    viewModel: SetupViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val logLines = remember { mutableStateListOf<String>() }

    // 收集日志流

    LaunchedEffect(Unit) {
        viewModel.logFlow.collect { line -> logLines.add(line) }
    }

    // 安装完成后自动导航
    LaunchedEffect(uiState.isComplete) {
        if (uiState.isComplete) {
            kotlinx.coroutines.delay(1500)
            onInstallComplete()
        }
    }

    // 代理选择对话框
    val proxyPickerState by viewModel.proxyPickerState.collectAsState()
    proxyPickerState?.let { proxyState ->
        ProxyPickerDialog(
            state = proxyState,
            onSelect = viewModel::onProxySelected,
            onDismiss = { viewModel.onProxySelected(proxyState.selectedIndex) }
        )
    }

    // 版本选择对话框
    val versionPickerState by viewModel.versionPickerState.collectAsState()
    versionPickerState?.let { versionState ->
        VersionPickerDialog(
            state = versionState,
            onSelect = { ver, idx -> viewModel.onVersionSelected(ver, idx) },
            onDismiss = viewModel::onVersionPickerCancelled
        )
    }

    // 备份恢复提示
    val restorePromptState by viewModel.restorePromptState.collectAsState()
    if (restorePromptState) {
        AlertDialog(
            onDismissRequest = { viewModel.onRestoreConfirmed(false) },
            title = { Text("发现备份数据") },
            text = { Text("是否恢复之前的 AstrBot 数据？") },
            confirmButton = { TextButton(onClick = { viewModel.onRestoreConfirmed(true) }) { Text("恢复") } },
            dismissButton = { TextButton(onClick = { viewModel.onRestoreConfirmed(false) }) { Text("跳过") } }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (uiState.isInstalling) "正在安装" else "安装向导") },
                navigationIcon = {
                    if (!uiState.isInstalling) {
                        IconButton(onClick = onNavigateBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                        }
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
            // ─── 环境检测中 ───
            if (uiState.isDetecting) {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator(modifier = Modifier.size(32.dp))
                        Spacer(modifier = Modifier.height(8.dp))
                        Text("正在检测安装环境...", style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }

            // ─── 环境状态 ───
            if (!uiState.isDetecting && !uiState.isInstalling && !uiState.isComplete) {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text("当前环境", style = MaterialTheme.typography.titleSmall)
                        Spacer(modifier = Modifier.height(8.dp))

                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                if (uiState.rootfsReady) "✅" else "❌",
                                style = MaterialTheme.typography.bodyLarge
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("系统镜像 (rootfs)", style = MaterialTheme.typography.bodyMedium)
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                if (uiState.botInstalled) "✅" else "❌",
                                style = MaterialTheme.typography.bodyLarge
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("AstrBot", style = MaterialTheme.typography.bodyMedium)
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            "运行模式: Chroot (Root)",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            // ─── 全部已安装 → 直接完成 ───
            if (!uiState.isDetecting && uiState.rootfsReady && uiState.botInstalled
                && !uiState.isInstalling && !uiState.isComplete
            ) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            "所有组件已安装，可以直接使用！",
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = onInstallComplete) { Text("完成") }
                            OutlinedButton(onClick = { viewModel.startInstallation() }) { Text("重装") }
                        }
                    }
                }
            }

            // ─── 安装选项（未安装或部分安装时） ───
            if (!uiState.isDetecting && !uiState.isInstalling && !uiState.isComplete
                && !(uiState.rootfsReady && uiState.botInstalled)
            ) {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text("安装选项", style = MaterialTheme.typography.titleSmall)
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            "缺失的组件已自动选中安装",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(8.dp))

                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(
                                checked = uiState.reinstallRootfs,
                                onCheckedChange = { viewModel.updateReinstallOption(reinstallRootfs = it) }
                            )
                            Text(if (uiState.rootfsReady) "重装系统镜像 (已存在)" else "安装系统镜像")
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(
                                checked = uiState.reinstallDeps,
                                onCheckedChange = { viewModel.updateReinstallOption(reinstallDeps = it) }
                            )
                            Text("重装系统依赖")
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(
                                checked = uiState.reinstallBot,
                                onCheckedChange = { viewModel.updateReinstallOption(reinstallBot = it) }
                            )
                            Text(if (uiState.botInstalled) "重装 AstrBot (已存在)" else "安装 AstrBot")
                        }

                        Spacer(modifier = Modifier.height(12.dp))

                        Button(
                            onClick = {
                                viewModel.startInstallation(
                                    reinstallRootfs = uiState.reinstallRootfs,
                                    reinstallDeps = uiState.reinstallDeps,
                                    reinstallBot = uiState.reinstallBot
                                )
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("开始安装")
                        }
                    }
                }
            }

            // ─── 进度指示 ───
            if (uiState.isInstalling) {
                Text(uiState.stepLabel, style = MaterialTheme.typography.titleMedium)
                LinearProgressIndicator(
                    progress = { uiState.progress },
                    modifier = Modifier.fillMaxWidth()
                )
                Text(
                    "步骤 ${uiState.currentStep + 1}/${uiState.totalSteps}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // ─── 错误提示 ───
            uiState.errorMessage?.let { error ->
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
                ) {
                    Text(
                        error,
                        modifier = Modifier.padding(12.dp),
                        color = MaterialTheme.colorScheme.onErrorContainer
                    )
                }
            }

            // ─── 完成提示 ───
            if (uiState.isComplete) {
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
                ) {
                    Text(
                        "安装完成！即将跳转...",
                        modifier = Modifier.padding(12.dp),
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
            }

            // ─── 日志区域 ───
            Card(modifier = Modifier.weight(1f).fillMaxWidth()) {
                Column(modifier = Modifier.padding(8.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("安装日志", style = MaterialTheme.typography.labelMedium)
                        val context = LocalContext.current
                        TextButton(
                            onClick = {
                                val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                                clipboard.setPrimaryClip(android.content.ClipData.newPlainText("安装日志", logLines.joinToString("\n")))
                                android.widget.Toast.makeText(context, "日志已复制", android.widget.Toast.LENGTH_SHORT).show()
                            }
                        ) {
                            Text("复制", style = MaterialTheme.typography.labelSmall)
                        }
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    SelectionContainer(modifier = Modifier.weight(1f).fillMaxWidth()) {
                        LazyColumn(
                            modifier = Modifier
                                .fillMaxSize(),
                            reverseLayout = false
                        ) {
                            items(count = logLines.size, key = { it }) { index ->
                                Text(
                                    logLines[index],
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ProxyPickerDialog(
    state: ProxyPickerState,
    onSelect: (Int) -> Unit,
    onDismiss: () -> Unit
) {
    var selected by remember { mutableStateOf(state.selectedIndex) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("选择下载线路") },
        text = {
            Column {
                state.proxies.forEach { proxy ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        RadioButton(selected = selected == proxy.index, onClick = { selected = proxy.index })
                        Column {
                            Text(proxy.name)
                            Text(
                                when {
                                    proxy.latencyMs < 0 -> "超时"
                                    proxy.latencyMs == 0 -> "测试中..."
                                    else -> "${proxy.latencyMs}ms"
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onSelect(selected) }) { Text("确定") } }
    )
}

@Composable
private fun VersionPickerDialog(
    state: VersionPickerState,
    onSelect: (String, Int) -> Unit,
    onDismiss: () -> Unit
) {
    var selectedIdx by remember { mutableStateOf(0) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("选择版本") },
        text = {
            Column {
                state.versions.forEachIndexed { idx, ver ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        RadioButton(selected = selectedIdx == idx, onClick = { selectedIdx = idx })
                        Text(ver)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onSelect(state.versions.getOrElse(selectedIdx) { "" }, selectedIdx)
            }) { Text("确定") }
        }
    )
}
