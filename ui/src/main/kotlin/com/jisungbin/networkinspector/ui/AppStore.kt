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
import com.jisungbin.networkinspector.ui.util.SessionExporter
import com.jisungbin.networkinspector.ui.util.applyFilters
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

class AppStore {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    private var bridge: AndroidDebugBridge? = null
    private var session: AttachSession? = null
    private var streamJob: Job? = null
    private val aggregator = RowAggregator()

    init {
        scope.launch { refreshDevices() }
    }

    fun refreshDevices() {
        scope.launch {
            withContext(Dispatchers.IO) {
                val b = bridge ?: AdbBridge.start(AdbBridge.resolveAdb()).also { bridge = it }
                val devices = b.devices.toList().map { it.snapshot() }
                _state.update {
                    it.copy(
                        devices = devices,
                        deviceSerial = it.deviceSerial ?: devices.firstOrNull()?.serial,
                    )
                }
                _state.value.deviceSerial?.let { loadPackagesFor(it) }
            }
        }
    }

    fun updateDevice(serial: String?) {
        _state.update {
            it.copy(
                deviceSerial = serial,
                packages = emptyList(),
                packageName = "",
                activity = "",
            )
        }
        if (serial != null) scope.launch(Dispatchers.IO) { loadPackagesFor(serial) }
    }

    fun updatePackage(name: String) {
        _state.update {
            it.copy(
                packageName = name,
                activity = "",
                runningPid = null,
                activityResolving = name.isNotBlank(),
            )
        }
        if (name.isBlank()) return
        val serial = _state.value.deviceSerial ?: return
        scope.launch(Dispatchers.IO) {
            val device = bridge?.devices?.toList()?.firstOrNull { it.serialNumber == serial }
            val pid = device?.pidOf(name)
            val activity = if (pid == null) device?.resolveLauncherActivity(name) else null
            _state.update {
                it.copy(
                    runningPid = pid,
                    activity = activity ?: "",
                    activityResolving = false,
                    attachMode = if (pid != null) AttachMode.AttachRunning else AttachMode.ColdStart,
                )
            }
        }
    }

    fun updateActivity(activity: String) =
        _state.update { it.copy(activity = activity, activityResolving = false) }

    fun updateMode(mode: AttachMode) = _state.update { it.copy(attachMode = mode) }

    private fun loadPackagesFor(serial: String) {
        _state.update { it.copy(packagesLoading = true) }
        val device = bridge?.devices?.toList()?.firstOrNull { it.serialNumber == serial }
        if (device == null) {
            _state.update { it.copy(packagesLoading = false) }
            return
        }
        val pkgs = runCatching { device.listThirdPartyPackages() }.getOrDefault(emptyList())
        val foreground = runCatching { device.foregroundPackage() }.getOrNull()
            ?.takeIf { it in pkgs }
        _state.update { it.copy(packages = pkgs, packagesLoading = false) }
        if (foreground != null && _state.value.packageName.isBlank()) updatePackage(foreground)
    }
    fun updateSearch(q: String) = _state.update { it.copy(search = q) }
    fun updateStatusFilter(f: StatusFilter) = _state.update { it.copy(statusFilter = f) }
    fun updateMethodFilter(m: String?) = _state.update { it.copy(methodFilter = m) }
    fun selectRow(id: Long?) = _state.update { it.copy(selectedRowId = id) }

    fun setDestination(d: Destination) = _state.update { it.copy(destination = d) }
    fun setTheme(t: ThemePreference) = _state.update { it.copy(theme = t) }

    fun toggleSort(key: SortKey) = _state.update {
        if (it.sortKey == key) it.copy(sortDescending = !it.sortDescending)
        else it.copy(sortKey = key, sortDescending = false)
    }

    fun setPaused(value: Boolean) = _state.update {
        if (!value) it.copy(paused = false, rows = aggregator.snapshot)
        else it.copy(paused = true)
    }

    fun toggleAutoScroll() = _state.update { it.copy(autoScroll = !it.autoScroll) }

    fun clearRows() {
        aggregator.reset()
        _state.update { it.copy(rows = emptyList(), selectedRowId = null, firstEventAt = null) }
    }

    fun exportSessionJson(): String {
        val s = _state.value
        val filtered = s.rows.applyFilters(s.search, s.statusFilter, s.methodFilter)
        return SessionExporter.export(filtered, s)
    }

    fun attach() {
        val s = _state.value
        val serial = s.deviceSerial ?: return error("device unselected")
        if (s.packageName.isBlank()) return error("package empty")
        if (s.attachMode == AttachMode.ColdStart && s.activity.isBlank()) {
            return error("activity required for cold start")
        }
        _state.update { it.copy(attach = AttachState.Connecting(AttachPhase.Deploying)) }
        scope.launch {
            var session: AttachSession? = null
            try {
                val orchestrator = withContext(Dispatchers.IO) {
                    val device = bridge!!.devices.toList().findBySerial(serial)
                    AttachOrchestrator(device, s.packageName, resolveStudioBundleDir())
                }
                val activityArg = s.activity.takeIf { s.attachMode == AttachMode.ColdStart }
                val opened = withContext(Dispatchers.IO) {
                    orchestrator.attach(s.attachMode, activityArg) { stage ->
                        _state.update { it.copy(attach = AttachState.Connecting(stage.toPhase())) }
                    }
                }
                session = opened
                this@AppStore.session = opened
                _state.update {
                    it.copy(
                        attach = AttachState.Streaming(opened.pid, opened.hostPort),
                        destination = Destination.INSPECTOR,
                    )
                }
                streamJob = scope.launch(Dispatchers.IO) {
                    opened.networkEvents().collect { event ->
                        val updated = aggregator.consume(event) ?: return@collect
                        if (_state.value.firstEventAt == null) {
                            _state.update { it.copy(firstEventAt = System.currentTimeMillis()) }
                        }
                        if (_state.value.paused) return@collect
                        _state.update { ui ->
                            val replaced = ui.rows.replaceOrAppend(updated)
                            ui.copy(rows = replaced)
                        }
                    }
                }
                scope.launch(Dispatchers.IO) {
                    val received = kotlinx.coroutines.withTimeoutOrNull(5_000) {
                        opened.rawEvents()
                            .filter { it.kind == com.android.tools.profiler.proto.Common.Event.Kind.APP_INSPECTION_RESPONSE }
                            .firstOrNull()
                    }
                    if (_state.value.attach is AttachState.Streaming && _state.value.inspectorReadyAt == null) {
                        _state.update { it.copy(inspectorReadyAt = System.currentTimeMillis()) }
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
                _state.update { it.copy(attach = AttachState.Failed(msg)) }
                runCatching { session?.close() }
                this@AppStore.session = null
            }
        }
    }

    fun detach() {
        scope.launch {
            streamJob?.cancel()
            streamJob = null
            withContext(Dispatchers.IO) { session?.close() }
            session = null
            _state.update {
                it.copy(
                    attach = AttachState.Idle,
                    rows = emptyList(),
                    selectedRowId = null,
                    destination = Destination.DEVICES,
                    inspectorReadyAt = null,
                    firstEventAt = null,
                )
            }
        }
    }

    fun upsertRule(rule: InterceptRule) = _state.update {
        val existing = it.interceptRules.indexOfFirst { r -> r.id == rule.id }
        val next = if (existing >= 0) it.interceptRules.toMutableList().apply { this[existing] = rule }
        else it.interceptRules + rule
        it.copy(interceptRules = next)
    }

    fun removeRule(id: String) = _state.update {
        it.copy(interceptRules = it.interceptRules.filterNot { r -> r.id == id })
    }

    fun applyRulesToInspector() {
        val rules = _state.value.interceptRules
            .filter { it.enabled }
            .map {
                com.jisungbin.networkinspector.protocol.HostRule(
                    id = it.id,
                    urlPattern = it.urlPattern,
                    method = it.method,
                    replacementStatus = it.replacementStatus,
                    replacementContentType = it.replacementContentType,
                    replacementBody = it.replacementBody,
                    addedHeaders = it.addedHeaders,
                    enabled = it.enabled,
                )
            }
        val s = session ?: return
        scope.launch(Dispatchers.IO) {
            com.jisungbin.networkinspector.protocol.RuleSender.apply(s.client, s.pid, s.streamId, rules)
        }
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
}
