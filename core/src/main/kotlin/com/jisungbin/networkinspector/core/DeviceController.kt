package com.jisungbin.networkinspector.core

import com.jisungbin.networkinspector.adb.foregroundPackage
import com.jisungbin.networkinspector.adb.listThirdPartyPackages
import com.jisungbin.networkinspector.adb.pidOf
import com.jisungbin.networkinspector.adb.resolveLauncherActivity
import com.jisungbin.networkinspector.adb.snapshot
import com.jisungbin.networkinspector.engine.AttachMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Device discovery and package/activity resolution over adb. */
internal class DeviceController(private val state: AppState, private val bridge: BridgeProvider) {
    fun refreshDevices() {
        state.scope.launch {
            withContext(Dispatchers.IO) {
                val devices = bridge.get().devices.toList().map { it.snapshot() }
                state.update { ui ->
                    val serial = ui.composingSerial ?: devices.firstOrNull()?.serial
                    val sessions = if (serial != null && serial !in ui.sessions) {
                        val model = devices.firstOrNull { it.serial == serial }?.model
                        ui.sessions + (serial to DeviceSession(serial, model = model))
                    } else ui.sessions
                    ui.copy(devices = devices, composingSerial = serial, sessions = sessions)
                }
                state.ui.composingSerial?.let { loadPackagesFor(it) }
            }
        }
    }

    /** Selects which device's attach form is being edited on the DEVICES screen. */
    fun selectComposingDevice(serial: String?) {
        state.update { ui ->
            val sessions = if (serial != null && serial !in ui.sessions) {
                val model = ui.devices.firstOrNull { it.serial == serial }?.model
                ui.sessions + (serial to DeviceSession(serial, model = model))
            } else ui.sessions
            ui.copy(composingSerial = serial, sessions = sessions)
        }
        if (serial != null) state.scope.launch(Dispatchers.IO) { loadPackagesFor(serial) }
    }

    fun updatePackage(serial: String, name: String) {
        state.updateSession(serial) {
            it.copy(packageName = name, activity = "", runningPid = null, activityResolving = name.isNotBlank())
        }
        if (name.isBlank()) return
        state.scope.launch(Dispatchers.IO) {
            val device = bridge.deviceOf(serial)
            val pid = device?.pidOf(name)
            val activity = if (pid == null) device?.resolveLauncherActivity(name) else null
            state.updateSession(serial) {
                it.copy(
                    runningPid = pid,
                    activity = activity ?: "",
                    activityResolving = false,
                    attachMode = if (pid != null) AttachMode.AttachRunning else AttachMode.ColdStart,
                )
            }
        }
    }

    fun updateActivity(serial: String, activity: String) =
        state.updateSession(serial) { it.copy(activity = activity, activityResolving = false) }

    fun updateMode(serial: String, mode: AttachMode) =
        state.updateSession(serial) { it.copy(attachMode = mode) }

    /** Loads the third-party package list for [serial] on demand, used by the MCP server. */
    fun loadPackages(serial: String) {
        state.scope.launch(Dispatchers.IO) { loadPackagesFor(serial) }
    }

    private fun loadPackagesFor(serial: String) {
        state.updateSession(serial) { it.copy(packagesLoading = true) }
        val device = bridge.deviceOf(serial)
        if (device == null) {
            state.updateSession(serial) { it.copy(packagesLoading = false) }
            return
        }
        val pkgs = runCatching { device.listThirdPartyPackages() }.getOrDefault(emptyList())
        val foreground = runCatching { device.foregroundPackage() }.getOrNull()?.takeIf { it in pkgs }
        state.updateSession(serial) { it.copy(packages = pkgs, packagesLoading = false) }
        if (foreground != null && state.ui.sessions[serial]?.packageName.isNullOrBlank()) {
            updatePackage(serial, foreground)
        }
    }
}
