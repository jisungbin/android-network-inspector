package com.jisungbin.networkinspector.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyShortcut
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.MenuBar
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState

fun main() = application {
    val windowState = rememberWindowState(size = DpSize(1280.dp, 800.dp))
    Window(
        onCloseRequest = ::exitApplication,
        title = "Network Inspector",
        state = windowState,
    ) {
        val store = remember { AppStore() }
        val ui by store.state.collectAsState()
        val streaming = ui.attach is AttachState.Streaming

        MenuBar {
            Menu("Session", mnemonic = 'S') {
                Item(
                    text = if (ui.paused) "Resume" else "Pause",
                    enabled = streaming,
                    shortcut = KeyShortcut(Key.Period, meta = true),
                    onClick = { store.setPaused(!ui.paused) },
                )
                Item(
                    text = "Clear",
                    enabled = streaming,
                    shortcut = KeyShortcut(Key.K, meta = true),
                    onClick = { store.clearRows() },
                )
                Separator()
                Item(
                    text = "Refresh devices",
                    shortcut = KeyShortcut(Key.R, meta = true),
                    onClick = { store.refreshDevices() },
                )
                Item(
                    text = "Detach",
                    enabled = streaming,
                    shortcut = KeyShortcut(Key.W, shift = true, meta = true),
                    onClick = { store.detach() },
                )
            }
            Menu("View", mnemonic = 'V') {
                CheckboxItem(
                    text = "Auto-scroll",
                    checked = ui.autoScroll,
                    shortcut = KeyShortcut(Key.J, meta = true),
                    onCheckedChange = { store.toggleAutoScroll() },
                )
                Separator()
                Item(
                    text = "Devices",
                    shortcut = KeyShortcut(Key.One, meta = true),
                    onClick = { store.setDestination(Destination.DEVICES) },
                )
                Item(
                    text = "Inspector",
                    enabled = streaming,
                    shortcut = KeyShortcut(Key.Two, meta = true),
                    onClick = { store.setDestination(Destination.INSPECTOR) },
                )
                Item(
                    text = "Rules",
                    shortcut = KeyShortcut(Key.Three, meta = true),
                    onClick = { store.setDestination(Destination.RULES) },
                )
                Item(
                    text = "Settings",
                    shortcut = KeyShortcut(Key.Comma, meta = true),
                    onClick = { store.setDestination(Destination.SETTINGS) },
                )
            }
        }

        val dark = when (ui.theme) {
            ThemePreference.SYSTEM -> isSystemInDarkTheme()
            ThemePreference.LIGHT -> false
            ThemePreference.DARK -> true
        }
        val scheme = if (dark) darkColorScheme() else lightColorScheme()
        MaterialTheme(colorScheme = scheme) {
            Surface(modifier = Modifier.fillMaxSize()) {
                AppRoot(store = store)
            }
        }
    }
}
