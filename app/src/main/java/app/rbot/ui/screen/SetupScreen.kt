package app.rbot.ui.screen

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import app.rbot.ui.viewmodel.SetupViewModel

/**
 * 安装向导 Screen — 替代旧版 SetupActivity（755 行）。
 * 使用 Compose 声明式 UI + 协程 ViewModel。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SetupScreen(
    onNavigateBack: () -> Unit = {},
    onInstallComplete: () -> Unit = {},
    viewModel: SetupViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val logs = remember { mutableListOf<String>() }

    // 收集日志流
    LaunchedEffect(Unit) {
        viewModel.logFlow.collect { line ->
            logs.add(line)
        }
    }

    // 安装完成后自动导航
    LaunchedEffect(uiState.isComplete) {
        if (uiState.isComplete) {
            kotlinx.coroutines.delay(2000)
            onInstallComplete()
        }
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
            // ─── 进度指示 ───
            Text(uiState.stepLabel, style = MaterialTheme.typography.titleMedium)

            if (uiState.isInstalling) {
                LinearProgressIndicator(
                    progress = { uiState.progress },
                    modifier = Modifier.fillMaxWidth()
                )

                Text(
                    "步骤 ${uiState.currentStep + 1}/5",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // ─── 安装选项 ───
            if (!uiState.isInstalling && !uiState.isComplete) {
                var reinstallRootfs by remember { mutableStateOf(false) }
                var reinstallDeps by remember { mutableStateOf(false) }
                var reinstallBot by remember { mutableStateOf(false) }

                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text("安装选项", style = MaterialTheme.typography.titleSmall)
                        Spacer(modifier = Modifier.height(8.dp))

                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(checked = reinstallRootfs, onCheckedChange = { reinstallRootfs = it })
                            Text("重装系统镜像")
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(checked = reinstallDeps, onCheckedChange = { reinstallDeps = it })
                            Text("重装系统依赖")
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(checked = reinstallBot, onCheckedChange = { reinstallBot = it })
                            Text("重装 AstrBot")
                        }

                        Spacer(modifier = Modifier.height(12.dp))

                        Button(
                            onClick = {
                                viewModel.startInstallation(
                                    reinstallRootfs = reinstallRootfs,
                                    reinstallDeps = reinstallDeps,
                                    reinstallBot = reinstallBot
                                )
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("开始安装")
                        }
                    }
                }
            }

            // ─── 错误提示 ───
            uiState.errorMessage?.let { error ->
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer
                    )
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
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer
                    )
                ) {
                    Text(
                        "安装完成！即将跳转到主界面...",
                        modifier = Modifier.padding(12.dp),
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
            }

            // ─── 日志区域 ───
            Card(modifier = Modifier.weight(1f)) {
                Column(modifier = Modifier.padding(8.dp)) {
                    Text("安装日志", style = MaterialTheme.typography.labelMedium)
                    Spacer(modifier = Modifier.height(4.dp))
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .verticalScroll(rememberScrollState())
                    ) {
                        logs.forEach { line ->
                            Text(
                                line,
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
