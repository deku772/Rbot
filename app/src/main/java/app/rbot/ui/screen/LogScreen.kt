package app.rbot.ui.screen

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import app.rbot.data.model.LogLine
import app.rbot.ui.viewmodel.LogViewModel

/**
 * 日志 Screen — 替代旧版 LogActivity。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LogScreen(viewModel: LogViewModel = hiltViewModel()) {
    val uiState by viewModel.uiState.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("日志") },
                actions = {
                    IconButton(onClick = viewModel::refreshLogs) {
                        Icon(Icons.Filled.Refresh, contentDescription = "刷新")
                    }
                    IconButton(onClick = viewModel::clearLogs) {
                        Icon(Icons.Filled.Delete, contentDescription = "清空")
                    }
                }
            )
        }
    ) { padding ->
        if (uiState.logLines.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentAlignment = androidx.compose.ui.Alignment.Center
            ) {
                Text(
                    "暂无日志",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            SelectionContainer {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding)
                        .padding(horizontal = 8.dp)
                ) {
                    items(uiState.logLines) { line ->
                        LogLineItem(line)
                    }
                }
            }
        }
    }
}

@Composable
private fun LogLineItem(line: LogLine) {
    Text(
        text = line.text,
        style = MaterialTheme.typography.bodySmall,
        color = if (line.isError) MaterialTheme.colorScheme.error
        else MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.padding(vertical = 1.dp)
    )
}
