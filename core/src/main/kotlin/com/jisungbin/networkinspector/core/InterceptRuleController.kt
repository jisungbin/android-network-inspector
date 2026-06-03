package com.jisungbin.networkinspector.core

import com.jisungbin.networkinspector.core.util.RulesStorage
import java.io.File

/** Intercept-rule CRUD: updates shared state, persists to disk, and fans out to attached devices. */
internal class InterceptRuleController(
    private val state: AppState,
    private val ruleChannels: () -> Collection<DeviceRuleChannel>,
) {
    fun upsert(rule: InterceptRule) {
        state.update { s ->
            val idx = s.interceptRules.indexOfFirst { it.id == rule.id }
            val next = if (idx < 0) s.interceptRules + rule
            else s.interceptRules.toMutableList().apply { this[idx] = rule }
            s.copy(interceptRules = next)
        }
        ruleChannels().forEach { it.upsert(rule) }
        persist()
    }

    fun remove(id: String) {
        state.update { s -> s.copy(interceptRules = s.interceptRules.filterNot { it.id == id }) }
        ruleChannels().forEach { it.remove(id) }
        persist()
    }

    fun importFromFile(file: File) {
        val imported = runCatching { RulesStorage.importFrom(file) }.getOrNull() ?: return
        state.update { it.copy(interceptRules = imported) }
        ruleChannels().forEach { it.reset(imported) }
        persist()
    }

    fun exportToFile(file: File) = RulesStorage.exportTo(file, state.ui.interceptRules)

    /** Pushes the current rule set onto a freshly attached device's channel. */
    fun pushInitial(channel: DeviceRuleChannel) = channel.pushInitial(state.ui.interceptRules)

    private fun persist() = RulesStorage.save(state.ui.interceptRules)
}
