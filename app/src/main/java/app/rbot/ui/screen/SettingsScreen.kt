package app.rbot.ui.screen

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import app.rbot.ui.viewmodel.SettingsViewModel

/**
 * 设置 Screen — 替代旧版 SettingsActivity。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(viewModel: SettingsViewModel = hiltViewModel()) {
    val uiState by viewModel.uiState.collectAsState()
    val backupState by viewModel.backupState.collectAsState()
    var showRestoreDialog by remember { mutableStateOf(false) }
    var showAddProxyDialog by remember { mutableStateOf(false) }
    var backups by remember { mutableStateOf<List<String>>(emptyList()) }

    Scaffold(
        topBar = { TopAppBar(title = { Text("设置") }) }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // ─── GitHub 代理 ───
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("GitHub 代理", style = MaterialTheme.typography.titleSmall)
                        Row {
                            if (uiState.isTestingProxy) {
                                CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                            } else {
                                IconButton(onClick = viewModel::testProxies) {
                                    Icon(Icons.Filled.Refresh, contentDescription = "测速")
                                }
                            }
                            IconButton(onClick = { showAddProxyDialog = true }) {
                                Icon(Icons.Filled.Add, contentDescription = "新增")
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    // 内嵌代理列表（可编辑、删除）
                    if (uiState.proxyInfo.isEmpty() && !uiState.isTestingProxy) {
                        Text("正在加载...", style = MaterialTheme.typography.bodySmall)
                    } else {
                        uiState.proxyInfo.forEach { proxy ->
                            ProxyListItem(
                                proxy = proxy,
                                onEdit = { name, url ->
                                    viewModel.editProxy(proxy.index, name, url)
                                },
                                onDelete = { viewModel.removeProxy(proxy.index) }
                            )
                        }
                    }
                }
            }

            // ─── 备份与恢复 ───
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("备份与恢复", style = MaterialTheme.typography.titleSmall)
                    Spacer(modifier = Modifier.height(8.dp))

                    when (val state = backupState) {
                        is SettingsViewModel.BackupState.Idle -> {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(onClick = { viewModel.backupAstrBot() }) {
                                    Icon(Icons.Filled.Archive, contentDescription = null, modifier = Modifier.size(18.dp))
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text("备份数据")
                                }
                                OutlinedButton(onClick = {
                                    backups = viewModel.listBackups()
                                    showRestoreDialog = true
                                }) {
                                    Icon(Icons.Filled.Restore, contentDescription = null, modifier = Modifier.size(18.dp))
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text("恢复数据")
                                }
                            }
                        }
                        is SettingsViewModel.BackupState.InProgress -> {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(state.msg, style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                        is SettingsViewModel.BackupState.Done -> {
                            Text(state.msg, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
                            Spacer(modifier = Modifier.height(8.dp))
                            OutlinedButton(onClick = { viewModel.resetBackupState() }) { Text("关闭") }
                        }
                        is SettingsViewModel.BackupState.Error -> {
                            Text(state.msg, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
                            Spacer(modifier = Modifier.height(8.dp))
                            OutlinedButton(onClick = { viewModel.resetBackupState() }) { Text("关闭") }
                        }
                    }
                }
            }

            // ─── 通用设置 ───
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("通用", style = MaterialTheme.typography.titleSmall)
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("开机自启动", style = MaterialTheme.typography.bodyMedium)
                            Text("设备启动时自动运行 AstrBot", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Switch(
                            checked = uiState.autoStartOnBoot,
                            onCheckedChange = viewModel::setAutoStartOnBoot
                        )
                    }
                }
            }

            // ─── 关于 ───
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("关于", style = MaterialTheme.typography.titleSmall)
                    Spacer(modifier = Modifier.height(8.dp))
                    Text("Rbot ${uiState.appVersion}", style = MaterialTheme.typography.bodyMedium)
                    uiState.astrbotVersion?.let {
                        Text("AstrBot $it", style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        }
    }

    // 恢复选择对话框
    if (showRestoreDialog) {
        AlertDialog(
            onDismissRequest = { showRestoreDialog = false },
            title = { Text("选择备份文件") },
            text = {
                if (backups.isEmpty()) {
                    Text("没有找到备份文件")
                } else {
                    Column {
                        backups.forEach { backup ->
                            TextButton(onClick = {
                                showRestoreDialog = false
                                viewModel.restoreAstrBot(backup)
                            }) {
                                Text(backup.substringAfterLast("/"))
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showRestoreDialog = false }) { Text("取消") }
            }
        )
    }

    // 新增代理对话框
    if (showAddProxyDialog) {
        var newName by remember { mutableStateOf("") }
        var newUrl by remember { mutableStateOf("https://") }
        AlertDialog(
            onDismissRequest = { showAddProxyDialog = false },
            title = { Text("新增代理") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = newName,
                        onValueChange = { newName = it },
                        label = { Text("名称") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                    OutlinedTextField(
                        value = newUrl,
                        onValueChange = { newUrl = it },
                        label = { Text("URL 前缀") },
                        placeholder = { Text("https://example.com/") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (newName.isNotBlank()) {
                            viewModel.addProxy(newName, newUrl)
                        }
                        showAddProxyDialog = false
                    },
                    enabled = newName.isNotBlank()
                ) { Text("添加") }
            },
            dismissButton = {
                TextButton(onClick = { showAddProxyDialog = false }) { Text("取消") }
            }
        )
    }
}

@Composable
private fun ProxyListItem(
    proxy: app.rbot.core.GitHubProxyManager.ProxyInfo,
    onEdit: (name: String, url: String) -> Unit,
    onDelete: () -> Unit
) {
    var showEditDialog by remember { mutableStateOf(false) }

    if (showEditDialog) {
        var editName by remember { mutableStateOf(proxy.name) }
        var editUrl by remember { mutableStateOf(proxy.template) }
        AlertDialog(
            onDismissRequest = { showEditDialog = false },
            title = { Text("编辑代理") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = editName,
                        onValueChange = { editName = it },
                        label = { Text("名称") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                    OutlinedTextField(
                        value = editUrl,
                        onValueChange = { editUrl = it },
                        label = { Text("URL 前缀") },
                        placeholder = { Text("https://example.com/") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    onEdit(editName, editUrl)
                    showEditDialog = false
                }) { Text("保存") }
            },
            dismissButton = {
                TextButton(onClick = { showEditDialog = false }) { Text("取消") }
            }
        )
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(proxy.name, style = MaterialTheme.typography.bodyMedium)
            if (proxy.template.isNotEmpty()) {
                Text(proxy.template, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        val latencyStr = when {
            proxy.latencyMs < 0 -> "超时"
            proxy.latencyMs == 0 -> "未测试"
            proxy.latencyMs < 500 -> "快 ${proxy.latencyMs}ms"
            proxy.latencyMs < 2000 -> "中 ${proxy.latencyMs}ms"
            else -> "慢 ${proxy.latencyMs}ms"
        }
        Text(latencyStr, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

        if (proxy.index > 0) {
            IconButton(onClick = { showEditDialog = true }) {
                Icon(Icons.Filled.Edit, contentDescription = "编辑", modifier = Modifier.size(20.dp))
            }
            IconButton(onClick = onDelete) {
                Icon(Icons.Filled.Delete, contentDescription = "删除", modifier = Modifier.size(20.dp))
            }
        }
    }
}
