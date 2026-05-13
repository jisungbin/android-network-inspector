package com.jisungbin.networkinspector.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Divider
import androidx.compose.material3.DropdownMenu
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
import androidx.compose.ui.unit.dp
import com.jisungbin.networkinspector.ui.AppStore
import com.jisungbin.networkinspector.ui.InterceptRule
import com.jisungbin.networkinspector.ui.UiState
import java.util.UUID

private val Methods = listOf("ANY", "GET", "POST", "PUT", "DELETE", "PATCH", "HEAD", "OPTIONS")

@Composable
fun InterceptRulesPanel(state: UiState, store: AppStore) {
    Column(modifier = Modifier.fillMaxSize().padding(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Mock / Intercept Rules", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.weight(1f))
            Button(onClick = { store.applyRulesToInspector() }) { Text("Apply to inspector") }
        }
        Text(
            "URL pattern + method match → status / Content-Type / body / added headers replacement.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.outline,
        )
        Spacer(Modifier.padding(4.dp))
        RuleEditor(onSubmit = { rule -> store.upsertRule(rule) })
        Divider(modifier = Modifier.padding(vertical = 8.dp))
        LazyColumn(modifier = Modifier.fillMaxSize()) {
            items(state.interceptRules, key = { it.id }) { rule ->
                RuleRow(
                    rule = rule,
                    onToggle = { store.upsertRule(rule.copy(enabled = !rule.enabled)) },
                    onRemove = { store.removeRule(rule.id) },
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RuleEditor(onSubmit: (InterceptRule) -> Unit) {
    var name by remember { mutableStateOf("") }
    var pattern by remember { mutableStateOf("") }
    var method by remember { mutableStateOf("ANY") }
    var status by remember { mutableStateOf("200") }
    var contentType by remember { mutableStateOf("application/json") }
    var body by remember { mutableStateOf("") }
    var headersText by remember { mutableStateOf("") }

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            OutlinedTextField(
                value = name, onValueChange = { name = it },
                label = { Text("Rule name") }, singleLine = true,
                modifier = Modifier.weight(1f),
            )
            MethodDropdown(method) { method = it }
        }
        OutlinedTextField(
            value = pattern, onValueChange = { pattern = it },
            label = { Text("URL pattern (host/path), e.g. api.example.com/users") },
            singleLine = true, modifier = Modifier.fillMaxWidth(),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            OutlinedTextField(
                value = status, onValueChange = { status = it },
                label = { Text("Status") }, singleLine = true,
                modifier = Modifier.width(120.dp),
            )
            OutlinedTextField(
                value = contentType, onValueChange = { contentType = it },
                label = { Text("Content-Type") }, singleLine = true,
                modifier = Modifier.weight(1f),
            )
        }
        OutlinedTextField(
            value = body, onValueChange = { body = it },
            label = { Text("Replacement body") },
            modifier = Modifier.fillMaxWidth(),
            maxLines = 8,
        )
        OutlinedTextField(
            value = headersText, onValueChange = { headersText = it },
            label = { Text("Added headers (one per line: Key: Value)") },
            modifier = Modifier.fillMaxWidth(),
            maxLines = 5,
        )
        Row {
            Spacer(Modifier.weight(1f))
            Button(
                onClick = {
                    if (pattern.isBlank()) return@Button
                    onSubmit(
                        InterceptRule(
                            id = UUID.randomUUID().toString(),
                            name = name.ifBlank { pattern },
                            urlPattern = pattern,
                            method = method,
                            replacementStatus = status.toIntOrNull() ?: 200,
                            replacementContentType = contentType.trim(),
                            replacementBody = body,
                            addedHeaders = parseHeaders(headersText),
                            enabled = true,
                        )
                    )
                    name = ""; pattern = ""; method = "ANY"
                    status = "200"; contentType = "application/json"
                    body = ""; headersText = ""
                },
            ) { Text("Add rule") }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MethodDropdown(current: String, onSelect: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = !expanded },
        modifier = Modifier.width(120.dp),
    ) {
        OutlinedTextField(
            readOnly = true,
            value = current,
            onValueChange = {},
            label = { Text("Method") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
            modifier = Modifier.menuAnchor(),
        )
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            Methods.forEach { m ->
                DropdownMenuItem(text = { Text(m) }, onClick = { onSelect(m); expanded = false })
            }
        }
    }
}

@Composable
private fun RuleRow(rule: InterceptRule, onToggle: () -> Unit, onRemove: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = rule.enabled, onCheckedChange = { onToggle() })
        Column(modifier = Modifier.weight(1f)) {
            Text(rule.name, style = MaterialTheme.typography.bodyMedium)
            Text(
                "${rule.method}  ${rule.urlPattern} → ${rule.replacementStatus}" +
                    (if (rule.replacementContentType.isNotEmpty()) "  [${rule.replacementContentType}]" else "") +
                    (if (rule.addedHeaders.isNotEmpty()) "  +${rule.addedHeaders.size}hdr" else ""),
                style = MaterialTheme.typography.bodySmall,
            )
        }
        TextButton(onClick = onRemove) { Text("Remove") }
    }
}

private fun parseHeaders(text: String): List<Pair<String, String>> =
    text.lineSequence()
        .map { it.trim() }
        .filter { it.isNotEmpty() && ":" in it }
        .map {
            val idx = it.indexOf(':')
            it.substring(0, idx).trim() to it.substring(idx + 1).trim()
        }
        .toList()
