package com.jisungbin.networkinspector.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Divider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ClipboardManager
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.jisungbin.networkinspector.engine.NetworkRow
import com.jisungbin.networkinspector.ui.util.DecodedBody
import com.jisungbin.networkinspector.ui.util.JsonViewer
import com.jisungbin.networkinspector.ui.util.countJsonMatches
import com.jisungbin.networkinspector.ui.util.decodeBody
import com.jisungbin.networkinspector.ui.util.toCurl

@Composable
fun RequestDetail(row: NetworkRow) {
    var tab by remember(row.connectionId) { mutableStateOf(0) }
    var search by remember(row.connectionId) { mutableStateOf("") }
    var showFull by remember(row.connectionId) { mutableStateOf(false) }
    val clipboard = LocalClipboardManager.current

    val headers = if (tab == 0) row.requestHeaders else row.responseHeaders
    val body = if (tab == 0) row.requestBody else row.responseBody
    val decoded = remember(body, headers, showFull) { decodeBody(body, headers, showFull = showFull) }
    val totalMatches = remember(decoded?.text, search) {
        if (decoded?.isJson == true && search.isNotBlank())
            countJsonMatches(decoded.text, search)
        else 0
    }
    var currentMatchIndex by remember(body, search) { mutableIntStateOf(0) }
    LaunchedEffect(totalMatches) {
        if (totalMatches == 0) currentMatchIndex = 0
        else if (currentMatchIndex >= totalMatches) currentMatchIndex = 0
    }

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
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                TextButton(onClick = {
                    clipboard.setText(AnnotatedString(row.toCurl()))
                }) { Text("Copy as cURL") }
                TextButton(onClick = {
                    clipboard.setText(AnnotatedString(row.url))
                }) { Text("Copy URL") }
            }
        }
        TabRow(selectedTabIndex = tab) {
            Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("Request") })
            Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("Response") })
        }
        Divider()
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                value = search,
                onValueChange = { search = it },
                placeholder = { Text("search within body / headers") },
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
            if (decoded?.isJson == true && search.isNotBlank()) {
                JsonSearchNav(
                    currentMatchIndex = currentMatchIndex,
                    totalMatches = totalMatches,
                    onPrev = {
                        if (totalMatches > 0) {
                            currentMatchIndex = if (currentMatchIndex - 1 < 0) totalMatches - 1
                            else currentMatchIndex - 1
                        }
                    },
                    onNext = {
                        if (totalMatches > 0) currentMatchIndex = (currentMatchIndex + 1) % totalMatches
                    },
                )
            }
        }
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 12.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            HeaderBlock(
                title = if (tab == 0) "Request Headers" else "Response Headers",
                headers = headers,
                search = search,
                clipboard = clipboard,
            )
            BodyBlock(
                title = if (tab == 0) "Request Body" else "Response Body",
                body = body,
                decoded = decoded,
                search = search,
                showFull = showFull,
                onLoadFull = { showFull = true },
                clipboard = clipboard,
                currentMatchIndex = currentMatchIndex,
                totalMatches = totalMatches,
            )
        }
    }
}

@Composable
private fun HeaderBlock(
    title: String,
    headers: List<Pair<String, List<String>>>,
    search: String,
    clipboard: ClipboardManager,
) {
    Column {
        Row {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.weight(1f))
            TextButton(onClick = {
                val all = headers.joinToString("\n") { (k, vs) -> "$k: ${vs.joinToString(", ")}" }
                clipboard.setText(AnnotatedString(all))
            }) { Text("Copy all") }
        }
        Spacer(Modifier.height(4.dp))
        if (headers.isEmpty()) {
            Text("(empty)", style = MaterialTheme.typography.bodySmall)
        } else {
            headers.forEach { (k, vs) ->
                val line = "$k: ${vs.joinToString(", ")}"
                if (!matches(line, search)) return@forEach
                Text(
                    text = highlightOccurrences(line, search),
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

@Composable
private fun BodyBlock(
    title: String,
    body: ByteArray?,
    decoded: DecodedBody?,
    search: String,
    showFull: Boolean,
    onLoadFull: () -> Unit,
    clipboard: ClipboardManager,
    currentMatchIndex: Int,
    totalMatches: Int,
) {
    var depthOverride by remember(body) { mutableIntStateOf(2) }
    var generation by remember(body) { mutableIntStateOf(0) }
    Column {
        Row {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.weight(1f))
            if (decoded?.isJson == true) {
                TextButton(onClick = {
                    depthOverride = Int.MAX_VALUE
                    generation++
                }) { Text("Expand all") }
                TextButton(onClick = {
                    depthOverride = 0
                    generation++
                }) { Text("Collapse all") }
            }
            if (decoded != null && !decoded.isBinary) {
                TextButton(onClick = {
                    clipboard.setText(AnnotatedString(decoded.text))
                }) { Text("Copy") }
            }
        }
        Spacer(Modifier.height(4.dp))
        if (decoded == null) {
            Text("(empty)", style = MaterialTheme.typography.bodySmall)
            return@Column
        }
        Text(
            "encoding=${decoded.encoding}  ${if (decoded.isJson) "json  " else ""}size=${decoded.originalSize}B",
            style = MaterialTheme.typography.bodySmall,
        )
        val truncated = !showFull && decoded.originalSize > decoded.text.length
        if (truncated) {
            Row {
                Text(
                    "preview ${decoded.text.length}B of ${decoded.originalSize}B",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
                Spacer(Modifier.weight(1f))
                TextButton(onClick = onLoadFull) { Text("Load full") }
            }
        }
        Spacer(Modifier.height(4.dp))
        SelectionContainer {
            if (decoded.isJson) {
                androidx.compose.runtime.key(generation) {
                    JsonViewer(
                        json = decoded.text,
                        search = search,
                        currentMatchIndex = if (totalMatches > 0) currentMatchIndex.coerceIn(0, totalMatches - 1) else -1,
                        defaultExpandedDepth = depthOverride,
                    )
                }
            } else {
                Text(
                    text = highlightOccurrences(decoded.text, search),
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

@Composable
private fun JsonSearchNav(
    currentMatchIndex: Int,
    totalMatches: Int,
    onPrev: () -> Unit,
    onNext: () -> Unit,
) {
    Row(
        modifier = Modifier.padding(start = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = if (totalMatches == 0) "0 of 0"
            else "${currentMatchIndex + 1} of $totalMatches",
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
        )
        androidx.compose.material3.IconButton(
            onClick = onPrev,
            enabled = totalMatches > 0,
            modifier = Modifier.size(32.dp),
        ) {
            androidx.compose.material3.Icon(
                imageVector = Icons.Default.KeyboardArrowUp,
                contentDescription = "Previous match",
            )
        }
        androidx.compose.material3.IconButton(
            onClick = onNext,
            enabled = totalMatches > 0,
            modifier = Modifier.size(32.dp),
        ) {
            androidx.compose.material3.Icon(
                imageVector = Icons.Default.KeyboardArrowDown,
                contentDescription = "Next match",
            )
        }
    }
}

private fun matches(line: String, search: String): Boolean =
    search.isBlank() || line.contains(search, ignoreCase = true)

private fun highlightOccurrences(source: String, query: String): AnnotatedString =
    if (query.isBlank()) AnnotatedString(source)
    else buildAnnotatedString {
        var i = 0
        while (i < source.length) {
            val idx = source.indexOf(query, i, ignoreCase = true)
            if (idx < 0) {
                append(source.substring(i))
                break
            }
            append(source.substring(i, idx))
            withStyle(
                SpanStyle(
                    background = Color(0xFFFFEB3B).copy(alpha = 0.6f),
                    color = Color.Black,
                )
            ) {
                append(source.substring(idx, idx + query.length))
            }
            i = idx + query.length
        }
    }
