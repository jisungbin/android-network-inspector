package com.jisungbin.networkinspector.core

import com.jisungbin.networkinspector.core.util.IgnoredHostsStorage
import com.jisungbin.networkinspector.core.util.hostOf

/** Owns the Settings "ignored hosts" list and its on-disk persistence. */
internal class IgnoredHostStore(private val state: AppState) {
    fun add(host: String) {
        val normalized = host.trim().removePrefix("https://").removePrefix("http://")
            .substringBefore("/").substringBefore("?").lowercase()
        if (normalized.isEmpty()) return
        state.update { s ->
            if (s.ignoredHosts.any { it.equals(normalized, ignoreCase = true) }) s
            else s.copy(ignoredHosts = s.ignoredHosts + normalized)
        }
        IgnoredHostsStorage.save(state.ui.ignoredHosts)
    }

    fun remove(host: String) {
        state.update { s ->
            s.copy(ignoredHosts = s.ignoredHosts.filterNot { it.equals(host, ignoreCase = true) })
        }
        IgnoredHostsStorage.save(state.ui.ignoredHosts)
    }

    fun ignoreHostOf(url: String) = add(hostOf(url))
}
