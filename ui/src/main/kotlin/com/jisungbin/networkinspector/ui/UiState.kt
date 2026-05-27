package com.jisungbin.networkinspector.ui

import com.jisungbin.networkinspector.adb.DeviceSnapshot
import com.jisungbin.networkinspector.engine.AttachMode
import com.jisungbin.networkinspector.engine.NetworkRow
import kotlinx.serialization.Serializable

enum class Destination { DEVICES, INSPECTOR, RULES, SETTINGS }
enum class ThemePreference(val label: String) {
    SYSTEM("Follow system"),
    LIGHT("Light"),
    DARK("Dark"),
}

/**
 * Per-device inspection state. One entry per device the user has selected or attached to.
 * Holds only immutable UI state — runtime resources (AttachSession, stream Job, RowAggregator)
 * live in [AppStore.runtimes], keyed by the same serial.
 */
data class DeviceSession(
    val serial: String,
    val model: String? = null,
    // attach form input
    val packages: List<String> = emptyList(),
    val packagesLoading: Boolean = false,
    val packageName: String = "",
    val activity: String = "",
    val activityResolving: Boolean = false,
    val attachMode: AttachMode = AttachMode.ColdStart,
    val runningPid: Int? = null,
    // attach / streaming state
    val attach: AttachState = AttachState.Idle,
    val inspectorReadyAt: Long? = null,
    val firstEventAt: Long? = null,
    // captured data, scoped to this device
    val rows: List<NetworkRow> = emptyList(),
    val selectedRowId: Long? = null,
    val ruleHits: Map<String, Int> = emptyMap(),
    val paused: Boolean = false,
)

data class UiState(
    val destination: Destination = Destination.DEVICES,
    val theme: ThemePreference = ThemePreference.SYSTEM,
    val devices: List<DeviceSnapshot> = emptyList(),
    // multi-device
    val sessions: Map<String, DeviceSession> = emptyMap(),
    val selectedSerial: String? = null,   // active INSPECTOR tab
    val composingSerial: String? = null,  // device whose attach form is being edited on DEVICES
    // global filters (shared across all devices)
    val search: String = "",
    val statusFilter: StatusFilter = StatusFilter.All,
    val methodFilter: String? = null,
    val sortKey: SortKey = SortKey.RECEIVED,
    val sortDescending: Boolean = false,
    val autoScroll: Boolean = true,
    // global rule definitions / settings
    val interceptRules: List<InterceptRule> = emptyList(),
    val ignoredHosts: List<String> = emptyList(),
)

val UiState.selectedSession: DeviceSession?
    get() = selectedSerial?.let { sessions[it] }

val UiState.composingSession: DeviceSession?
    get() = composingSerial?.let { sessions[it] }

val UiState.anyStreaming: Boolean
    get() = sessions.values.any { it.attach is AttachState.Streaming }

/** Devices with an active tab: anything past Idle (Connecting / Streaming / Failed). */
val UiState.inspectingSessions: List<DeviceSession>
    get() = sessions.values
        .filter { it.attach !is AttachState.Idle }
        .sortedBy { it.serial }

enum class SortKey { METHOD, STATUS, URL, RECEIVED, DURATION, SIZE, PROTO, START_TIME }

sealed class AttachState {
    data object Idle : AttachState()
    data class Connecting(val phase: AttachPhase) : AttachState()
    data class Streaming(val pid: Int, val hostPort: Int) : AttachState()
    data class Failed(val message: String) : AttachState()
}

enum class AttachPhase(val label: String) {
    Deploying("Deploying agent + perfa.jar + network-inspector.jar"),
    DaemonStart("Starting transport daemon"),
    AttachAgent("Attaching JVMTI agent"),
    Forwarding("adb forward + gRPC channel"),
    CreatingInspector("Creating network inspector"),
}

enum class StatusFilter { All, Success, Redirect, ClientError, ServerError, InFlight }

@Serializable
data class InterceptRule(
    val id: String,
    val name: String,
    val urlPattern: String,
    val method: String = "ANY",
    val replacementStatus: Int,
    val replacementContentType: String = "",
    val replacementBody: String,
    val addedHeaders: List<Pair<String, String>> = emptyList(),
    val enabled: Boolean,
)
