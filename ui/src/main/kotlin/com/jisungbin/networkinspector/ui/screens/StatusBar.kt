package com.jisungbin.networkinspector.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.jisungbin.networkinspector.engine.ConnectionState
import com.jisungbin.networkinspector.ui.AttachState
import com.jisungbin.networkinspector.ui.DeviceSession
import com.jisungbin.networkinspector.ui.UiState

@Composable
fun StatusBar(state: UiState, session: DeviceSession?) {
    val streaming = session?.attach as? AttachState.Streaming
    val rows = session?.rows ?: emptyList()
    val totalBytes = rows.sumOf { (it.requestBody?.size ?: 0) + (it.responseBody?.size ?: 0) }
    val inFlight = rows.count { it.state == ConnectionState.IN_FLIGHT }
    val failed = rows.count { it.state == ConnectionState.FAILED }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(horizontal = 12.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Cell(
            if (streaming != null) {
                val tail = "pid=${streaming.pid} port=${streaming.hostPort}"
                when {
                    session.inspectorReadyAt == null -> "● setup $tail"
                    session.firstEventAt == null -> "● listening (waiting for traffic) $tail"
                    else -> "● capturing $tail"
                }
            }
            else when (session?.attach) {
                is AttachState.Connecting -> "○ connecting"
                is AttachState.Failed -> "○ failed"
                else -> "○ idle"
            }
        )
        Cell("rows ${rows.size}")
        Cell("in-flight $inFlight")
        if (failed > 0) Cell("failed $failed")
        Cell("total ${sizeText(totalBytes)}")
        if (session?.paused == true) Cell("paused")
        androidx.compose.foundation.layout.Spacer(Modifier.weight(1f))
        Cell(session?.packageName?.ifBlank { "—" } ?: "—")
    }
}

@Composable
private fun Cell(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        fontFamily = FontFamily.Monospace,
    )
}

private fun sizeText(s: Int): String = when {
    s == 0 -> "0B"
    s < 1024 -> "${s}B"
    s < 1024 * 1024 -> "${s / 1024}KB"
    s < 1024 * 1024 * 1024 -> "${s / (1024 * 1024)}MB"
    else -> "${s / (1024L * 1024L * 1024L)}GB"
}
