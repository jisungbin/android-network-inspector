package com.jisungbin.networkinspector.ui

import com.jisungbin.networkinspector.adb.DeviceSnapshot
import com.jisungbin.networkinspector.engine.AttachMode
import com.jisungbin.networkinspector.engine.NetworkRow
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient

enum class Destination { DEVICES, INSPECTOR, RULES, SETTINGS }
enum class ThemePreference(val label: String) {
    SYSTEM("Follow system"),
    LIGHT("Light"),
    DARK("Dark"),
}

data class UiState(
    val destination: Destination = Destination.DEVICES,
    val theme: ThemePreference = ThemePreference.SYSTEM,
    val devices: List<DeviceSnapshot> = emptyList(),
    val deviceSerial: String? = null,
    val packages: List<String> = emptyList(),
    val packagesLoading: Boolean = false,
    val packageName: String = "",
    val activity: String = "com.gangnam.sister.debug/gnsister.app.main.MainActivity",
    val activityResolving: Boolean = false,
    val attachMode: AttachMode = AttachMode.ColdStart,
    val runningPid: Int? = null,
    val attach: AttachState = AttachState.Idle,
    val inspectorReadyAt: Long? = null,
    val firstEventAt: Long? = null,
    val rows: List<NetworkRow> = emptyList(),
    val selectedRowId: Long? = null,
    val search: String = "",
    val statusFilter: StatusFilter = StatusFilter.All,
    val methodFilter: String? = null,
    val interceptRules: List<InterceptRule> = emptyList(),
    val ruleHits: Map<String, Int> = emptyMap(),
    val sortKey: SortKey = SortKey.RECEIVED,
    val sortDescending: Boolean = false,
    val paused: Boolean = false,
    val autoScroll: Boolean = true,
)

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
    @Transient
    val protocolRuleId: Int? = null,
)
