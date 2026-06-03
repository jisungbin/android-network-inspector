package com.jisungbin.networkinspector.core

import com.android.tools.profiler.proto.Common
import com.jisungbin.networkinspector.adb.findBySerial
import com.jisungbin.networkinspector.engine.AttachMode
import com.jisungbin.networkinspector.engine.AttachOrchestrator
import com.jisungbin.networkinspector.engine.AttachSession
import com.jisungbin.networkinspector.engine.AttachStage
import com.jisungbin.networkinspector.engine.NetworkRow
import com.jisungbin.networkinspector.engine.RowAggregator
import com.jisungbin.networkinspector.log.DiskLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Attach/detach lifecycle and live network-event collection. Owns the per-device [Runtime]
 * (non-serializable: the attach session, stream job, row aggregator, rule channel). On attach it
 * calls [onAttached] so the rule controller can push the current rules onto the new device.
 */
internal class SessionController(
    private val state: AppState,
    private val bridge: BridgeProvider,
    private val studioBundleDir: () -> File,
    private val onAttached: (DeviceRuleChannel) -> Unit,
) {
    private class Runtime(
        val session: AttachSession,
        val ruleChannel: DeviceRuleChannel,
        var streamJob: Job? = null,
        val aggregator: RowAggregator = RowAggregator(),
    )

    private val runtimes = ConcurrentHashMap<String, Runtime>()

    /** Rule channels of every attached device, for [InterceptRuleController] to fan out to. */
    fun ruleChannels(): Collection<DeviceRuleChannel> = runtimes.values.map { it.ruleChannel }

    fun setPaused(value: Boolean) {
        val serial = state.ui.selectedSerial ?: return
        val rt = runtimes[serial]
        state.updateSession(serial) {
            if (!value) it.copy(paused = false, rows = rt?.aggregator?.snapshot ?: it.rows)
            else it.copy(paused = true)
        }
    }

    fun clearRows() {
        val serial = state.ui.selectedSerial ?: return
        runtimes[serial]?.aggregator?.reset()
        state.updateSession(serial) { it.copy(rows = emptyList(), selectedRowId = null, firstEventAt = null) }
    }

    fun attach(serial: String) {
        val sess = state.ui.sessions[serial] ?: return
        if (sess.packageName.isBlank()) return
        if (sess.attachMode == AttachMode.ColdStart && sess.activity.isBlank()) return
        // Already attaching or streaming on this device — ignore duplicate attach.
        if (runtimes.containsKey(serial)) return
        if (sess.attach is AttachState.Connecting || sess.attach is AttachState.Streaming) return

        val packageName = sess.packageName
        val attachMode = sess.attachMode
        val activityArg = sess.activity.takeIf { attachMode == AttachMode.ColdStart }
        val model = state.ui.devices.firstOrNull { it.serial == serial }?.model

        state.updateSession(serial) {
            it.copy(model = model ?: it.model, attach = AttachState.Connecting(AttachPhase.Deploying))
        }
        state.scope.launch {
            var session: AttachSession? = null
            try {
                val orchestrator = withContext(Dispatchers.IO) {
                    val device = bridge.get().devices.toList().findBySerial(serial)
                    AttachOrchestrator(device, packageName, studioBundleDir())
                }
                val opened = withContext(Dispatchers.IO) {
                    orchestrator.attach(attachMode, activityArg) { stage ->
                        state.updateSession(serial) { it.copy(attach = AttachState.Connecting(stage.toPhase())) }
                    }
                }
                session = opened
                val rt = Runtime(session = opened, ruleChannel = DeviceRuleChannel(opened, state.scope))
                runtimes[serial] = rt
                state.updateSession(serial) { it.copy(attach = AttachState.Streaming(opened.pid, opened.hostPort)) }
                state.update {
                    it.copy(destination = Destination.INSPECTOR, selectedSerial = it.selectedSerial ?: serial)
                }
                // Map the current global rules onto this device's stream-scoped protocol ids.
                onAttached(rt.ruleChannel)
                rt.streamJob = state.scope.launch(Dispatchers.IO) {
                    opened.networkEvents().collect { event ->
                        val updated = rt.aggregator.consume(event) ?: return@collect
                        val cur = state.ui.sessions[serial] ?: return@collect
                        if (cur.firstEventAt == null) {
                            state.updateSession(serial) { it.copy(firstEventAt = System.currentTimeMillis()) }
                        }
                        if (state.ui.sessions[serial]?.paused == true) return@collect
                        state.updateSession(serial) { s ->
                            val previousMocked = s.rows.firstOrNull { it.connectionId == updated.connectionId }?.mocked ?: false
                            val justMocked = updated.mocked && !previousMocked
                            val newHits = if (justMocked) {
                                val matching = state.ui.interceptRules.firstOrNull { matchesRow(updated, it) }
                                if (matching != null) {
                                    s.ruleHits + (matching.id to ((s.ruleHits[matching.id] ?: 0) + 1))
                                } else s.ruleHits
                            } else s.ruleHits
                            s.copy(rows = s.rows.replaceOrAppend(updated), ruleHits = newHits)
                        }
                    }
                }
                state.scope.launch(Dispatchers.IO) {
                    val received = withTimeoutOrNull(5_000) {
                        opened.rawEvents()
                            .filter { it.kind == Common.Event.Kind.APP_INSPECTION_RESPONSE }
                            .firstOrNull()
                    }
                    val cur = state.ui.sessions[serial]
                    if (cur?.attach is AttachState.Streaming && cur.inspectorReadyAt == null) {
                        state.updateSession(serial) { it.copy(inspectorReadyAt = System.currentTimeMillis()) }
                        DiskLogger.log(
                            if (received != null) "inspector ready (response received)"
                            else "inspector assumed ready (5s timeout)"
                        )
                    }
                }
                delay(1_500)
                withContext(Dispatchers.IO) { opened.sendCreateAndStart() }
            } catch (t: Throwable) {
                DiskLogger.logError("attach failed", t)
                val diag = session?.let {
                    runCatching { withContext(Dispatchers.IO) { it.runner.diagnose() } }.getOrNull()
                }
                if (diag != null) {
                    DiskLogger.logBlock("attach-diagnose", diag)
                }
                val msg = buildString {
                    append(t::class.simpleName ?: "error")
                    t.message?.let { append(": $it") }
                    t.cause?.let { c ->
                        append("\ncaused by ${c::class.simpleName}")
                        c.message?.let { append(": $it") }
                    }
                    if (!diag.isNullOrBlank()) {
                        append("\n\n").append(diag)
                    }
                    append("\n\n(full log at ")
                    append(DiskLogger.file.absolutePath)
                    append(")")
                }
                state.updateSession(serial) { it.copy(attach = AttachState.Failed(msg)) }
                runCatching { session?.close() }
                runtimes.remove(serial)
            }
        }
    }

    /** Detaches a single device tab, freeing its runtime but keeping the attach form input. */
    fun detach(serial: String) {
        state.scope.launch {
            val rt = runtimes.remove(serial)
            rt?.streamJob?.cancel()
            rt?.ruleChannel?.close()
            withContext(Dispatchers.IO) { rt?.session?.close() }
            state.update { ui ->
                val sess = ui.sessions[serial]
                val updatedSessions = if (sess != null) {
                    ui.sessions + (serial to sess.copy(
                        attach = AttachState.Idle,
                        rows = emptyList(),
                        selectedRowId = null,
                        inspectorReadyAt = null,
                        firstEventAt = null,
                        ruleHits = emptyMap(),
                        paused = false,
                    ))
                } else ui.sessions
                val stillInspecting = updatedSessions.values.filter { it.attach !is AttachState.Idle }
                val newSelected = if (ui.selectedSerial == serial) {
                    stillInspecting.firstOrNull { it.attach is AttachState.Streaming }?.serial
                        ?: stillInspecting.firstOrNull()?.serial
                } else ui.selectedSerial
                ui.copy(
                    sessions = updatedSessions,
                    selectedSerial = newSelected,
                    destination = if (updatedSessions.values.none { it.attach is AttachState.Streaming })
                        Destination.DEVICES else ui.destination,
                )
            }
        }
    }

    /** Detaches the currently selected tab. */
    fun detach() {
        state.ui.selectedSerial?.let { detach(it) }
    }

    fun detachAll() {
        runtimes.keys.toList().forEach { detach(it) }
    }

    private fun AttachStage.toPhase(): AttachPhase = when (this) {
        AttachStage.Deploying -> AttachPhase.Deploying
        AttachStage.DaemonStart -> AttachPhase.DaemonStart
        AttachStage.AttachAgent -> AttachPhase.AttachAgent
        AttachStage.Forwarding -> AttachPhase.Forwarding
        AttachStage.CreatingInspector -> AttachPhase.CreatingInspector
    }

    private fun List<NetworkRow>.replaceOrAppend(row: NetworkRow): List<NetworkRow> {
        val idx = indexOfFirst { it.connectionId == row.connectionId }
        return if (idx >= 0) toMutableList().apply { this[idx] = row } else this + row
    }
}
