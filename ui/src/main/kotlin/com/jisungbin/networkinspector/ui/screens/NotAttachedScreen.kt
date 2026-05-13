package com.jisungbin.networkinspector.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.jisungbin.networkinspector.ui.AppStore
import com.jisungbin.networkinspector.ui.Destination

@Composable
fun NotAttachedScreen(store: AppStore) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text("Not attached", style = MaterialTheme.typography.headlineSmall)
        Text(
            "Pick a device + package and attach to start capturing traffic.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.outline,
        )
        TextButton(onClick = { store.setDestination(Destination.DEVICES) }) {
            Text("Go to Devices →")
        }
    }
}
