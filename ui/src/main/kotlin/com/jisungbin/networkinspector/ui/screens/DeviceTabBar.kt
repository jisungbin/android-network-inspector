package com.jisungbin.networkinspector.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryScrollableTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.jisungbin.networkinspector.core.AppStore
import com.jisungbin.networkinspector.core.AttachState
import com.jisungbin.networkinspector.core.DeviceSession
import com.jisungbin.networkinspector.core.UiState
import com.jisungbin.networkinspector.core.inspectingSessions

internal fun attachBadge(attach: AttachState): String = when (attach) {
    is AttachState.Streaming -> "●"
    is AttachState.Connecting -> "◐"
    is AttachState.Failed -> "✕"
    is AttachState.Idle -> "○"
}

@Composable
fun DeviceTabBar(state: UiState, store: AppStore) {
    val sessions = state.inspectingSessions
    if (sessions.isEmpty()) return
    val selectedIndex = sessions.indexOfFirst { it.serial == state.selectedSerial }.coerceAtLeast(0)
    PrimaryScrollableTabRow(
        selectedTabIndex = selectedIndex,
        edgePadding = 0.dp,
    ) {
        sessions.forEach { s ->
            Tab(
                selected = s.serial == state.selectedSerial,
                onClick = { store.selectTab(s.serial) },
                text = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Text("${attachBadge(s.attach)} ${s.model ?: s.serial}")
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Close ${s.serial}",
                            modifier = Modifier
                                .size(16.dp)
                                .clickable { store.detach(s.serial) },
                        )
                    }
                },
            )
        }
    }
}

/** Shown in the INSPECTOR area when the selected tab is connecting or has failed (not streaming). */
@Composable
fun DeviceSessionStatus(session: DeviceSession?, store: AppStore) {
    val serial = session?.serial
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        when (val a = session?.attach) {
            is AttachState.Connecting -> {
                Text("Connecting…", style = MaterialTheme.typography.titleMedium)
                Text(a.phase.label, color = MaterialTheme.colorScheme.outline)
            }
            is AttachState.Failed -> {
                Text(
                    "Attach failed",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.error,
                )
                SelectionContainer {
                    Column(
                        modifier = Modifier
                            .heightIn(max = 500.dp)
                            .verticalScroll(rememberScrollState()),
                    ) {
                        Text(
                            a.message,
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
                if (serial != null) {
                    TextButton(onClick = { store.detach(serial) }) { Text("Close tab") }
                }
            }
            else -> Text("Not streaming", color = MaterialTheme.colorScheme.outline)
        }
    }
}
