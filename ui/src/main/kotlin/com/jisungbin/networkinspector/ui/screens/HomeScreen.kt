package com.jisungbin.networkinspector.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.jisungbin.networkinspector.deploy.AttachMode
import com.jisungbin.networkinspector.ui.AppStore
import com.jisungbin.networkinspector.ui.AttachPhase
import com.jisungbin.networkinspector.ui.AttachState
import com.jisungbin.networkinspector.ui.UiState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(state: UiState, store: AppStore) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Network Inspector", style = MaterialTheme.typography.headlineSmall)

        Row(verticalAlignment = Alignment.CenterVertically) {
            DeviceDropdown(state = state, store = store, modifier = Modifier.weight(1f))
            TextButton(onClick = { store.refreshDevices() }) { Text("Refresh") }
        }

        PackageDropdown(state = state, store = store)

        ModeChooser(state = state, store = store)

        OutlinedTextField(
            value = state.activity,
            onValueChange = { store.updateActivity(it) },
            label = { Text("Activity") },
            placeholder = { Text("auto-resolved on package select; edit if needed") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            enabled = state.attachMode == AttachMode.ColdStart,
            trailingIcon = {
                if (state.activityResolving) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                }
            },
        )

        Button(
            onClick = { store.attach() },
            enabled = state.deviceSerial != null
                && state.packageName.isNotBlank()
                && (state.attachMode == AttachMode.AttachRunning || state.activity.isNotBlank())
                && state.attach !is AttachState.Connecting,
        ) {
            Text(
                when (state.attach) {
                    is AttachState.Connecting -> "Connecting…"
                    else -> when (state.attachMode) {
                        AttachMode.ColdStart -> "Cold start + attach"
                        AttachMode.AttachRunning -> "Attach to PID ${state.runningPid}"
                    }
                }
            )
        }

        when (val s = state.attach) {
            is AttachState.Connecting -> AttachStepper(s.phase)
            is AttachState.Failed -> SelectionContainer {
                androidx.compose.foundation.layout.Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 500.dp)
                        .verticalScroll(rememberScrollState()),
                ) {
                    Text(
                        "Failed:\n${s.message}",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                    )
                }
            }
            else -> Unit
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DeviceDropdown(state: UiState, store: AppStore, modifier: Modifier = Modifier) {
    var expanded by remember { mutableStateOf(false) }
    val selected = state.devices.firstOrNull { it.serial == state.deviceSerial }
    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = it },
        modifier = modifier,
    ) {
        OutlinedTextField(
            readOnly = true,
            value = selected?.let { "${it.serial}  ${it.model.orEmpty()}" } ?: "(no device)",
            onValueChange = {},
            label = { Text("Device") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier.menuAnchor().fillMaxWidth(),
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            state.devices.forEach { d ->
                DropdownMenuItem(
                    text = { Text("${d.serial}  ${d.model.orEmpty()}  ${d.abi.orEmpty()}") },
                    onClick = {
                        store.updateDevice(d.serial)
                        expanded = false
                    },
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PackageDropdown(state: UiState, store: AppStore) {
    var expanded by remember { mutableStateOf(false) }
    val filtered = remember(state.packages, state.packageName) {
        val q = state.packageName.trim()
        val list = if (q.isBlank()) state.packages
        else state.packages.filter { it.contains(q, ignoreCase = true) }
        list.take(200)
    }
    val runningHint = state.runningPid?.let { " (running pid=$it)" }.orEmpty()
    ExposedDropdownMenuBox(
        expanded = expanded && filtered.isNotEmpty(),
        onExpandedChange = { expanded = it },
    ) {
        OutlinedTextField(
            value = state.packageName,
            onValueChange = {
                store.updatePackage(it)
                expanded = true
            },
            label = { Text("Package$runningHint") },
            placeholder = { Text("type to filter installed third-party apps") },
            singleLine = true,
            modifier = Modifier.menuAnchor().fillMaxWidth(),
            trailingIcon = {
                if (state.packagesLoading) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                } else {
                    ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded)
                }
            },
        )
        ExposedDropdownMenu(
            expanded = expanded && filtered.isNotEmpty(),
            onDismissRequest = { expanded = false },
        ) {
            filtered.forEach { pkg ->
                DropdownMenuItem(
                    text = { Text(pkg) },
                    onClick = {
                        store.updatePackage(pkg)
                        expanded = false
                    },
                )
            }
        }
    }
}

@Composable
private fun AttachStepper(current: AttachPhase) {
    androidx.compose.foundation.layout.Column(
        verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(4.dp),
        modifier = Modifier.padding(top = 8.dp),
    ) {
        AttachPhase.entries.forEach { phase ->
            val state = when {
                phase.ordinal < current.ordinal -> "✓"
                phase.ordinal == current.ordinal -> "▶"
                else -> "·"
            }
            val color = when {
                phase.ordinal < current.ordinal -> MaterialTheme.colorScheme.primary
                phase.ordinal == current.ordinal -> MaterialTheme.colorScheme.primary
                else -> MaterialTheme.colorScheme.outline
            }
            Text(
                "$state  ${phase.label}",
                color = color,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ModeChooser(state: UiState, store: AppStore) {
    val modes = listOf(AttachMode.ColdStart, AttachMode.AttachRunning)
    val labels = mapOf(
        AttachMode.ColdStart to "Cold start",
        AttachMode.AttachRunning to "Attach running",
    )
    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
        modes.forEachIndexed { index, m ->
            SegmentedButton(
                selected = state.attachMode == m,
                onClick = { store.updateMode(m) },
                shape = SegmentedButtonDefaults.itemShape(index = index, count = modes.size),
                enabled = m != AttachMode.AttachRunning || state.runningPid != null,
            ) {
                Text(labels[m] ?: m.name)
            }
        }
    }
}
