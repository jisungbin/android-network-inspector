package com.jisungbin.networkinspector.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.jisungbin.networkinspector.inspector.ConnectionState
import com.jisungbin.networkinspector.inspector.NetworkRow
import com.jisungbin.networkinspector.ui.AppStore
import com.jisungbin.networkinspector.ui.AttachState
import com.jisungbin.networkinspector.ui.StatusFilter
import com.jisungbin.networkinspector.ui.UiState
import com.jisungbin.networkinspector.ui.util.applyFilters

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InspectorScreen(state: UiState, store: AppStore) {
    val streaming = state.attach as? AttachState.Streaming
    val filtered = remember(state.rows, state.search, state.statusFilter, state.methodFilter) {
        state.rows.applyFilters(state.search, state.statusFilter, state.methodFilter)
    }
    val selected = filtered.firstOrNull { it.connectionId == state.selectedRowId }

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                "pid=${streaming?.pid}  port=${streaming?.hostPort}  ${state.packageName}",
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(Modifier.weight(1f))
            OutlinedTextField(
                value = state.search,
                onValueChange = { store.updateSearch(it) },
                placeholder = { Text("search URL / method") },
                singleLine = true,
                modifier = Modifier.width(280.dp),
            )
            StatusFilterDropdown(state.statusFilter) { store.updateStatusFilter(it) }
            MethodFilterDropdown(state.methodFilter) { store.updateMethodFilter(it) }
            TextButton(onClick = { store.detach() }) { Text("Detach") }
        }
        Divider()
        Row(modifier = Modifier.fillMaxSize()) {
            Box(modifier = Modifier.weight(1.4f).fillMaxHeight()) {
                RequestTable(rows = filtered, selectedId = state.selectedRowId) { id ->
                    store.selectRow(id)
                }
            }
            Divider(modifier = Modifier.fillMaxHeight().width(1.dp))
            Box(modifier = Modifier.weight(1.6f).fillMaxHeight()) {
                if (selected != null) {
                    RequestDetail(selected)
                } else {
                    InterceptRulesPanel(state, store)
                }
            }
        }
    }
}

@Composable
private fun RequestTable(
    rows: List<NetworkRow>,
    selectedId: Long?,
    onClick: (Long) -> Unit,
) {
    Column {
        TableHeaderRow()
        Divider()
        LazyColumn(modifier = Modifier.fillMaxSize()) {
            items(rows, key = { it.connectionId }) { row ->
                val bg = when {
                    row.connectionId == selectedId -> MaterialTheme.colorScheme.secondaryContainer
                    row.state == ConnectionState.FAILED -> Color(0x33D32F2F)
                    else -> Color.Transparent
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(bg)
                        .clickable { onClick(row.connectionId) }
                        .padding(horizontal = 8.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    TableCell(row.method, weight = 0.5f)
                    TableCell(row.statusText(), weight = 0.5f)
                    TableCell(row.url, weight = 4f)
                    TableCell(row.protocol.name, weight = 0.7f)
                    TableCell(row.durationText(), weight = 0.7f)
                    TableCell(row.sizeText(), weight = 0.7f)
                }
            }
        }
    }
}

@Composable
private fun TableHeaderRow() {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        TableCell("METHOD", weight = 0.5f, header = true)
        TableCell("STATUS", weight = 0.5f, header = true)
        TableCell("URL", weight = 4f, header = true)
        TableCell("PROTO", weight = 0.7f, header = true)
        TableCell("MS", weight = 0.7f, header = true)
        TableCell("SIZE", weight = 0.7f, header = true)
    }
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.TableCell(
    text: String,
    weight: Float,
    header: Boolean = false,
) {
    Text(
        text = text,
        modifier = Modifier.weight(weight),
        fontFamily = FontFamily.Monospace,
        fontWeight = if (header) FontWeight.Bold else FontWeight.Normal,
        style = MaterialTheme.typography.bodySmall,
        maxLines = 1,
    )
}

private fun NetworkRow.statusText(): String = when {
    state == ConnectionState.FAILED -> "ERR"
    statusCode != null -> statusCode.toString()
    else -> "…"
}

private fun NetworkRow.durationText(): String {
    val end = endTimestamp ?: return "—"
    val ms = (end - startTimestamp) / 1_000_000L
    return "$ms"
}

private fun NetworkRow.sizeText(): String {
    val s = (requestBody?.size ?: 0) + (responseBody?.size ?: 0)
    return when {
        s == 0 -> "—"
        s < 1024 -> "${s}B"
        s < 1024 * 1024 -> "${s / 1024}KB"
        else -> "${s / (1024 * 1024)}MB"
    }
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
