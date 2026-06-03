package com.jisungbin.networkinspector.core

import com.jisungbin.networkinspector.core.util.IgnoredHostsStorage
import com.jisungbin.networkinspector.core.util.RulesStorage
import com.jisungbin.networkinspector.core.util.SessionExporter
import com.jisungbin.networkinspector.core.util.applyFilters
import com.jisungbin.networkinspector.engine.AttachMode
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

/**
 * Thin facade over the single [AppState] (SSOT) and the responsibility-specific controllers. Keeps
 * the public action surface the UI and the embedded MCP server call, while delegating side effects
 * to [DeviceController]/[SessionController]/[InterceptRuleController]/[IgnoredHostStore]. Pure view
 * reducers stay inline here — they have no collaborators.
 */
class AppStore {
    private val appState = AppState()
    val state: StateFlow<UiState> = appState.flow.asStateFlow()

    private val bridge = BridgeProvider()
    private val ignoredHosts = IgnoredHostStore(appState)
    private val devices = DeviceController(appState, bridge)
    // Init order matters: `rules` reaches `sessions` only via a lambda (lazy), but `sessions`
    // captures `rules::pushInitial` eagerly — so `rules` must be declared before `sessions`.
    private val rules = InterceptRuleController(appState, ruleChannels = { sessions.ruleChannels() })
    private val sessions: SessionController =
        SessionController(appState, bridge, ::resolveStudioBundleDir, onAttached = rules::pushInitial)

    init {
        val savedRules = RulesStorage.load()
        val savedIgnored = IgnoredHostsStorage.load()
        if (savedRules.isNotEmpty() || savedIgnored.isNotEmpty()) {
            appState.update { it.copy(interceptRules = savedRules, ignoredHosts = savedIgnored) }
        }
        devices.refreshDevices()
    }

    // ---- device / package -------------------------------------------------------------------
    fun refreshDevices() = devices.refreshDevices()
    fun selectComposingDevice(serial: String?) = devices.selectComposingDevice(serial)
    fun updatePackage(serial: String, name: String) = devices.updatePackage(serial, name)
    fun updateActivity(serial: String, activity: String) = devices.updateActivity(serial, activity)
    fun updateMode(serial: String, mode: AttachMode) = devices.updateMode(serial, mode)
    fun loadPackages(serial: String) = devices.loadPackages(serial)

    // ---- attach / session -------------------------------------------------------------------
    fun attach(serial: String) = sessions.attach(serial)
    fun detach(serial: String) = sessions.detach(serial)
    fun detach() = sessions.detach()
    fun detachAll() = sessions.detachAll()
    fun setPaused(value: Boolean) = sessions.setPaused(value)
    fun clearRows() = sessions.clearRows()

    // ---- intercept rules --------------------------------------------------------------------
    fun upsertRule(rule: InterceptRule) = rules.upsert(rule)
    fun removeRule(id: String) = rules.remove(id)
    fun importRulesFromFile(file: File) = rules.importFromFile(file)
    fun exportRulesToFile(file: File) = rules.exportToFile(file)

    // ---- ignored hosts ----------------------------------------------------------------------
    fun addIgnoredHost(host: String) = ignoredHosts.add(host)
    fun removeIgnoredHost(host: String) = ignoredHosts.remove(host)
    fun ignoreHostOf(url: String) = ignoredHosts.ignoreHostOf(url)

    // ---- view reducers (pure state transitions) ---------------------------------------------
    fun updateSearch(q: String) = appState.update { it.copy(search = q) }
    fun updateStatusFilter(filter: StatusFilter) = appState.update { it.copy(statusFilter = filter) }
    fun updateMethodFilter(method: String?) = appState.update { it.copy(methodFilter = method) }

    fun selectRow(id: Long?) {
        val serial = appState.ui.selectedSerial ?: return
        appState.updateSession(serial) { it.copy(selectedRowId = id) }
    }

    fun selectTab(serial: String) = appState.update { it.copy(selectedSerial = serial) }
    fun setDestination(destination: Destination) = appState.update { it.copy(destination = destination) }
    fun setTheme(theme: ThemePreference) = appState.update { it.copy(theme = theme) }

    fun toggleSort(key: SortKey) = appState.update {
        if (it.sortKey == key) it.copy(sortDescending = !it.sortDescending)
        else it.copy(sortKey = key, sortDescending = false)
    }

    fun toggleAutoScroll() = appState.update { it.copy(autoScroll = !it.autoScroll) }

    fun exportSessionJson(): String {
        val ui = appState.ui
        val sess = ui.selectedSession ?: return "{}"
        val filtered = sess.rows.applyFilters(ui.search, ui.statusFilter, ui.methodFilter, ui.ignoredHosts)
        return SessionExporter.export(filtered, sess, ui)
    }

    private fun resolveStudioBundleDir(): File {
        val path = System.getProperty("network.inspector.studio.bundle")
            ?: error("Set -Dnetwork.inspector.studio.bundle=<path>")
        return File(path).also { require(it.isDirectory) { "Not a dir: $path" } }
    }
}
