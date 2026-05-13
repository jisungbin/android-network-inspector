package com.jisungbin.networkinspector.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import androidx.compose.ui.unit.DpSize

fun main() = application {
    val state = rememberWindowState(size = DpSize(1280.dp, 800.dp))
    Window(
        onCloseRequest = ::exitApplication,
        title = "Network Inspector",
        state = state,
    ) {
        MaterialTheme {
            Surface(modifier = Modifier.fillMaxSize()) {
                val store = remember { AppStore() }
                AppRoot(store = store)
            }
        }
    }
}
