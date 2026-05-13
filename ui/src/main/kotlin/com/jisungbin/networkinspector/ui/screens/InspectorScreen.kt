package com.jisungbin.networkinspector.ui.screens

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Divider
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.jisungbin.networkinspector.engine.ConnectionState
import com.jisungbin.networkinspector.engine.NetworkRow
import com.jisungbin.networkinspector.ui.AppStore
import com.jisungbin.networkinspector.ui.AttachState
import com.jisungbin.networkinspector.ui.SortKey
import com.jisungbin.networkinspector.ui.StatusFilter
import com.jisungbin.networkinspector.ui.UiState
import com.jisungbin.networkinspector.ui.util.applyFilters
import kotlinx.coroutines.delay

private data class Column(val key: SortKey, val label: String, val initialWidth: Dp)

private val Columns = listOf(
    Column(SortKey.METHOD, "METHOD", 70.dp),
    Column(SortKey.STATUS, "STATUS", 70.dp),
    Column(SortKey.URL, "URL", 460.dp),
    Column(SortKey.PROTO, "PROTO", 80.dp),
    Column(SortKey.DURATION, "MS", 60.dp),
    Column(SortKey.SIZE, "SIZE", 70.dp),
)

private const val HIGHLIGHT_MS = 1100L

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InspectorScreen(state: UiState, store: AppStore) {
    val streaming = state.attach as? AttachState.Streaming
    val filtered = remember(state.rows, state.search, state.statusFilter, state.methodFilter) {
        state.rows.applyFilters(state.search, state.statusFilter, state.methodFilter)
    }
    val sorted = remember(filtered, state.sortKey, state.sortDescending) {
        filtered.sortedWith(rowComparator(state.sortKey, state.sortDescending))
    }
    val selected = sorted.firstOrNull { it.connectionId == state.selectedRowId }
    val columnWidths = remember {
        mutableStateMapOf<SortKey, Dp>().apply { Columns.forEach { this[it.key] = it.initialWidth } }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Toolbar(state, streaming, store)
        Divider()
        FilterBar(state, store)
        Divider()
        Row(modifier = Modifier.fillMaxSize()) {
            Box(modifier = Modifier.weight(1.4f).fillMaxHeight()) {
                RequestTable(
                    rows = sorted,
                    state = state,
                    columnWidths = columnWidths,
                    onClickHeader = { store.toggleSort(it) },
                    onClickRow = { store.selectRow(it) },
                )
            }
            Divider(modifier = Modifier.fillMaxHeight().width(1.dp))
            Box(modifier = Modifier.weight(1.6f).fillMaxHeight()) {
                if (selected != null) RequestDetail(selected)
                else InterceptRulesPanel(state, store)
            }
        }
    }
}

@Composable
private fun Toolbar(state: UiState, streaming: AttachState.Streaming?, store: AppStore) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            "pid=${streaming?.pid}  port=${streaming?.hostPort}  ${state.packageName}",
            style = MaterialTheme.typography.bodySmall,
        )
        Spacer(Modifier.weight(1f))
        TextButton(onClick = { store.setPaused(!state.paused) }) {
            Text(if (state.paused) "Resume" else "Pause")
        }
        TextButton(onClick = { store.clearRows() }) { Text("Clear") }
        TextButton(onClick = { store.toggleAutoScroll() }) {
            Text(if (state.autoScroll) "Auto-scroll: on" else "Auto-scroll: off")
        }
        TextButton(onClick = { store.detach() }) { Text("Detach") }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FilterBar(state: UiState, store: AppStore) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        OutlinedTextField(
            value = state.search,
            onValueChange = { store.updateSearch(it) },
            placeholder = { Text("search URL / method") },
            singleLine = true,
            modifier = Modifier.weight(1f),
        )
        StatusFilterDropdown(state.statusFilter) { store.updateStatusFilter(it) }
        MethodFilterDropdown(state.methodFilter) { store.updateMethodFilter(it) }
    }
}

@Composable
private fun RequestTable(
    rows: List<NetworkRow>,
    state: UiState,
    columnWidths: androidx.compose.runtime.snapshots.SnapshotStateMap<SortKey, Dp>,
    onClickHeader: (SortKey) -> Unit,
    onClickRow: (Long) -> Unit,
) {
    val listState = rememberLazyListState()
    LaunchedEffect(rows.size, state.autoScroll, state.paused) {
        if (state.autoScroll && !state.paused && rows.isNotEmpty()) {
            listState.animateScrollToItem(rows.size - 1)
        }
    }

    Column {
        HeaderRow(
            sortKey = state.sortKey,
            descending = state.sortDescending,
            widths = columnWidths,
            onClick = onClickHeader,
        )
        Divider()
        LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
            items(rows, key = { it.connectionId }) { row ->
                DataRow(
                    row = row,
                    selected = row.connectionId == state.selectedRowId,
                    widths = columnWidths,
                    onClick = { onClickRow(row.connectionId) },
                )
            }
        }
    }
}

@Composable
private fun HeaderRow(
    sortKey: SortKey,
    descending: Boolean,
    widths: androidx.compose.runtime.snapshots.SnapshotStateMap<SortKey, Dp>,
    onClick: (SortKey) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Columns.forEachIndexed { idx, col ->
            val w = widths[col.key] ?: col.initialWidth
            Row(
                modifier = Modifier
                    .width(w)
                    .clickable { onClick(col.key) }
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = col.label,
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.SemiBold,
                    fontFamily = FontFamily.Monospace,
                    maxLines = 1,
                )
                if (sortKey == col.key) {
                    Spacer(Modifier.width(4.dp))
                    Text(
                        text = if (descending) "▼" else "▲",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
            if (idx != Columns.lastIndex) {
                ResizeHandle(col.key, widths)
            }
        }
    }
}

@Composable
private fun ResizeHandle(
    key: SortKey,
    widths: androidx.compose.runtime.snapshots.SnapshotStateMap<SortKey, Dp>,
) {
    val density = LocalDensity.current
    Box(
        modifier = Modifier
            .width(6.dp)
            .height(20.dp)
            .background(MaterialTheme.colorScheme.outlineVariant)
            .pointerInput(key) {
                detectDragGestures { _, dragAmount ->
                    val current = widths[key] ?: 80.dp
                    val deltaDp = with(density) { dragAmount.x.toDp() }
                    widths[key] = (current + deltaDp).coerceAtLeast(40.dp)
                }
            }
    )
}

@Composable
private fun DataRow(
    row: NetworkRow,
    selected: Boolean,
    widths: androidx.compose.runtime.snapshots.SnapshotStateMap<SortKey, Dp>,
    onClick: () -> Unit,
) {
    val highlight = useHighlight(row.lastUpdatedAtMs)
    val statusTint = statusTint(row)
    val base = when {
        selected -> MaterialTheme.colorScheme.secondaryContainer
        else -> statusTint
    }
    val pulse by animateColorAsState(
        targetValue = if (highlight > 0f) Color(0xFFB7F2C5).copy(alpha = highlight * 0.5f) else Color.Transparent,
        animationSpec = tween(80),
        label = "row-highlight",
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(base)
            .background(pulse)
            .clickable { onClick() }
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Columns.forEachIndexed { idx, col ->
            val w = widths[col.key] ?: col.initialWidth
            Box(modifier = Modifier.width(w).padding(horizontal = 8.dp)) {
                Text(
                    text = cellText(row, col.key),
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    maxLines = 1,
                )
            }
            if (idx != Columns.lastIndex) Spacer(Modifier.width(6.dp))
        }
    }
}

@Composable
private fun useHighlight(timestamp: Long): Float {
    var alpha by remember(timestamp) { mutableFloatStateOf(1f) }
    LaunchedEffect(timestamp) {
        val start = System.currentTimeMillis()
        while (true) {
            val elapsed = System.currentTimeMillis() - start
            if (elapsed >= HIGHLIGHT_MS) {
                alpha = 0f
                break
            }
            alpha = 1f - elapsed / HIGHLIGHT_MS.toFloat()
            delay(50)
        }
    }
    return alpha
}

private fun statusTint(row: NetworkRow): Color = when {
    row.state == ConnectionState.FAILED -> Color(0xFFFFCDD2)
    row.statusCode == null -> Color.Transparent
    row.statusCode in 200..299 -> Color.Transparent
    row.statusCode in 300..399 -> Color(0xFFFFF59D).copy(alpha = 0.35f)
    row.statusCode in 400..499 -> Color(0xFFFFCC80).copy(alpha = 0.4f)
    row.statusCode in 500..599 -> Color(0xFFEF9A9A).copy(alpha = 0.45f)
    else -> Color.Transparent
}

private fun cellText(row: NetworkRow, key: SortKey): String = when (key) {
    SortKey.METHOD -> row.method
    SortKey.STATUS -> when {
        row.state == ConnectionState.FAILED -> "ERR"
        row.statusCode != null -> row.statusCode.toString()
        else -> "…"
    }
    SortKey.URL -> row.url
    SortKey.PROTO -> row.protocol.name
    SortKey.DURATION -> row.endTimestamp?.let { ((it - row.startTimestamp) / 1_000_000L).toString() } ?: "—"
    SortKey.SIZE -> sizeText((row.requestBody?.size ?: 0) + (row.responseBody?.size ?: 0))
    SortKey.START_TIME -> row.startTimestamp.toString()
}

private fun sizeText(s: Int): String = when {
    s == 0 -> "—"
    s < 1024 -> "${s}B"
    s < 1024 * 1024 -> "${s / 1024}KB"
    else -> "${s / (1024 * 1024)}MB"
}

private fun rowComparator(key: SortKey, descending: Boolean): Comparator<NetworkRow> {
    val base: Comparator<NetworkRow> = when (key) {
        SortKey.METHOD -> compareBy { it.method }
        SortKey.STATUS -> compareBy { it.statusCode ?: Int.MAX_VALUE }
        SortKey.URL -> compareBy { it.url }
        SortKey.PROTO -> compareBy { it.protocol.name }
        SortKey.DURATION -> compareBy { it.endTimestamp?.let { e -> e - it.startTimestamp } ?: Long.MAX_VALUE }
        SortKey.SIZE -> compareBy { (it.requestBody?.size ?: 0) + (it.responseBody?.size ?: 0) }
        SortKey.START_TIME -> compareBy { it.startTimestamp }
    }
    return if (descending) base.reversed() else base
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun StatusFilterDropdown(current: StatusFilter, onSelect: (StatusFilter) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = !expanded },
        modifier = Modifier.width(140.dp),
    ) {
        OutlinedTextField(
            readOnly = true,
            value = current.name,
            onValueChange = {},
            label = { Text("Status") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
            modifier = Modifier.menuAnchor(),
        )
        androidx.compose.material3.DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            StatusFilter.entries.forEach { f ->
                DropdownMenuItem(text = { Text(f.name) }, onClick = {
                    onSelect(f); expanded = false
                })
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MethodFilterDropdown(current: String?, onSelect: (String?) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val items = listOf<String?>(null, "GET", "POST", "PUT", "DELETE", "PATCH")
    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = !expanded },
        modifier = Modifier.width(120.dp),
    ) {
        OutlinedTextField(
            readOnly = true,
            value = current ?: "All",
            onValueChange = {},
            label = { Text("Method") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
            modifier = Modifier.menuAnchor(),
        )
        androidx.compose.material3.DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            items.forEach { m ->
                DropdownMenuItem(text = { Text(m ?: "All") }, onClick = {
                    onSelect(m); expanded = false
                })
            }
        }
    }
}
