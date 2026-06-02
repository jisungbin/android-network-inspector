package com.jisungbin.networkinspector.ui.mcp

import com.jisungbin.networkinspector.log.DiskLogger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

enum class McpLogLevel { INFO, WARN, ERROR }

/**
 * One line in the MCP activity log: a server lifecycle event ([tool] == null) or a tool call
 * ([tool] == the tool name). [seq] is a stable, monotonic id for list keys.
 */
data class McpLogEntry(
    val seq: Long,
    val atMs: Long,
    val level: McpLogLevel,
    val tool: String?,
    val message: String,
)

/**
 * In-memory ring buffer of MCP activity, exposed as a [StateFlow] so the Settings log viewer can
 * render it live, and mirrored to the rolling [DiskLogger] file. Every MCP operation — server
 * start/stop and each tool call (begin / success / failure) — is recorded here from one place
 * ([McpToolContext.tool] and [InspectorMcpServer]), so the log can never silently miss an action.
 *
 * Thread-safe: tool handlers run on Ktor IO threads, lifecycle events on the UI/IO threads.
 */
class McpLog(private val capacity: Int = 500) {
    private val _entries = MutableStateFlow<List<McpLogEntry>>(emptyList())
    val entries: StateFlow<List<McpLogEntry>> = _entries.asStateFlow()

    private var seq = 0L

    @Synchronized
    fun add(level: McpLogLevel, tool: String?, message: String) {
        val entry = McpLogEntry(seq++, System.currentTimeMillis(), level, tool, message)
        _entries.update { (it + entry).takeLast(capacity) }
        val tag = if (tool != null) "[mcp:$tool]" else "[mcp]"
        DiskLogger.log("$tag ${level.name} $message")
    }

    fun info(tool: String?, message: String) = add(McpLogLevel.INFO, tool, message)
    fun error(tool: String?, message: String) = add(McpLogLevel.ERROR, tool, message)

    fun clear() {
        _entries.value = emptyList()
    }
}
