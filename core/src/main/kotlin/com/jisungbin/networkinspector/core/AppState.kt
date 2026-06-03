package com.jisungbin.networkinspector.core

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

/**
 * Single source of truth shared by every controller: owns the one [UiState] flow and the coroutine
 * scope. Controllers read/write through this, so there is exactly one StateFlow the UI renders from.
 */
internal class AppState {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val flow = MutableStateFlow(UiState())
    val ui: UiState get() = flow.value

    inline fun update(block: (UiState) -> UiState) = flow.update(block)

    /** Replaces a single device's session, creating it if absent. */
    inline fun updateSession(serial: String, crossinline block: (DeviceSession) -> DeviceSession) {
        flow.update { state ->
            val current = state.sessions[serial] ?: DeviceSession(serial)
            state.copy(sessions = state.sessions + (serial to block(current)))
        }
    }
}
