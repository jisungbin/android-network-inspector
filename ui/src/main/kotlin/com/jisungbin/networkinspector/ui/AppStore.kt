package com.jisungbin.networkinspector.ui

import com.android.ddmlib.AndroidDebugBridge
import com.jisungbin.networkinspector.adb.AdbBridge
import com.jisungbin.networkinspector.adb.findBySerial
import com.jisungbin.networkinspector.adb.foregroundPackage
import com.jisungbin.networkinspector.adb.listThirdPartyPackages
import com.jisungbin.networkinspector.adb.resolveLauncherActivity
import com.jisungbin.networkinspector.adb.snapshot
import com.jisungbin.networkinspector.engine.AttachMode
import com.jisungbin.networkinspector.engine.AttachOrchestrator
import com.jisungbin.networkinspector.engine.AttachSession
import com.jisungbin.networkinspector.engine.AttachStage
import com.jisungbin.networkinspector.adb.pidOf
import com.jisungbin.networkinspector.engine.RowAggregator
import com.jisungbin.networkinspector.ui.util.IgnoredHostsStorage
import com.jisungbin.networkinspector.ui.util.RulesStorage
import com.jisungbin.networkinspector.ui.util.SessionExporter
import com.jisungbin.networkinspector.ui.util.applyFilters
import com.jisungbin.networkinspector.ui.util.hostOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.ConcurrentHashMap

class AppStore {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    private var bridge: AndroidDebugBridge? = null

    /**
     * Per-device runtime resources, keyed by serial. Mirrors [UiState.sessions] but holds the
     * mutable/non-serializable bits: the live attach session, its stream job, the row aggregator,
     * and the device-local mapping from [InterceptRule.id] to the stream-scoped protocol rule id.
     */
    private class Runtime(
        val session: AttachSession,
        var streamJob: Job? = null,
        val aggregator: RowAggregator = RowAggregator(),
        var nextProtocolRuleId: Int = 1,
        val ruleIdMap: MutableMap<String, Int> = mutableMapOf(),
    )

    private val runtimes = ConcurrentHashMap<String, Runtime>()

    init {
        val saved = RulesStorage.load()
        val savedIgnored = IgnoredHostsStorage.load()
        if (saved.isNotEmpty() || savedIgnored.isNotEmpty()) {
            _state.update { it.copy(interceptRules = saved, ignoredHosts = savedIgnored) }
        }
        scope.launch { refreshDevices() }
    }

    private inline fun updateSession(serial: String, block: (DeviceSession) -> DeviceSession) {
        _state.update { ui ->
            val cur = ui.sessions[serial] ?: DeviceSession(serial)
            ui.copy(sessions = ui.sessions + (serial to block(cur)))
        }
    }

    fun refreshDevices() {
        scope.launch {
            withContext(Dispatchers.IO) {
                val b = bridge ?: AdbBridge.start(AdbBridge.resolveAdb()).also { bridge = it }
                val devices = b.devices.toList().map { it.snapshot() }
                _state.update { ui ->
                    val serial = ui.composingSerial ?: devices.firstOrNull()?.serial
                    val sessions = if (serial != null && serial !in ui.sessions) {
                        val model = devices.firstOrNull { it.serial == serial }?.model
                        ui.sessions + (serial to DeviceSession(serial, model = model))
                    } else ui.sessions
                    ui.copy(devices = devices, composingSerial = serial, sessions = sessions)
                }
                _state.value.composingSerial?.let { loadPackagesFor(it) }
            }
        }
    }

    /** Selects which device's attach form is being edited on the DEVICES screen. */
    fun selectComposingDevice(serial: String?) {
        _state.update { ui ->
            val sessions = if (serial != null && serial !in ui.sessions) {
                val model = ui.devices.firstOrNull { it.serial == serial }?.model
                ui.sessions + (serial to DeviceSession(serial, model = model))
            } else ui.sessions
            ui.copy(composingSerial = serial, sessions = sessions)
        }
        if (serial != null) scope.launch(Dispatchers.IO) { loadPackagesFor(serial) }
    }

    fun updatePackage(serial: String, name: String) {
        updateSession(serial) {
            it.copy(
                packageName = name,
                activity = "",
                runningPid = null,
                activityResolving = name.isNotBlank(),
            )
        }
        if (name.isBlank()) return
        scope.launch(Dispatchers.IO) {
            val device = bridge?.devices?.toList()?.firstOrNull { it.serialNumber == serial }
            val pid = device?.pidOf(name)
            val activity = if (pid == null) device?.resolveLauncherActivity(name) else null
            updateSession(serial) {
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
        updateSession(serial) { it.copy(activity = activity, activityResolving = false) }

    fun updateMode(serial: String, mode: AttachMode) =
        updateSession(serial) { it.copy(attachMode = mode) }

    /**
     * Loads the third-party package list for [serial] on demand, off the UI/composing flow.
     * Used by the embedded MCP server so an agent can enumerate packages for any connected
     * device without first selecting it on the DEVICES screen.
     */
    fun loadPackages(serial: String) {
        scope.launch(Dispatchers.IO) { loadPackagesFor(serial) }
    }

    private fun loadPackagesFor(serial: String) {
        updateSession(serial) { it.copy(packagesLoading = true) }
        val device = bridge?.devices?.toList()?.firstOrNull { it.serialNumber == serial }
        if (device == null) {
            updateSession(serial) { it.copy(packagesLoading = false) }
            return
        }
        val pkgs = runCatching { device.listThirdPartyPackages() }.getOrDefault(emptyList())
        val foreground = runCatching { device.foregroundPackage() }.getOrNull()
            ?.takeIf { it in pkgs }
        updateSession(serial) { it.copy(packages = pkgs, packagesLoading = false) }
        if (foreground != null && _state.value.sessions[serial]?.packageName.isNullOrBlank()) {
            updatePackage(serial, foreground)
        }
    }

    fun updateSearch(q: String) = _state.update { it.copy(search = q) }
    fun updateStatusFilter(f: StatusFilter) = _state.update { it.copy(statusFilter = f) }
    fun updateMethodFilter(m: String?) = _state.update { it.copy(methodFilter = m) }

    fun selectRow(id: Long?) {
        val serial = _state.value.selectedSerial ?: return
        updateSession(serial) { it.copy(selectedRowId = id) }
    }

    /** Switches the active INSPECTOR tab. */
    fun selectTab(serial: String) = _state.update { it.copy(selectedSerial = serial) }

    fun addIgnoredHost(host: String) {
        val normalized = host.trim().removePrefix("https://").removePrefix("http://")
            .substringBefore("/").substringBefore("?").lowercase()
        if (normalized.isEmpty()) return
        _state.update { state ->
            if (state.ignoredHosts.any { it.equals(normalized, ignoreCase = true) }) state
            else state.copy(ignoredHosts = state.ignoredHosts + normalized)
        }
        IgnoredHostsStorage.save(_state.value.ignoredHosts)
    }

    fun removeIgnoredHost(host: String) {
        _state.update { state ->
            state.copy(ignoredHosts = state.ignoredHosts.filterNot { it.equals(host, ignoreCase = true) })
        }
        IgnoredHostsStorage.save(_state.value.ignoredHosts)
    }

    fun ignoreHostOf(url: String) = addIgnoredHost(hostOf(url))

    fun setDestination(d: Destination) = _state.update { it.copy(destination = d) }
    fun setTheme(t: ThemePreference) = _state.update { it.copy(theme = t) }

    fun toggleSort(key: SortKey) = _state.update {
        if (it.sortKey == key) it.copy(sortDescending = !it.sortDescending)
        else it.copy(sortKey = key, sortDescending = false)
    }

    fun setPaused(value: Boolean) {
        val serial = _state.value.selectedSerial ?: return
        val rt = runtimes[serial]
        updateSession(serial) {
            if (!value) it.copy(paused = false, rows = rt?.aggregator?.snapshot ?: it.rows)
            else it.copy(paused = true)
        }
    }

    fun toggleAutoScroll() = _state.update { it.copy(autoScroll = !it.autoScroll) }

    fun clearRows() {
        val serial = _state.value.selectedSerial ?: return
        runtimes[serial]?.aggregator?.reset()
        updateSession(serial) { it.copy(rows = emptyList(), selectedRowId = null, firstEventAt = null) }
    }

    fun exportSessionJson(): String {
        val ui = _state.value
        val sess = ui.selectedSession ?: return "{}"
        val filtered = sess.rows.applyFilters(ui.search, ui.statusFilter, ui.methodFilter, ui.ignoredHosts)
        return SessionExporter.export(filtered, sess, ui)
    }

    fun attach(serial: String) {
        val sess = _state.value.sessions[serial] ?: return
        if (sess.packageName.isBlank()) return
        if (sess.attachMode == AttachMode.ColdStart && sess.activity.isBlank()) return
        // Already attaching or streaming on this device — ignore duplicate attach.
        if (runtimes.containsKey(serial)) return
        if (sess.attach is AttachState.Connecting || sess.attach is AttachState.Streaming) return

        val packageName = sess.packageName
        val attachMode = sess.attachMode
        val activityArg = sess.activity.takeIf { attachMode == AttachMode.ColdStart }
        val model = _state.value.devices.firstOrNull { it.serial == serial }?.model

        updateSession(serial) {
            it.copy(model = model ?: it.model, attach = AttachState.Connecting(AttachPhase.Deploying))
        }
        scope.launch {
            var session: AttachSession? = null
            try {
                val orchestrator = withContext(Dispatchers.IO) {
                    val device = bridge!!.devices.toList().findBySerial(serial)
                    AttachOrchestrator(device, packageName, resolveStudioBundleDir())
                }
                val opened = withContext(Dispatchers.IO) {
                    orchestrator.attach(attachMode, activityArg) { stage ->
                        updateSession(serial) { it.copy(attach = AttachState.Connecting(stage.toPhase())) }
                    }
                }
                session = opened
                val rt = Runtime(session = opened)
                runtimes[serial] = rt
                // Map the current global rules onto this device's stream-scoped protocol ids.
                val toSend = _state.value.interceptRules.map { r ->
                    val pid = rt.nextProtocolRuleId++
                    rt.ruleIdMap[r.id] = pid
                    pid to r
                }
                updateSession(serial) { it.copy(attach = AttachState.Streaming(opened.pid, opened.hostPort)) }
                _state.update {
                    it.copy(
                        destination = Destination.INSPECTOR,
                        selectedSerial = it.selectedSerial ?: serial,
                    )
                }
                scope.launch(Dispatchers.IO) {
                    toSend.forEach { (pid, r) ->
                        com.jisungbin.networkinspector.protocol.RuleSender.sendAdd(
                            opened.client, opened.pid, opened.streamId,
                            pid, r.toHostRule(),
                        )
                    }
                }
                rt.streamJob = scope.launch(Dispatchers.IO) {
                    opened.networkEvents().collect { event ->
                        val updated = rt.aggregator.consume(event) ?: return@collect
                        val cur = _state.value.sessions[serial] ?: return@collect
                        if (cur.firstEventAt == null) {
                            updateSession(serial) { it.copy(firstEventAt = System.currentTimeMillis()) }
                        }
                        if (_state.value.sessions[serial]?.paused == true) return@collect
                        updateSession(serial) { s ->
                            val previousMocked = s.rows.firstOrNull { it.connectionId == updated.connectionId }?.mocked ?: false
                            val justMocked = updated.mocked && !previousMocked
                            val newHits = if (justMocked) {
                                val matching = _state.value.interceptRules.firstOrNull { matchesRow(updated, it) }
                                if (matching != null) {
                                    s.ruleHits + (matching.id to ((s.ruleHits[matching.id] ?: 0) + 1))
                                } else s.ruleHits
                            } else s.ruleHits
                            s.copy(rows = s.rows.replaceOrAppend(updated), ruleHits = newHits)
                        }
                    }
                }
                scope.launch(Dispatchers.IO) {
                    val received = kotlinx.coroutines.withTimeoutOrNull(5_000) {
                        opened.rawEvents()
                            .filter { it.kind == com.android.tools.profiler.proto.Common.Event.Kind.APP_INSPECTION_RESPONSE }
                            .firstOrNull()
                    }
                    val cur = _state.value.sessions[serial]
                    if (cur?.attach is AttachState.Streaming && cur.inspectorReadyAt == null) {
                        updateSession(serial) { it.copy(inspectorReadyAt = System.currentTimeMillis()) }
                        com.jisungbin.networkinspector.log.DiskLogger.log(
                            if (received != null) "inspector ready (response received)"
                            else "inspector assumed ready (5s timeout)"
                        )
                    }
                }
                kotlinx.coroutines.delay(1_500)
                withContext(Dispatchers.IO) { opened.sendCreateAndStart() }
            } catch (t: Throwable) {
                com.jisungbin.networkinspector.log.DiskLogger.logError("attach failed", t)
                val diag = session?.let {
                    runCatching {
                        withContext(Dispatchers.IO) { it.runner.diagnose() }
                    }.getOrNull()
                }
                if (diag != null) {
                    com.jisungbin.networkinspector.log.DiskLogger.logBlock("attach-diagnose", diag)
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
                    append(com.jisungbin.networkinspector.log.DiskLogger.file.absolutePath)
                    append(")")
                }
                updateSession(serial) { it.copy(attach = AttachState.Failed(msg)) }
                runCatching { session?.close() }
                runtimes.remove(serial)
            }
        }
    }

    /** Detaches a single device tab, freeing its runtime but keeping the attach form input. */
    fun detach(serial: String) {
        scope.launch {
            val rt = runtimes.remove(serial)
            rt?.streamJob?.cancel()
            withContext(Dispatchers.IO) { rt?.session?.close() }
            _state.update { ui ->
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
        _state.value.selectedSerial?.let { detach(it) }
    }

    fun detachAll() {
        runtimes.keys.toList().forEach { detach(it) }
    }

    fun upsertRule(rule: InterceptRule) {
        _state.update { state ->
            val idx = state.interceptRules.indexOfFirst { it.id == rule.id }
            val next = if (idx < 0) state.interceptRules + rule
            else state.interceptRules.toMutableList().apply { this[idx] = rule }
            state.copy(interceptRules = next)
        }
        // Fan out to every attached device, each with its own protocol rule id.
        runtimes.forEach { (_, rt) ->
            val s = rt.session
            val existingPid = rt.ruleIdMap[rule.id]
            if (existingPid == null) {
                val pid = rt.nextProtocolRuleId++
                rt.ruleIdMap[rule.id] = pid
                scope.launch(Dispatchers.IO) {
                    com.jisungbin.networkinspector.protocol.RuleSender.sendAdd(
                        s.client, s.pid, s.streamId, pid, rule.toHostRule(),
                    )
                }
            } else {
                scope.launch(Dispatchers.IO) {
                    com.jisungbin.networkinspector.protocol.RuleSender.sendUpdate(
                        s.client, s.pid, s.streamId, existingPid, rule.toHostRule(),
                    )
                }
            }
        }
        persistRules()
    }

    fun removeRule(id: String) {
        _state.update { state ->
            state.copy(interceptRules = state.interceptRules.filterNot { it.id == id })
        }
        runtimes.forEach { (_, rt) ->
            val pid = rt.ruleIdMap.remove(id)
            if (pid != null) {
                val s = rt.session
                scope.launch(Dispatchers.IO) {
                    com.jisungbin.networkinspector.protocol.RuleSender.sendRemove(
                        s.client, s.pid, s.streamId, pid,
                    )
                }
            }
        }
        persistRules()
    }

    private fun InterceptRule.toHostRule(): com.jisungbin.networkinspector.protocol.HostRule =
        com.jisungbin.networkinspector.protocol.HostRule(
            id = id,
            urlPattern = urlPattern,
            method = method,
            replacementStatus = replacementStatus,
            replacementContentType = replacementContentType,
            replacementBody = replacementBody,
            addedHeaders = addedHeaders,
            enabled = enabled,
        )

    private fun persistRules() {
        RulesStorage.save(_state.value.interceptRules)
    }

    fun importRulesFromFile(file: File) {
        val imported = runCatching { RulesStorage.importFrom(file) }.getOrNull() ?: return
        _state.update { it.copy(interceptRules = imported) }
        // Re-sync every attached device: drop old mappings, push the imported set fresh.
        runtimes.forEach { (_, rt) ->
            val s = rt.session
            val oldIds = rt.ruleIdMap.values.toList()
            rt.ruleIdMap.clear()
            val toAdd = imported.map { r ->
                val pid = rt.nextProtocolRuleId++
                rt.ruleIdMap[r.id] = pid
                pid to r
            }
            scope.launch(Dispatchers.IO) {
                oldIds.forEach {
                    com.jisungbin.networkinspector.protocol.RuleSender.sendRemove(s.client, s.pid, s.streamId, it)
                }
                toAdd.forEach { (pid, r) ->
                    com.jisungbin.networkinspector.protocol.RuleSender.sendAdd(
                        s.client, s.pid, s.streamId, pid, r.toHostRule(),
                    )
                }
            }
        }
        persistRules()
    }

    fun exportRulesToFile(file: File) {
        RulesStorage.exportTo(file, _state.value.interceptRules)
    }

    private fun resolveStudioBundleDir(): File {
        val p = System.getProperty("network.inspector.studio.bundle")
            ?: error("Set -Dnetwork.inspector.studio.bundle=<path>")
        return File(p).also { require(it.isDirectory) { "Not a dir: $p" } }
    }

    private fun AttachStage.toPhase(): AttachPhase = when (this) {
        AttachStage.Deploying -> AttachPhase.Deploying
        AttachStage.DaemonStart -> AttachPhase.DaemonStart
        AttachStage.AttachAgent -> AttachPhase.AttachAgent
        AttachStage.Forwarding -> AttachPhase.Forwarding
        AttachStage.CreatingInspector -> AttachPhase.CreatingInspector
    }

    private fun List<com.jisungbin.networkinspector.engine.NetworkRow>.replaceOrAppend(
        row: com.jisungbin.networkinspector.engine.NetworkRow,
    ): List<com.jisungbin.networkinspector.engine.NetworkRow> {
        val idx = indexOfFirst { it.connectionId == row.connectionId }
        return if (idx >= 0) toMutableList().apply { this[idx] = row } else this + row
    }

    private fun matchesRow(row: com.jisungbin.networkinspector.engine.NetworkRow, rule: InterceptRule): Boolean {
        if (!rule.enabled) return false
        if (rule.method != "ANY" && rule.method.uppercase() != row.method.uppercase()) return false
        val needle = rule.urlPattern.removePrefix("https://").removePrefix("http://")
        return needle.isNotBlank() && needle in row.url
    }
}
