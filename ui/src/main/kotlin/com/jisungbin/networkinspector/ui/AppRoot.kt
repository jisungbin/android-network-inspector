package com.jisungbin.networkinspector.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import com.jisungbin.networkinspector.ui.screens.HomeScreen
import com.jisungbin.networkinspector.ui.screens.InspectorScreen
import androidx.compose.runtime.collectAsState

@Composable
fun AppRoot(store: AppStore) {
    val state by store.state.collectAsState()
    Box(modifier = Modifier.fillMaxSize()) {
        when (state.attach) {
            AttachState.Idle, is AttachState.Connecting, is AttachState.Failed ->
                HomeScreen(state = state, store = store)
            is AttachState.Streaming ->
                InspectorScreen(state = state, store = store)
        }
    }
}
