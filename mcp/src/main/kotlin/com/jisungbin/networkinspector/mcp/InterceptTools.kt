package com.jisungbin.networkinspector.mcp

import com.jisungbin.networkinspector.core.InterceptRule
import com.jisungbin.networkinspector.core.util.decodeBody
import com.jisungbin.networkinspector.core.util.hostOf
import io.modelcontextprotocol.kotlin.sdk.server.Server
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import java.util.UUID

/**
 * Intercept-rule tools. Every mutation goes through [AppStore.upsertRule]/[AppStore.removeRule],
 * which fans the rule out to every attached device over the inspector protocol, persists it to
 * disk, and updates the shared UI state — so the Rules screen reflects each change instantly.
 */
internal fun registerInterceptTools(server: Server, ctx: McpToolContext) {

    ctx.tool(
        server,
        name = "list_intercept_rules",
        description = "List all mock/intercept rules with their definition and how many responses each " +
            "has mocked across attached devices.",
        inputSchema = toolSchema(),
    ) { _ ->
        val ui = ctx.store.state.value
        val hits = aggregatedHits(ctx)
        ctx.ok(buildJsonObject {
            put("count", ui.interceptRules.size)
            putJsonArray("rules") { ui.interceptRules.forEach { add(ruleJson(it, hits[it.id] ?: 0)) } }
        })
    }

    ctx.tool(
        server,
        name = "add_intercept_rule",
        description = "Add a mock rule that rewrites matching responses on every attached device. " +
            "Matches by URL substring (host + path) and optional method; replaces status, optionally " +
            "the body, content-type and extra headers.",
        inputSchema = toolSchema(
            required = listOf("urlPattern", "status"),
            props = arrayOf(
                "urlPattern" to strProp("URL (or host/path substring) to match, e.g. api.example.com/login."),
                "status" to intProp("Replacement HTTP status code, e.g. 500."),
                "name" to strProp("Human-readable rule name. Defaults to the urlPattern."),
                "method" to enumProp(
                    "HTTP method to match. Default ANY.",
                    listOf("ANY", "GET", "POST", "PUT", "DELETE", "PATCH", "HEAD", "OPTIONS"),
                ),
                "body" to strProp("Replacement response body. Omit to keep the original body."),
                "contentType" to strProp("Replacement Content-Type header, e.g. application/json."),
                "headers" to objProp("Extra response headers to add, as a { name: value } object."),
                "enabled" to boolProp("Whether the rule is active. Default true."),
            ),
        ),
    ) { request ->
        val a = request.args
        val rule = InterceptRule(
            id = UUID.randomUUID().toString(),
            name = a.string("name")?.ifBlank { null } ?: a.requireString("urlPattern"),
            urlPattern = a.requireString("urlPattern"),
            method = a.string("method")?.uppercase() ?: "ANY",
            replacementStatus = a.requireInt("status"),
            replacementContentType = a.string("contentType").orEmpty(),
            replacementBody = a.string("body").orEmpty(),
            addedHeaders = parseHeaders(a.obj("headers")),
            enabled = a.bool("enabled") ?: true,
        )
        ctx.store.upsertRule(rule)
        ctx.ok(buildJsonObject {
            put("created", true)
            put("rule", ruleJson(rule, 0))
        })
    }

    ctx.tool(
        server,
        name = "update_intercept_rule",
        description = "Update fields of an existing mock rule by id. Only the fields you pass are changed.",
        inputSchema = toolSchema(
            required = listOf("id"),
            props = arrayOf(
                "id" to strProp("Rule id from list_intercept_rules."),
                "urlPattern" to strProp("New URL/host/path substring to match."),
                "status" to intProp("New replacement status code."),
                "name" to strProp("New rule name."),
                "method" to enumProp(
                    "New method to match.",
                    listOf("ANY", "GET", "POST", "PUT", "DELETE", "PATCH", "HEAD", "OPTIONS"),
                ),
                "body" to strProp("New replacement body."),
                "contentType" to strProp("New replacement Content-Type."),
                "headers" to objProp("Replacement set of extra headers, as a { name: value } object."),
                "enabled" to boolProp("Enable/disable the rule."),
            ),
        ),
    ) { request ->
        val a = request.args
        val id = a.requireString("id")
        val existing = ctx.store.state.value.interceptRules.firstOrNull { it.id == id }
            ?: throw ToolError("no rule with id '$id'. Call list_intercept_rules.")
        val updated = existing.copy(
            name = a.string("name")?.ifBlank { null } ?: existing.name,
            urlPattern = a.string("urlPattern") ?: existing.urlPattern,
            method = a.string("method")?.uppercase() ?: existing.method,
            replacementStatus = a.int("status") ?: existing.replacementStatus,
            replacementContentType = a.string("contentType") ?: existing.replacementContentType,
            replacementBody = a.string("body") ?: existing.replacementBody,
            addedHeaders = a.obj("headers")?.let { parseHeaders(it) } ?: existing.addedHeaders,
            enabled = a.bool("enabled") ?: existing.enabled,
        )
        ctx.store.upsertRule(updated)
        ctx.ok(buildJsonObject {
            put("updated", true)
            put("rule", ruleJson(updated, aggregatedHits(ctx)[id] ?: 0))
        })
    }

    ctx.tool(
        server,
        name = "set_intercept_rule_enabled",
        description = "Enable or disable a mock rule by id without changing its definition.",
        inputSchema = toolSchema(
            required = listOf("id", "enabled"),
            props = arrayOf(
                "id" to strProp("Rule id from list_intercept_rules."),
                "enabled" to boolProp("true to enable, false to disable."),
            ),
        ),
    ) { request ->
        val a = request.args
        val id = a.requireString("id")
        val enabled = a.bool("enabled") ?: throw ToolError("missing required argument 'enabled'.")
        val existing = ctx.store.state.value.interceptRules.firstOrNull { it.id == id }
            ?: throw ToolError("no rule with id '$id'. Call list_intercept_rules.")
        val updated = existing.copy(enabled = enabled)
        ctx.store.upsertRule(updated)
        ctx.ok(buildJsonObject {
            put("id", id)
            put("enabled", enabled)
        })
    }

    ctx.tool(
        server,
        name = "remove_intercept_rule",
        description = "Delete a mock rule by id. Removes it from every attached device immediately.",
        inputSchema = toolSchema(
            required = listOf("id"),
            props = arrayOf("id" to strProp("Rule id from list_intercept_rules.")),
        ),
    ) { request ->
        val a = request.args
        val id = a.requireString("id")
        if (ctx.store.state.value.interceptRules.none { it.id == id }) {
            throw ToolError("no rule with id '$id'. Call list_intercept_rules.")
        }
        ctx.store.removeRule(id)
        ctx.ok(buildJsonObject {
            put("removed", true)
            put("id", id)
        })
    }

    ctx.tool(
        server,
        name = "mock_from_captured",
        description = "Promote a captured response into a mock rule that replays it: pins the recorded " +
            "status, content-type and body for that URL + method. Great for freezing a known-good (or " +
            "known-bad) response. Fails if the captured body is binary.",
        inputSchema = toolSchema(
            required = listOf("connectionId"),
            props = arrayOf(
                serialProp,
                "connectionId" to intProp("The connectionId of the captured request to freeze."),
                "name" to strProp("Rule name. Defaults to 'mock <method> <host>'."),
            ),
        ),
    ) { request ->
        val a = request.args
        val serial = ctx.requireSerial(a.string("serial"))
        val row = ctx.row(serial, a.requireLong("connectionId"))
        val status = row.statusCode
            ?: throw ToolError("request ${row.connectionId} has no response status yet; nothing to mock.")
        val decoded = row.responseBody?.let { runCatching { decodeBody(it, row.responseHeaders, showFull = true) }.getOrNull() }
        if (decoded != null && decoded.isBinary) {
            throw ToolError("captured response body is binary and cannot be replayed as a text mock.")
        }
        val contentType = row.responseHeaders
            .firstOrNull { it.first.equals("content-type", true) }?.second?.firstOrNull().orEmpty()
        val host = hostOf(row.url)
        val rule = InterceptRule(
            id = UUID.randomUUID().toString(),
            name = a.string("name")?.ifBlank { null } ?: "mock ${row.method} $host",
            urlPattern = row.url,
            method = row.method.ifBlank { "ANY" },
            replacementStatus = status,
            replacementContentType = contentType,
            replacementBody = decoded?.text.orEmpty(),
            addedHeaders = emptyList(),
            enabled = true,
        )
        ctx.store.upsertRule(rule)
        ctx.ok(buildJsonObject {
            put("created", true)
            put("fromConnectionId", row.connectionId)
            put("rule", ruleJson(rule, 0))
        })
    }
}

/** Sums per-device hit counts for each rule id across all sessions. */
private fun aggregatedHits(ctx: McpToolContext): Map<String, Int> {
    val out = HashMap<String, Int>()
    ctx.store.state.value.sessions.values.forEach { session ->
        session.ruleHits.forEach { (id, n) -> out[id] = (out[id] ?: 0) + n }
    }
    return out
}

private fun parseHeaders(obj: JsonObject?): List<Pair<String, String>> =
    obj?.mapNotNull { (k, v) ->
        if (k.isBlank()) null else k to ((v as? JsonPrimitive)?.contentOrNull ?: v.toString())
    }.orEmpty()

private fun ruleJson(rule: InterceptRule, hits: Int) = buildJsonObject {
    put("id", rule.id)
    put("name", rule.name)
    put("urlPattern", rule.urlPattern)
    put("method", rule.method)
    put("replacementStatus", rule.replacementStatus)
    put("replacementContentType", rule.replacementContentType)
    put("replacementBody", rule.replacementBody)
    put("enabled", rule.enabled)
    put("hits", hits)
    putJsonArray("addedHeaders") {
        rule.addedHeaders.forEach { (k, v) -> add(buildJsonObject { put("name", k); put("value", v) }) }
    }
}
