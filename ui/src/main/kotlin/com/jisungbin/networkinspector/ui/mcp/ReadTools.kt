package com.jisungbin.networkinspector.ui.mcp

import com.jisungbin.networkinspector.engine.ConnectionState
import com.jisungbin.networkinspector.engine.NetworkRow
import com.jisungbin.networkinspector.ui.util.decodeBody
import com.jisungbin.networkinspector.ui.util.hostOf
import io.modelcontextprotocol.kotlin.sdk.server.Server
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/** Read-only tools: query, inspect, search, tail and summarize the captured traffic. */
internal fun registerReadTools(server: Server, ctx: McpToolContext) {

    ctx.tool(
        server,
        name = "list_requests",
        description = "List captured HTTP requests for a device (most recent first), without bodies. " +
            "Filter by method, status class, url substring or protocol. Reflects exactly what the " +
            "inspector window shows (respects pause).",
        inputSchema = toolSchema(
            props = arrayOf(
                serialProp,
                "method" to strProp("Filter by HTTP method, e.g. GET, POST."),
                "status" to enumProp(
                    "Filter by status class.",
                    listOf("2xx", "3xx", "4xx", "5xx", "success", "redirect", "clientError", "serverError", "inFlight", "failed"),
                ),
                "urlContains" to strProp("Keep only requests whose URL contains this substring (case-insensitive)."),
                "protocol" to enumProp("Filter by transport.", listOf("JAVA_NET", "OKHTTP2", "OKHTTP3", "UNKNOWN")),
                "limit" to intProp("Max requests to return. Default 50."),
                "offset" to intProp("Skip this many before returning. Default 0."),
            ),
        ),
    ) { request ->
        val a = request.args
        val serial = ctx.requireSerial(a.string("serial"))
        val all = ctx.rows(serial)
        val filtered = all
            .filter { a.string("method")?.let { m -> it.method.equals(m, true) } ?: true }
            .filter { a.string("urlContains")?.let { u -> it.url.contains(u, true) } ?: true }
            .filter { a.string("protocol")?.let { p -> it.protocol.name.equals(p, true) } ?: true }
            .filter { matchesStatus(it, a.string("status")) }
            .sortedByDescending { it.startTimestamp }
        val offset = (a.int("offset") ?: 0).coerceAtLeast(0)
        val limit = (a.int("limit") ?: 50).coerceIn(1, 1000)
        val page = filtered.drop(offset).take(limit)
        ctx.ok(buildJsonObject {
            put("serial", serial)
            put("total", filtered.size)
            put("offset", offset)
            put("limit", limit)
            put("count", page.size)
            putJsonArray("requests") { page.forEach { add(ctx.rowSummary(it)) } }
        })
    }

    ctx.tool(
        server,
        name = "get_request",
        description = "Get one captured request in full: request/response headers and decoded bodies " +
            "(JSON pretty-printed, binary base64-encoded). Bodies are truncated to maxBodyChars.",
        inputSchema = toolSchema(
            required = listOf("connectionId"),
            props = arrayOf(
                serialProp,
                "connectionId" to intProp("The connectionId from list_requests/search_requests."),
                "maxBodyChars" to intProp("Truncate each decoded text body to this many characters. Default 20000."),
            ),
        ),
    ) { request ->
        val a = request.args
        val serial = ctx.requireSerial(a.string("serial"))
        val row = ctx.row(serial, a.requireLong("connectionId"))
        val maxChars = (a.int("maxBodyChars") ?: 20_000).coerceIn(256, 2_000_000)
        ctx.ok(ctx.rowDetail(row, maxChars))
    }

    ctx.tool(
        server,
        name = "search_requests",
        description = "Full-text search across captured requests — URL, headers and/or decoded bodies. " +
            "Returns matching request summaries with where the match was found.",
        inputSchema = toolSchema(
            required = listOf("query"),
            props = arrayOf(
                serialProp,
                "query" to strProp("Substring to search for (case-insensitive)."),
                "in" to enumProp("Where to search. Default all.", listOf("url", "headers", "body", "all")),
                "limit" to intProp("Max matches to return. Default 50."),
            ),
        ),
    ) { request ->
        val a = request.args
        val serial = ctx.requireSerial(a.string("serial"))
        val query = a.requireString("query")
        val scope = a.string("in") ?: "all"
        val limit = (a.int("limit") ?: 50).coerceIn(1, 1000)
        val matches = ctx.rows(serial).mapNotNull { row ->
            val where = mutableListOf<String>()
            if (scope == "url" || scope == "all") {
                if (row.url.contains(query, true)) where += "url"
            }
            if (scope == "headers" || scope == "all") {
                if (headersContain(row.requestHeaders, query)) where += "requestHeaders"
                if (headersContain(row.responseHeaders, query)) where += "responseHeaders"
            }
            if (scope == "body" || scope == "all") {
                if (bodyContains(row.requestBody, row.requestHeaders, query)) where += "requestBody"
                if (bodyContains(row.responseBody, row.responseHeaders, query)) where += "responseBody"
            }
            if (where.isEmpty()) null else row to where
        }.sortedByDescending { it.first.startTimestamp }.take(limit)
        ctx.ok(buildJsonObject {
            put("serial", serial)
            put("query", query)
            put("count", matches.size)
            putJsonArray("matches") {
                matches.forEach { (row, where) ->
                    add(buildJsonObject {
                        ctx.rowSummary(row).forEach { (k, v) -> put(k, v) }
                        putJsonArray("matchedIn") { where.forEach { add(it) } }
                    })
                }
            }
        })
    }

    ctx.tool(
        server,
        name = "tail_requests",
        description = "Poll for requests created or updated since a cursor. Pass cursorMs=0 first, then " +
            "feed back the returned cursorMs to get only what changed. Use this to watch live traffic.",
        inputSchema = toolSchema(
            props = arrayOf(
                serialProp,
                "cursorMs" to intProp("Return requests whose lastUpdatedAtMs is greater than this. Default 0."),
                "limit" to intProp("Max requests to return. Default 100."),
            ),
        ),
    ) { request ->
        val a = request.args
        val serial = ctx.requireSerial(a.string("serial"))
        val cursor = a.long("cursorMs") ?: 0L
        val limit = (a.int("limit") ?: 100).coerceIn(1, 1000)
        val updated = ctx.rows(serial)
            .filter { it.lastUpdatedAtMs > cursor }
            .sortedBy { it.lastUpdatedAtMs }
            .take(limit)
        val nextCursor = updated.maxOfOrNull { it.lastUpdatedAtMs } ?: cursor
        ctx.ok(buildJsonObject {
            put("serial", serial)
            put("cursorMs", nextCursor)
            put("count", updated.size)
            putJsonArray("requests") { updated.forEach { add(ctx.rowSummary(it)) } }
        })
    }

    ctx.tool(
        server,
        name = "summarize_traffic",
        description = "Aggregate stats for a device's captured traffic: counts by status class and method, " +
            "and per-host call count, failure count and latency. Start here to spot what is slow or breaking.",
        inputSchema = toolSchema(props = arrayOf(serialProp)),
    ) { request ->
        val a = request.args
        val serial = ctx.requireSerial(a.string("serial"))
        val rows = ctx.rows(serial)
        ctx.ok(buildJsonObject {
            put("serial", serial)
            put("total", rows.size)
            put("inFlight", rows.count { it.state == ConnectionState.IN_FLIGHT })
            put("completed", rows.count { it.state == ConnectionState.COMPLETED })
            put("failed", rows.count { it.state == ConnectionState.FAILED })
            put("mocked", rows.count { it.mocked })
            put("byStatusClass", buildJsonObject {
                rows.groupingBy { ctx.statusClass(it.statusCode) }.eachCount()
                    .toSortedMap().forEach { (k, v) -> put(k, v) }
            })
            put("byMethod", buildJsonObject {
                rows.filter { it.method.isNotBlank() }.groupingBy { it.method.uppercase() }.eachCount()
                    .toSortedMap().forEach { (k, v) -> put(k, v) }
            })
            put("byHost", buildJsonArray {
                rows.groupBy { hostOf(it.url) }
                    .map { (host, hostRows) -> hostStats(ctx, host, hostRows) }
                    .sortedByDescending { it.count }
                    .forEach { add(it.json) }
            })
        })
    }
}

private fun matchesStatus(row: NetworkRow, status: String?): Boolean {
    if (status == null) return true
    val code = row.statusCode
    return when (status.lowercase()) {
        "2xx", "success" -> code in 200..299
        "3xx", "redirect" -> code in 300..399
        "4xx", "clienterror" -> code in 400..499
        "5xx", "servererror" -> code in 500..599
        "inflight" -> row.state == ConnectionState.IN_FLIGHT
        "failed" -> row.state == ConnectionState.FAILED
        else -> true
    }
}

private fun headersContain(headers: List<Pair<String, List<String>>>, query: String): Boolean =
    headers.any { (k, vs) -> k.contains(query, true) || vs.any { it.contains(query, true) } }

private fun bodyContains(bytes: ByteArray?, headers: List<Pair<String, List<String>>>, query: String): Boolean {
    if (bytes == null || bytes.isEmpty()) return false
    val text = runCatching { decodeBody(bytes, headers, showFull = true) }.getOrNull()?.takeIf { !it.isBinary }?.text
        ?: runCatching { String(bytes, Charsets.UTF_8) }.getOrNull()
    return text?.contains(query, true) == true
}

private class HostStat(val count: Int, val json: kotlinx.serialization.json.JsonObject)

private fun hostStats(ctx: McpToolContext, host: String, rows: List<NetworkRow>): HostStat {
    val durations = rows.mapNotNull { ctx.durationMs(it) }
    val json = buildJsonObject {
        put("host", host.ifBlank { "(none)" })
        put("count", rows.size)
        put("failures", rows.count { ctx.isFailure(it) })
        if (durations.isNotEmpty()) {
            put("avgDurationMs", durations.average().toLong())
            put("maxDurationMs", durations.max())
        }
    }
    return HostStat(rows.size, json)
}
