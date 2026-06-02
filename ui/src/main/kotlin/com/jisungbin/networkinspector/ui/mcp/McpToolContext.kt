package com.jisungbin.networkinspector.ui.mcp

import com.jisungbin.networkinspector.engine.ConnectionState
import com.jisungbin.networkinspector.engine.NetworkRow
import com.jisungbin.networkinspector.ui.AppStore
import com.jisungbin.networkinspector.ui.AttachState
import com.jisungbin.networkinspector.ui.DeviceSession
import com.jisungbin.networkinspector.ui.util.DecodedBody
import com.jisungbin.networkinspector.ui.util.decodeBody
import com.jisungbin.networkinspector.ui.util.hostOf
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.types.CallToolRequest
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.util.Base64

/**
 * A recoverable, caller-facing error. Thrown by the helpers below and converted into an
 * `isError = true` tool result by [guard], so individual tool bodies stay linear and never
 * have to assemble error payloads by hand.
 */
internal class ToolError(message: String) : Exception(message)

/**
 * Shared state and formatting helpers for every MCP tool. Holds the single live [AppStore]
 * that the desktop UI also renders from — so anything a tool mutates (rules, attach) shows up
 * in the window immediately, and anything a tool reads is exactly what the user sees.
 */
internal class McpToolContext(val store: AppStore, val log: McpLog) {

    private val pretty = Json { prettyPrint = true }
    private val compact = Json

    /** The reachable MCP endpoint URL, injected by the server so get_status can report it. */
    var serverEndpoint: String? = null

    // ---- result builders -------------------------------------------------------------------

    fun ok(element: JsonElement): CallToolResult =
        CallToolResult(content = listOf(TextContent(pretty.encodeToString(JsonElement.serializer(), element))))

    fun ok(text: String): CallToolResult =
        CallToolResult(content = listOf(TextContent(text)))

    fun err(message: String): CallToolResult =
        CallToolResult(content = listOf(TextContent(message)), isError = true)

    /**
     * Registers a tool on [server] and routes every invocation through one logged, guarded path:
     * logs the call (name + argument summary), runs [handler], then logs success with elapsed time
     * or failure with the message — and turns [ToolError]/unexpected exceptions into clean
     * `isError = true` results. This is the single choke point that guarantees every MCP tool
     * action lands in [log] (and the disk log) uniformly.
     */
    fun tool(
        server: Server,
        name: String,
        description: String,
        inputSchema: ToolSchema = ToolSchema(),
        handler: suspend (CallToolRequest) -> CallToolResult,
    ) {
        server.addTool(name = name, description = description, inputSchema = inputSchema) { request ->
            log.add(McpLogLevel.INFO, name, "→ ${summarizeArgs(request.args)}")
            val startNs = System.nanoTime()
            fun ms() = (System.nanoTime() - startNs) / 1_000_000
            try {
                val result = handler(request)
                if (result.isError == true) {
                    log.add(McpLogLevel.ERROR, name, "✗ ${ms()}ms · ${firstText(result).take(300)}")
                } else {
                    log.add(McpLogLevel.INFO, name, "✓ ${ms()}ms")
                }
                result
            } catch (e: ToolError) {
                log.add(McpLogLevel.ERROR, name, "✗ ${ms()}ms · ${e.message}")
                err(e.message ?: "error")
            } catch (e: Exception) {
                log.add(McpLogLevel.ERROR, name, "✗ ${ms()}ms · ${e::class.simpleName}: ${e.message}")
                err("${e::class.simpleName}: ${e.message}")
            }
        }
    }

    private fun summarizeArgs(args: JsonObject): String {
        if (args.isEmpty()) return "(no args)"
        val text = compact.encodeToString(JsonObject.serializer(), args)
        return if (text.length > 200) text.take(200) + "…" else text
    }

    private fun firstText(result: CallToolResult): String =
        (result.content.firstOrNull() as? TextContent)?.text ?: ""

    // ---- device / session resolution -------------------------------------------------------

    /**
     * Resolves which device a tool should act on. Explicit [serial] wins; otherwise falls back
     * to the active inspector tab, then to the sole streaming device. Throws [ToolError] with an
     * actionable message when the choice is ambiguous or there is nothing attached.
     */
    fun requireSerial(serial: String?): String {
        val ui = store.state.value
        if (serial != null) {
            if (ui.sessions.containsKey(serial) || ui.devices.any { it.serial == serial }) return serial
            throw ToolError("unknown device serial '$serial'. Call list_devices to see connected devices.")
        }
        ui.selectedSerial?.let { return it }
        val streaming = ui.sessions.values.filter { it.attach is AttachState.Streaming }.map { it.serial }
        return when {
            streaming.size == 1 -> streaming.first()
            streaming.isEmpty() -> throw ToolError("no attached device. Call attach first, or pass 'serial'.")
            else -> throw ToolError("multiple devices attached (${streaming.joinToString()}); pass 'serial'.")
        }
    }

    fun session(serial: String): DeviceSession =
        store.state.value.sessions[serial] ?: throw ToolError("no session for device '$serial'.")

    fun rows(serial: String): List<NetworkRow> = session(serial).rows

    fun row(serial: String, connectionId: Long): NetworkRow =
        rows(serial).firstOrNull { it.connectionId == connectionId }
            ?: throw ToolError("no captured request with connectionId=$connectionId on '$serial'.")

    // ---- row formatting --------------------------------------------------------------------

    /** Device-clock duration in ms, derived from the ns request/response timestamps. */
    fun durationMs(row: NetworkRow): Long? {
        val end = row.endTimestamp ?: return null
        val d = (end - row.startTimestamp) / 1_000_000
        return d.takeIf { it >= 0 }
    }

    /** Compact, body-free view for list/search/tail results. */
    fun rowSummary(row: NetworkRow): JsonObject = buildJsonObject {
        put("connectionId", row.connectionId)
        put("method", row.method)
        put("url", row.url)
        put("host", hostOf(row.url))
        if (row.statusCode != null) put("status", row.statusCode!!) else put("status", JsonNull)
        put("state", row.state.name)
        put("protocol", row.protocol.name)
        put("mocked", row.mocked)
        durationMs(row)?.let { put("durationMs", it) }
        put("requestBytes", row.requestBody?.size ?: 0)
        put("responseBytes", row.responseBody?.size ?: 0)
        put("startedAt", row.startTimestamp)
        put("lastUpdatedAtMs", row.lastUpdatedAtMs)
    }

    /** Full view including headers and decoded bodies, for get_request. */
    fun rowDetail(row: NetworkRow, maxBodyChars: Int): JsonObject = buildJsonObject {
        put("connectionId", row.connectionId)
        put("method", row.method)
        put("url", row.url)
        put("host", hostOf(row.url))
        if (row.statusCode != null) put("status", row.statusCode!!) else put("status", JsonNull)
        put("state", row.state.name)
        put("protocol", row.protocol.name)
        put("mocked", row.mocked)
        durationMs(row)?.let { put("durationMs", it) }
        put("request", buildJsonObject {
            put("headers", headersJson(row.requestHeaders))
            put("body", bodyJson(row.requestBody, row.requestHeaders, maxBodyChars))
        })
        put("response", buildJsonObject {
            put("headers", headersJson(row.responseHeaders))
            put("body", bodyJson(row.responseBody, row.responseHeaders, maxBodyChars))
        })
    }

    private fun headersJson(headers: List<Pair<String, List<String>>>): JsonObject = buildJsonObject {
        headers.forEach { (name, values) -> put(name, values.joinToString(", ")) }
    }

    /**
     * Decodes a body for display: gunzips/UTF-8s text (pretty-printing JSON via the shared
     * [decodeBody]), truncates to [maxBodyChars], and base64-encodes binary payloads so an agent
     * can still recover the bytes.
     */
    fun bodyJson(bytes: ByteArray?, headers: List<Pair<String, List<String>>>, maxBodyChars: Int): JsonElement {
        if (bytes == null || bytes.isEmpty()) return JsonNull
        val decoded: DecodedBody? = runCatching { decodeBody(bytes, headers, showFull = true) }.getOrNull()
        return buildJsonObject {
            put("size", bytes.size)
            put("encoding", decoded?.encoding ?: "identity")
            if (decoded != null && !decoded.isBinary) {
                if (decoded.isJson) put("isJson", true)
                val text = decoded.text
                if (text.length > maxBodyChars) {
                    put("text", text.take(maxBodyChars))
                    put("truncated", true)
                    put("fullChars", text.length)
                } else {
                    put("text", text)
                }
            } else {
                put("binary", true)
                put("base64", Base64.getEncoder().encodeToString(bytes))
            }
        }
    }

    fun statusClass(code: Int?): String = when (code) {
        null -> "in-flight"
        in 200..299 -> "2xx"
        in 300..399 -> "3xx"
        in 400..499 -> "4xx"
        in 500..599 -> "5xx"
        else -> "other"
    }

    fun isFailure(row: NetworkRow): Boolean =
        row.state == ConnectionState.FAILED || (row.statusCode != null && row.statusCode!! >= 400)
}

// ---- JsonObject argument accessors ---------------------------------------------------------
// MCP delivers tool arguments as a JsonObject; these read the loosely-typed values defensively
// (numbers may arrive as JSON strings from some clients), returning null when absent or null.

internal fun JsonObject.string(key: String): String? =
    (this[key] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.contentOrNull

internal fun JsonObject.int(key: String): Int? =
    (this[key] as? JsonPrimitive)?.let { it.intOrNull ?: it.contentOrNull?.toIntOrNull() }

internal fun JsonObject.long(key: String): Long? =
    (this[key] as? JsonPrimitive)?.let { it.longOrNull ?: it.contentOrNull?.toLongOrNull() }

internal fun JsonObject.bool(key: String): Boolean? =
    (this[key] as? JsonPrimitive)?.let { it.booleanOrNull ?: it.contentOrNull?.toBooleanStrictOrNull() }

internal fun JsonObject.stringList(key: String): List<String>? =
    (this[key] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }

internal fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject

/** Tool arguments as a never-null object (MCP omits the field entirely for no-arg calls). */
internal val CallToolRequest.args: JsonObject get() = arguments ?: JsonObject(emptyMap())

/** A required string argument; throws [ToolError] when missing or blank. */
internal fun JsonObject.requireString(key: String): String =
    string(key)?.takeIf { it.isNotBlank() } ?: throw ToolError("missing required argument '$key'.")

internal fun JsonObject.requireLong(key: String): Long =
    long(key) ?: throw ToolError("missing required argument '$key'.")

internal fun JsonObject.requireInt(key: String): Int =
    int(key) ?: throw ToolError("missing required argument '$key'.")
