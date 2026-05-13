package com.jisungbin.networkinspector.ui

import com.jisungbin.networkinspector.adb.DeviceSnapshot
import com.jisungbin.networkinspector.deploy.AttachMode
import com.jisungbin.networkinspector.inspector.NetworkRow

data class UiState(
    val devices: List<DeviceSnapshot> = emptyList(),
    val deviceSerial: String? = null,
    val packages: List<String> = emptyList(),
    val packagesLoading: Boolean = false,
    val packageName: String = "",
    val activity: String = "",
    val activityResolving: Boolean = false,
    val attachMode: AttachMode = AttachMode.ColdStart,
    val runningPid: Int? = null,
    val attach: AttachState = AttachState.Idle,
    val rows: List<NetworkRow> = emptyList(),
    val selectedRowId: Long? = null,
    val search: String = "",
    val statusFilter: StatusFilter = StatusFilter.All,
    val methodFilter: String? = null,
    val interceptRules: List<InterceptRule> = emptyList(),
)

sealed class AttachState {
    data object Idle : AttachState()
    data object Connecting : AttachState()
    data class Streaming(val pid: Int, val hostPort: Int) : AttachState()
    data class Failed(val message: String) : AttachState()
}

enum class StatusFilter { All, Success, Redirect, ClientError, ServerError, InFlight }

data class InterceptRule(
    val id: String,
    val name: String,
    val urlPattern: String,
    val replacementStatus: Int,
    val replacementBody: String,
    val enabled: Boolean,
)
