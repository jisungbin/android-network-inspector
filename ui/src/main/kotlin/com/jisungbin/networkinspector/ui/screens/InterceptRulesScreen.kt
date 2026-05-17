package com.jisungbin.networkinspector.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.jisungbin.networkinspector.ui.AppStore
import com.jisungbin.networkinspector.ui.UiState

@Composable
fun InterceptRulesScreen(state: UiState, store: AppStore, streaming: Boolean) {
    Column(modifier = Modifier.fillMaxSize()) {
        if (!streaming) {
            Card(modifier = Modifier.padding(12.dp)) {
                Text(
                    "Inspector not attached — rules can still be edited and will sync " +
                        "automatically once you attach.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(12.dp),
                )
            }
        }
        InterceptRulesPanel(state = state, store = store)
    }
}
