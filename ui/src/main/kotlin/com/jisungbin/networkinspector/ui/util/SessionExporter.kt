package com.jisungbin.networkinspector.ui.util

import com.jisungbin.networkinspector.engine.NetworkRow
import com.jisungbin.networkinspector.ui.UiState
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.util.Base64

object SessionExporter {
    fun export(rows: List<NetworkRow>, state: UiState): String {
        val ordered = rows.sortedBy { it.startTimestamp }
        val root = buildJsonObject {
            put("exportedAt", DateTimeFormatter.ISO_INSTANT.format(Instant.now()))
            put("device", state.deviceSerial ?: "")
            put("package", state.packageName)
            putJsonObject("filterContext") {
                put("search", state.search)
                put("statusFilter", state.statusFilter.name)
                if (state.methodFilter != null) put("methodFilter", state.methodFilter)
                else put("methodFilter", JsonNull)
            }
            put("totalCount", ordered.size)
            putJsonArray("rows") {
                ordered.forEach { row -> add(rowToJson(row)) }
            }
        }
        return Json.encodeToString(JsonElement.serializer(), root)
    }

    private fun rowToJson(row: NetworkRow): JsonObject = buildJsonObject {
        put("connectionId", row.connectionId)
        put("startTimestamp", row.startTimestamp)
        val end = row.endTimestamp
        if (end != null) {
            put("endTimestamp", end)
            put("durationNs", end - row.startTimestamp)
        } else {
            put("endTimestamp", JsonNull)
        }
        put("method", row.method)
        put("url", row.url)
        put("protocol", row.protocol.name)
        val status = row.statusCode
        if (status != null) put("statusCode", status) else put("statusCode", JsonNull)
        put("state", row.state.name)
        put("lastUpdatedAtMs", row.lastUpdatedAtMs)
        val respAt = row.responseAtMs
        if (respAt != null) put("responseAtMs", respAt) else put("responseAtMs", JsonNull)
        put("request", buildJsonObject {
            put("headers", headersToJson(row.requestHeaders))
            put("body", bodyToJson(row.requestBody, row.requestHeaders))
        })
        put("response", buildJsonObject {
            put("headers", headersToJson(row.responseHeaders))
            put("body", bodyToJson(row.responseBody, row.responseHeaders))
        })
    }

    private fun headersToJson(headers: List<Pair<String, List<String>>>): JsonArray = buildJsonArray {
        headers.forEach { (name, values) ->
            add(buildJsonObject {
                put("name", name)
                put("values", buildJsonArray { values.forEach { add(JsonPrimitive(it)) } })
            })
        }
    }

    private fun bodyToJson(
        bytes: ByteArray?,
        headers: List<Pair<String, List<String>>>,
    ): JsonElement {
        if (bytes == null || bytes.isEmpty()) return JsonNull
        val decoded = runCatching { decodeBody(bytes, headers, showFull = true) }.getOrNull()
        return buildJsonObject {
            put("size", bytes.size)
            put("base64", Base64.getEncoder().encodeToString(bytes))
            if (decoded != null && !decoded.isBinary) {
                put("encoding", decoded.encoding)
                put("text", decoded.text)
                if (decoded.isJson) put("isJson", true)
            } else if (decoded != null) {
                put("encoding", decoded.encoding)
            }
        }
    }
}
