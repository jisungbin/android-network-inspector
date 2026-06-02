package com.jisungbin.networkinspector.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.NetworkCheck
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Smartphone
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import com.jisungbin.networkinspector.ui.mcp.InspectorMcpServer
import com.jisungbin.networkinspector.ui.screens.DeviceSessionStatus
import com.jisungbin.networkinspector.ui.screens.DeviceTabBar
import com.jisungbin.networkinspector.ui.screens.HomeScreen
import com.jisungbin.networkinspector.ui.screens.InspectorScreen
import com.jisungbin.networkinspector.ui.screens.InterceptRulesScreen
import com.jisungbin.networkinspector.ui.screens.NotAttachedScreen
import com.jisungbin.networkinspector.ui.screens.SettingsScreen
import com.jisungbin.networkinspector.ui.screens.StatusBar

@Composable
fun AppRoot(store: AppStore, mcp: InspectorMcpServer) {
    val state by store.state.collectAsState()
    val streaming = state.anyStreaming

    Row(modifier = Modifier.fillMaxSize()) {
        NavigationRail {
            NavigationRailItem(
                selected = state.destination == Destination.DEVICES,
                onClick = { store.setDestination(Destination.DEVICES) },
                icon = { Icon(Icons.Default.Smartphone, contentDescription = "Devices") },
                label = { Text("Devices") },
            )
            NavigationRailItem(
                selected = state.destination == Destination.INSPECTOR,
                onClick = { store.setDestination(Destination.INSPECTOR) },
                icon = { Icon(Icons.Default.NetworkCheck, contentDescription = "Inspector") },
                label = { Text("Inspector") },
            )
            NavigationRailItem(
                selected = state.destination == Destination.RULES,
                onClick = { store.setDestination(Destination.RULES) },
                icon = { Icon(Icons.Default.SwapHoriz, contentDescription = "Rules") },
                label = { Text("Rules") },
            )
            NavigationRailItem(
                selected = state.destination == Destination.SETTINGS,
                onClick = { store.setDestination(Destination.SETTINGS) },
                icon = { Icon(Icons.Default.Settings, contentDescription = "Settings") },
                label = { Text("Settings") },
            )
        }
        Column(modifier = Modifier.fillMaxSize()) {
            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                when (state.destination) {
                    Destination.DEVICES -> HomeScreen(state = state, store = store)
                    Destination.INSPECTOR ->
                        if (state.inspectingSessions.isEmpty()) {
                            NotAttachedScreen(store)
                        } else {
                            Column(modifier = Modifier.fillMaxSize()) {
                                DeviceTabBar(state = state, store = store)
                                val sess = state.selectedSession
                                if (sess != null && sess.attach is AttachState.Streaming) {
                                    InspectorScreen(session = sess, state = state, store = store)
                                } else {
                                    DeviceSessionStatus(session = sess, store = store)
                                }
                            }
                        }
                    Destination.RULES -> InterceptRulesScreen(state = state, store = store, streaming = streaming)
                    Destination.SETTINGS -> SettingsScreen(state = state, store = store, mcp = mcp)
                }
            }
            StatusBar(state = state, session = state.selectedSession)
        }
    }
}
