package com.jisungbin.networkinspector.ui.mcp

import com.jisungbin.networkinspector.ui.util.HarExporter
import com.jisungbin.networkinspector.ui.util.toCurl
import io.modelcontextprotocol.kotlin.sdk.server.Server

/** Export / repro tools: standard HAR archive and a runnable curl command. */
internal fun registerExportTools(server: Server, ctx: McpToolContext) {

    ctx.tool(
        server,
        name = "export_har",
        description = "Export a device's captured traffic as an HAR 1.2 archive (importable into Postman, " +
            "Charles, browser devtools). Optionally filter by URL substring and cap the entry count.",
        inputSchema = toolSchema(
            props = arrayOf(
                serialProp,
                "urlContains" to strProp("Only include requests whose URL contains this substring."),
                "limit" to intProp("Max entries (most recent kept). Default: all."),
            ),
        ),
    ) { request ->
        val a = request.args
        val serial = ctx.requireSerial(a.string("serial"))
        var rows = ctx.rows(serial)
        a.string("urlContains")?.let { needle -> rows = rows.filter { it.url.contains(needle, true) } }
        a.int("limit")?.let { n -> rows = rows.sortedByDescending { it.startTimestamp }.take(n.coerceAtLeast(1)) }
        // Return the HAR JSON verbatim (already a JSON document).
        ctx.ok(HarExporter.export(rows))
    }

    ctx.tool(
        server,
        name = "to_curl",
        description = "Render one captured request as a runnable curl command, including method, headers " +
            "and request body. Use it to reproduce or share a request.",
        inputSchema = toolSchema(
            required = listOf("connectionId"),
            props = arrayOf(
                serialProp,
                "connectionId" to intProp("The connectionId from list_requests/search_requests."),
            ),
        ),
    ) { request ->
        val a = request.args
        val serial = ctx.requireSerial(a.string("serial"))
        val row = ctx.row(serial, a.requireLong("connectionId"))
        ctx.ok(row.toCurl())
    }
}
