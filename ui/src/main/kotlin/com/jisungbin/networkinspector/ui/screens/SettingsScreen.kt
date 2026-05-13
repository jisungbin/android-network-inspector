package com.jisungbin.networkinspector.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ClipboardManager
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.jisungbin.networkinspector.log.DiskLogger
import com.jisungbin.networkinspector.ui.AppStore
import com.jisungbin.networkinspector.ui.ThemePreference
import com.jisungbin.networkinspector.ui.UiState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(state: UiState, store: AppStore) {
    val clipboard = LocalClipboardManager.current
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        Text("Settings", style = MaterialTheme.typography.headlineSmall)

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
            PathRow(DiskLogger.file.absolutePath, clipboard)
            Text(
                "Every adb shell command, attach step, gRPC event and stack trace lands here. " +
                    "Tail it when something is wrong.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline,
            )
        }

        Section("Studio bundle") {
            val bundle = System.getProperty("network.inspector.studio.bundle").orEmpty()
            PathRow(bundle.ifBlank { "(unset)" }, clipboard)
            Text(
                "Device-side agents are loaded from this directory. " +
                    "Sync from a local Android Studio install with: ./gradlew syncStudioBundle",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline,
            )
        }
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
private fun PathRow(path: String, clipboard: ClipboardManager) {
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
        TextButton(onClick = { clipboard.setText(AnnotatedString(path)) }) { Text("Copy") }
    }
}
