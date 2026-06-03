package com.jisungbin.networkinspector.mcp

import com.jisungbin.networkinspector.log.DiskLogger
import com.jisungbin.networkinspector.core.AppStore
import io.ktor.server.cio.CIO
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.ServerOptions
import io.modelcontextprotocol.kotlin.sdk.server.mcpStreamableHttp
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.ServerCapabilities
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.net.InetSocketAddress
import java.net.ServerSocket

/** Default loopback port for the embedded MCP endpoint. */
const val DEFAULT_MCP_PORT: Int = 38017

sealed interface McpServerStatus {
    data object Stopped : McpServerStatus
    data class Running(val port: Int, val endpoint: String) : McpServerStatus
    data class Failed(val message: String) : McpServerStatus
}

/**
 * Embeds a Model Context Protocol server inside the desktop app over Ktor's streamable-HTTP
 * transport, exposing the live inspector to an MCP client (e.g. Claude Code).
 *
 * It is intentionally in-process and shares the one [AppStore] the UI renders from: tools read the
 * exact rows the window shows, and tool mutations (intercept rules, attach/detach) flow back into
 * the same state — so MCP actions are reflected in the UI in real time, and vice versa.
 *
 * Bound to 127.0.0.1 only; there is no auth, so it must never listen on a public interface.
 */
class InspectorMcpServer(
    private val store: AppStore,
    val port: Int = DEFAULT_MCP_PORT,
    /** Live activity log, shared with the Settings viewer; also mirrored to the disk log. */
    val log: McpLog = McpLog(),
) {
    private val _status = MutableStateFlow<McpServerStatus>(McpServerStatus.Stopped)
    val status: StateFlow<McpServerStatus> = _status.asStateFlow()

    /** The URL an MCP client connects to. */
    val endpoint: String = "http://127.0.0.1:$port/mcp"

    private var engine: EmbeddedServer<*, *>? = null

    @Synchronized
    fun start() {
        if (engine != null) return
        if (!portAvailable(port)) {
            val msg = "port $port is already in use"
            log.error(null, "server not started: $msg")
            _status.value = McpServerStatus.Failed(msg)
            return
        }
        try {
            val server = buildServer()
            val started = embeddedServer(CIO, host = "127.0.0.1", port = port) {
                mcpStreamableHttp { server }
            }
            started.start(wait = false)
            engine = started
            _status.value = McpServerStatus.Running(port, endpoint)
            log.info(null, "server listening on $endpoint")
        } catch (t: Throwable) {
            DiskLogger.logError("MCP server failed to start", t)
            log.error(null, "server failed to start: ${t.message ?: t::class.simpleName}")
            _status.value = McpServerStatus.Failed(t.message ?: t::class.simpleName ?: "unknown error")
        }
    }

    @Synchronized
    fun stop() {
        engine?.let {
            runCatching { it.stop(gracePeriodMillis = 500, timeoutMillis = 1_000) }
                .onFailure { e -> DiskLogger.logError("MCP server stop failed", e) }
            log.info(null, "server stopped")
        }
        engine = null
        _status.value = McpServerStatus.Stopped
    }

    @Synchronized
    fun restart() {
        stop()
        start()
    }

    private fun buildServer(): Server {
        val server = Server(
            serverInfo = Implementation(name = "android-network-inspector", version = "1.0.0"),
            options = ServerOptions(
                capabilities = ServerCapabilities(tools = ServerCapabilities.Tools(listChanged = true)),
            ),
        )
        val ctx = McpToolContext(store, log).apply { serverEndpoint = endpoint }
        registerReadTools(server, ctx)
        registerInterceptTools(server, ctx)
        registerSessionTools(server, ctx)
        registerExportTools(server, ctx)
        return server
    }

    private fun portAvailable(port: Int): Boolean = runCatching {
        ServerSocket().use { socket ->
            socket.reuseAddress = false
            socket.bind(InetSocketAddress("127.0.0.1", port))
        }
        true
    }.getOrDefault(false)
}
