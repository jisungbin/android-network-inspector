package com.jisungbin.networkinspector.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Divider
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

@Composable
fun InterceptRulesPanel(state: UiState, store: AppStore) {
    Column(modifier = Modifier.fillMaxSize().padding(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Intercept Rules", style = MaterialTheme.typography.titleMedium)
            androidx.compose.foundation.layout.Spacer(Modifier.weight(1f))
            Button(onClick = { store.applyRulesToInspector() }) { Text("Apply to inspector") }
        }
        androidx.compose.foundation.layout.Spacer(Modifier.padding(4.dp))
        RuleEditor(onSubmit = { rule -> store.upsertRule(rule) })
        Divider(modifier = Modifier.padding(vertical = 8.dp))
        LazyColumn(modifier = Modifier.fillMaxSize()) {
            items(state.interceptRules, key = { it.id }) { rule ->
                RuleRow(rule = rule, onToggle = {
                    store.upsertRule(rule.copy(enabled = !rule.enabled))
                }, onRemove = { store.removeRule(rule.id) })
            }
        }
    }
}

@Composable
private fun RuleEditor(onSubmit: (InterceptRule) -> Unit) {
    var name by remember { mutableStateOf("") }
    var pattern by remember { mutableStateOf("") }
    var status by remember { mutableStateOf("200") }
    var body by remember { mutableStateOf("") }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        OutlinedTextField(
            value = name, onValueChange = { name = it },
            label = { Text("Rule name") }, singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = pattern, onValueChange = { pattern = it },
            label = { Text("URL pattern (host/path) e.g. api.example.com/users") },
            singleLine = true, modifier = Modifier.fillMaxWidth(),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            OutlinedTextField(
                value = status, onValueChange = { status = it },
                label = { Text("Status") }, singleLine = true,
                modifier = Modifier.weight(1f),
            )
            Button(
                onClick = {
                    if (pattern.isNotBlank()) {
                        onSubmit(
                            InterceptRule(
                                id = UUID.randomUUID().toString(),
                                name = name.ifBlank { pattern },
                                urlPattern = pattern,
                                replacementStatus = status.toIntOrNull() ?: 200,
                                replacementBody = body,
                                enabled = true,
                            )
                        )
                        name = ""; pattern = ""; status = "200"; body = ""
                    }
                },
                modifier = Modifier.align(Alignment.CenterVertically),
            ) { Text("Add rule") }
        }
        OutlinedTextField(
            value = body, onValueChange = { body = it },
            label = { Text("Replacement body (optional)") },
            modifier = Modifier.fillMaxWidth(),
            maxLines = 5,
        )
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
                "${rule.urlPattern} → ${rule.replacementStatus}",
                style = MaterialTheme.typography.bodySmall,
            )
        }
        TextButton(onClick = onRemove) { Text("Remove") }
    }
}
