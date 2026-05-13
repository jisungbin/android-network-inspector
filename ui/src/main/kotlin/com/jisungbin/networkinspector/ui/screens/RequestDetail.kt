package com.jisungbin.networkinspector.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Divider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.jisungbin.networkinspector.inspector.NetworkRow
import com.jisungbin.networkinspector.ui.util.decodeBody

@Composable
fun RequestDetail(row: NetworkRow) {
    var tab by remember(row.connectionId) { mutableStateOf(0) }
    Column(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                "${row.method} ${row.url}",
                style = MaterialTheme.typography.titleMedium,
                fontFamily = FontFamily.Monospace,
            )
            Text(
                "status=${row.statusCode ?: "—"}  proto=${row.protocol}  state=${row.state}",
                style = MaterialTheme.typography.bodySmall,
            )
        }
        TabRow(selectedTabIndex = tab) {
            Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("Request") })
            Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("Response") })
        }
        Divider()
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(12.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (tab == 0) {
                HeaderBlock("Request Headers", row.requestHeaders)
                BodyBlock("Request Body", row.requestBody, row.requestHeaders)
            } else {
                HeaderBlock("Response Headers", row.responseHeaders)
                BodyBlock("Response Body", row.responseBody, row.responseHeaders)
            }
        }
    }
}

@Composable
private fun HeaderBlock(title: String, headers: List<Pair<String, List<String>>>) {
    Column {
        Text(title, style = MaterialTheme.typography.titleSmall)
        Spacer(Modifier.height(4.dp))
        if (headers.isEmpty()) {
            Text("(empty)", style = MaterialTheme.typography.bodySmall)
        } else {
            headers.forEach { (k, vs) ->
                Row(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = "$k:",
                        fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        text = vs.joinToString(", "),
                        fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.weight(3f),
                    )
                }
            }
        }
    }
}

@Composable
private fun BodyBlock(title: String, body: ByteArray?, headers: List<Pair<String, List<String>>>) {
    val decoded = remember(body, headers) { decodeBody(body, headers) }
    Column {
        Text(title, style = MaterialTheme.typography.titleSmall)
        Spacer(Modifier.height(4.dp))
        if (decoded == null) {
            Text("(empty)", style = MaterialTheme.typography.bodySmall)
        } else {
            Text(
                "encoding=${decoded.encoding}  size=${body?.size ?: 0}",
                style = MaterialTheme.typography.bodySmall,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                decoded.text,
                fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}
