package com.jisungbin.networkinspector.ui.screens

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.jisungbin.networkinspector.log.DiskLogger
import com.jisungbin.networkinspector.ui.AppStore
import com.jisungbin.networkinspector.ui.ThemePreference
import com.jisungbin.networkinspector.ui.UiState
import com.jisungbin.networkinspector.ui.mcp.InspectorMcpServer
import com.jisungbin.networkinspector.ui.mcp.McpLogEntry
import com.jisungbin.networkinspector.ui.mcp.McpLogLevel
import com.jisungbin.networkinspector.ui.mcp.McpServerStatus
import com.jisungbin.networkinspector.ui.util.rememberCopyToClipboard
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(state: UiState, store: AppStore, mcp: InspectorMcpServer) {
    val copy = rememberCopyToClipboard()
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        Text("Settings", style = MaterialTheme.typography.headlineSmall)

        Section("MCP server") {
            McpServerControls(mcp = mcp, copy = copy)
        }

        Section("MCP activity log") {
            McpActivityLog(mcp = mcp)
        }

        Section("Theme") {
            SingleChoiceSegmentedButtonRow(modifier = Modifier.width(420.dp)) {
                ThemePreference.entries.forEachIndexed { idx, t ->
                    SegmentedButton(
                        selected = state.theme == t,
                        onClick = { store.setTheme(t) },
                        shape = SegmentedButtonDefaults.itemShape(idx, ThemePreference.entries.size),
                    ) { Text(t.label) }
                }
            }
        }

        Section("Log file") {
            PathRow(DiskLogger.file.absolutePath, copy)
            Text(
                "Every adb shell command, attach step, gRPC event and stack trace lands here. " +
                    "Tail it when something is wrong.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline,
            )
        }

        Section("Studio bundle") {
            val bundle = System.getProperty("network.inspector.studio.bundle").orEmpty()
            PathRow(bundle.ifBlank { "(unset)" }, copy)
            Text(
                "Device-side agents are loaded from this directory. " +
                    "Sync from a local Android Studio install with: ./gradlew syncStudioBundle",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline,
            )
        }

        Section("Ignored hosts") {
            Text(
                "Requests targeting these hosts (and their subdomains) are hidden from the " +
                    "inspector. Data is still captured — toggling here updates the view instantly.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline,
            )
            IgnoredHostInput(onAdd = { store.addIgnoredHost(it) })
            if (state.ignoredHosts.isEmpty()) {
                Text(
                    "No hosts ignored yet.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline,
                    fontFamily = FontFamily.Monospace,
                )
            } else {
                state.ignoredHosts.forEach { host ->
                    IgnoredHostRow(host = host, onRemove = { store.removeIgnoredHost(host) })
                }
            }
        }
    }
}

@Composable
private fun McpServerControls(mcp: InspectorMcpServer, copy: (String) -> Unit) {
    val status by mcp.status.collectAsState()
    val scope = rememberCoroutineScope()
    val running = status is McpServerStatus.Running

    val (statusText, statusColor) = when (val s = status) {
        is McpServerStatus.Running -> "Running — listening on port ${s.port}" to MaterialTheme.colorScheme.primary
        is McpServerStatus.Stopped -> "Stopped" to MaterialTheme.colorScheme.outline
        is McpServerStatus.Failed -> "Failed: ${s.message}" to MaterialTheme.colorScheme.error
    }
    Text(statusText, style = MaterialTheme.typography.bodyMedium, color = statusColor)
    Text(
        "Exposes the live capture to an MCP client such as Claude Code. Anything the agent does — " +
            "adding mock rules, attaching to a device — shows up in this window instantly, and it reads " +
            "exactly the requests you see. Bound to 127.0.0.1 with no auth; keep it local.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.outline,
    )
    PathRow(mcp.endpoint, copy)
    androidx.compose.foundation.layout.Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        OutlinedButton(
            onClick = { scope.launch(Dispatchers.IO) { if (running) mcp.stop() else mcp.start() } },
        ) { Text(if (running) "Stop" else "Start") }
        if (running || status is McpServerStatus.Failed) {
            TextButton(
                onClick = { scope.launch(Dispatchers.IO) { mcp.restart() } },
            ) { Text("Restart") }
        }
    }
}

private val MCP_LOG_TIME = DateTimeFormatter.ofPattern("HH:mm:ss.SSS").withZone(ZoneId.systemDefault())

@Composable
private fun McpActivityLog(mcp: InspectorMcpServer) {
    val entries by mcp.log.entries.collectAsState()
    val listState = rememberLazyListState()
    // Follow the tail as new entries arrive.
    LaunchedEffect(entries.size) {
        if (entries.isNotEmpty()) listState.scrollToItem(entries.lastIndex)
    }
    Text(
        "Every MCP server event and tool call (with arguments, result and elapsed time) is recorded " +
            "here and mirrored to the disk log. Errors are highlighted.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.outline,
    )
    androidx.compose.foundation.layout.Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            "${entries.size} entries",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.outline,
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = { mcp.log.clear() }, enabled = entries.isNotEmpty()) { Text("Clear") }
    }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(260.dp)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(8.dp))
            .padding(8.dp),
    ) {
        if (entries.isEmpty()) {
            Text(
                "No MCP activity yet. Register an MCP client and call a tool.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline,
                fontFamily = FontFamily.Monospace,
            )
        } else {
            LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
                items(entries, key = { it.seq }) { entry -> McpLogRow(entry) }
            }
        }
    }
}

@Composable
private fun McpLogRow(entry: McpLogEntry) {
    val color = when (entry.level) {
        McpLogLevel.ERROR -> MaterialTheme.colorScheme.error
        McpLogLevel.WARN -> MaterialTheme.colorScheme.tertiary
        McpLogLevel.INFO -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    val time = remember(entry.atMs) { MCP_LOG_TIME.format(Instant.ofEpochMilli(entry.atMs)) }
    val tag = entry.tool?.let { "$it " }.orEmpty()
    Text(
        text = "$time  $tag${entry.message}",
        style = MaterialTheme.typography.bodySmall,
        fontFamily = FontFamily.Monospace,
        color = color,
        maxLines = 3,
    )
}

@Composable
private fun IgnoredHostInput(onAdd: (String) -> Unit) {
    var text by remember { mutableStateOf("") }
    val submit = {
        if (text.isNotBlank()) {
            onAdd(text)
            text = ""
        }
    }
    androidx.compose.foundation.layout.Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        OutlinedTextField(
            value = text,
            onValueChange = { text = it },
            placeholder = { Text("e.g. analytics.example.com") },
            singleLine = true,
            modifier = Modifier.weight(1f),
        )
        OutlinedButton(onClick = { submit() }, enabled = text.isNotBlank()) { Text("Add") }
    }
}

@Composable
private fun IgnoredHostRow(host: String, onRemove: () -> Unit) {
    androidx.compose.foundation.layout.Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            host,
            fontFamily = FontFamily.Monospace,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = onRemove) { Text("Remove") }
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(title, style = MaterialTheme.typography.titleSmall)
        content()
    }
}

@Composable
private fun PathRow(path: String, onCopy: (String) -> Unit) {
    androidx.compose.foundation.layout.Row(
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            path,
            fontFamily = FontFamily.Monospace,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = { onCopy(path) }) { Text("Copy") }
    }
}
