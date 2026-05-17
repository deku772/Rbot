package app.rbot.ui.screen

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import app.rbot.core.AuthManager
import app.rbot.core.GitHubProxyManager
import app.rbot.ui.viewmodel.SettingsViewModel

/**
 * 设置 Screen — 替代旧版 SettingsActivity。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(viewModel: SettingsViewModel = hiltViewModel()) {
    val uiState by viewModel.uiState.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(title = { Text("设置") })
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
            // ─── 代理设置 ───
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("GitHub 代理", style = MaterialTheme.typography.titleSmall)
                        if (uiState.isTestingProxy) {
                            CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                        } else {
                            IconButton(onClick = viewModel::testProxies) {
                                Icon(Icons.Filled.Refresh, contentDescription = "测速")
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    uiState.proxyInfo.forEach { proxy ->
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(proxy.name, style = MaterialTheme.typography.bodyMedium)
                            Text(
                                GitHubProxyManager.getLatencyString(proxy.index),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    OutlinedTextField(
                        value = uiState.customProxy,
                        onValueChange = viewModel::setCustomProxy,
                        label = { Text("自定义代理 URL") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
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
                    if (uiState.isUpdateAvailable) {
                        uiState.latestVersion?.let {
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                "发现新版本: v$it",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                }
            }
        }
    }
}
