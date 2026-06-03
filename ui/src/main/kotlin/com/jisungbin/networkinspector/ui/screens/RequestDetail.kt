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
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.SecondaryTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.jisungbin.networkinspector.engine.NetworkRow
import com.jisungbin.networkinspector.core.util.DecodedBody
import com.jisungbin.networkinspector.ui.util.JsonViewer
import com.jisungbin.networkinspector.ui.util.JsonViewerState
import com.jisungbin.networkinspector.ui.util.buildJsonViewerState
import com.jisungbin.networkinspector.core.util.decodeBody
import com.jisungbin.networkinspector.ui.util.rememberCopyToClipboard
import com.jisungbin.networkinspector.core.util.toCurl
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RequestDetail(row: NetworkRow) {
    var tab by remember(row.connectionId) { mutableStateOf(1) }
    var subTab by remember(row.connectionId) { mutableStateOf(1) }
    var search by remember(row.connectionId) { mutableStateOf("") }
    var showFull by remember(row.connectionId) { mutableStateOf(false) }
    val copy = rememberCopyToClipboard()

    val headers = if (tab == 0) row.requestHeaders else row.responseHeaders
    val body = if (tab == 0) row.requestBody else row.responseBody

    // 디코딩(gzip 해제 + 바이너리 판별 + JSON 파싱)과 JsonViewer 트리 빌드를 Default 디스패처로 옮긴다.
    // body가 바뀌면 produceState가 이전 코루틴을 자동 취소해 큰 응답을 휙휙 넘길 때 작업이 쌓이지 않는다.
    val bodyState by produceState<BodyState>(BodyState.Loading, body, headers, showFull) {
        value = BodyState.Loading
        val d = withContext(Dispatchers.Default) {
            decodeBody(body, headers, showFull = showFull)
        }
        val js = if (d?.isJson == true) {
            withContext(Dispatchers.Default) {
                buildJsonViewerState(d.parsedJson, d.text, defaultExpandedDepth = 2)
            }
        } else null
        value = BodyState.Ready(d, js)
    }
    val isLoading = bodyState is BodyState.Loading
    val decoded = (bodyState as? BodyState.Ready)?.decoded
    val jsonState = (bodyState as? BodyState.Ready)?.jsonState

    val totalMatches = if (decoded?.isJson == true && jsonState != null && search.isNotBlank()) {
        jsonState.totalMatches
    } else 0
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
                TextButton(onClick = { copy(row.toCurl()) }) { Text("Copy as cURL") }
                TextButton(onClick = { copy(row.url) }) { Text("Copy URL") }
            }
        }
        PrimaryTabRow(selectedTabIndex = tab) {
            Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("Request") })
            Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("Response") })
        }
        HorizontalDivider()
        SecondaryTabRow(
            selectedTabIndex = subTab,
            modifier = Modifier.height(32.dp),
        ) {
            Tab(
                selected = subTab == 0,
                onClick = { subTab = 0 },
                modifier = Modifier.height(32.dp),
                text = {
                    Text("Headers", style = MaterialTheme.typography.labelMedium)
                },
            )
            Tab(
                selected = subTab == 1,
                onClick = { subTab = 1 },
                modifier = Modifier.height(32.dp),
                text = {
                    Text("Body", style = MaterialTheme.typography.labelMedium)
                },
            )
        }
        HorizontalDivider()
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                value = search,
                onValueChange = { search = it },
                placeholder = {
                    Text(if (subTab == 0) "search within headers" else "search within body")
                },
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
            if (subTab == 1 && decoded?.isJson == true && search.isNotBlank()) {
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
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .padding(horizontal = 12.dp),
        ) {
            when (subTab) {
                0 -> HeaderBlock(
                    title = if (tab == 0) "Request Headers" else "Response Headers",
                    headers = headers,
                    search = search,
                    onCopy = copy,
                    modifier = Modifier.fillMaxSize(),
                )
                else -> BodyBlock(
                    title = if (tab == 0) "Request Body" else "Response Body",
                    decoded = decoded,
                    isLoading = isLoading,
                    search = search,
                    showFull = showFull,
                    onLoadFull = { showFull = true },
                    onCopy = copy,
                    currentMatchIndex = currentMatchIndex,
                    totalMatches = totalMatches,
                    jsonState = jsonState,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }
}

@Composable
private fun HeaderBlock(
    title: String,
    headers: List<Pair<String, List<String>>>,
    search: String,
    onCopy: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        Row {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.weight(1f))
            TextButton(onClick = {
                val all = headers.joinToString("\n") { (k, vs) -> "$k: ${vs.joinToString(", ")}" }
                onCopy(all)
            }) { Text("Copy all") }
        }
        Spacer(Modifier.height(4.dp))
        if (headers.isEmpty()) {
            Text("(empty)", style = MaterialTheme.typography.bodySmall)
        } else {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
            ) {
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
}

@Composable
private fun BodyBlock(
    title: String,
    decoded: DecodedBody?,
    isLoading: Boolean,
    search: String,
    showFull: Boolean,
    onLoadFull: () -> Unit,
    onCopy: (String) -> Unit,
    currentMatchIndex: Int,
    totalMatches: Int,
    jsonState: JsonViewerState?,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        Row {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.weight(1f))
            if (decoded?.isJson == true && jsonState != null) {
                TextButton(onClick = { jsonState.expandAll() }) { Text("Expand all") }
                TextButton(onClick = { jsonState.collapseAll() }) { Text("Collapse all") }
            }
            if (decoded != null && !decoded.isBinary) {
                TextButton(onClick = { onCopy(decoded.text) }) { Text("Copy") }
            }
        }
        Spacer(Modifier.height(4.dp))
        if (isLoading) {
            Text(
                "Decoding…",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            return@Column
        }
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
        if (decoded.isJson && jsonState != null) {
            SelectionContainer(modifier = Modifier.weight(1f).fillMaxWidth()) {
                JsonViewer(
                    state = jsonState,
                    search = search,
                    currentMatchIndex = if (totalMatches > 0) currentMatchIndex.coerceIn(0, totalMatches - 1) else -1,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        } else {
            SelectionContainer(modifier = Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                Text(
                    text = highlightOccurrences(decoded.text, search),
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

private sealed interface BodyState {
    data object Loading : BodyState
    data class Ready(val decoded: DecodedBody?, val jsonState: JsonViewerState?) : BodyState
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
