package com.jisungbin.networkinspector.core.util

import com.jisungbin.networkinspector.engine.NetworkRow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.util.Base64

/**
 * Exports captured [NetworkRow]s as an [HAR 1.2](http://www.softwareishard.com/blog/har-12-spec/)
 * archive — the standard format browsers/Postman/Charles import. Wall-clock timings are
 * best-effort: the device reports request/response timestamps in nanoseconds (used for `time`),
 * while `startedDateTime` is approximated from the host-side response timestamp.
 */
object HarExporter {
    private val pretty = Json { prettyPrint = true }

    fun export(rows: List<NetworkRow>, creatorName: String = "android-network-inspector", creatorVersion: String = "1.0"): String {
        val root = buildJsonObject {
            put("log", buildJsonObject {
                put("version", "1.2")
                put("creator", buildJsonObject {
                    put("name", creatorName)
                    put("version", creatorVersion)
                })
                putJsonArray("entries") {
                    rows.sortedBy { it.startTimestamp }.forEach { add(entry(it)) }
                }
            })
        }
        return pretty.encodeToString(JsonElement.serializer(), root)
    }

    private fun entry(row: NetworkRow) = buildJsonObject {
        val timeMs = row.endTimestamp?.let { ((it - row.startTimestamp) / 1_000_000).coerceAtLeast(0) } ?: 0L
        val startMs = row.responseAtMs ?: row.lastUpdatedAtMs
        put("startedDateTime", DateTimeFormatter.ISO_INSTANT.format(Instant.ofEpochMilli(startMs)))
        put("time", timeMs)
        put("request", buildJsonObject {
            put("method", row.method)
            put("url", row.url)
            put("httpVersion", "HTTP/1.1")
            put("cookies", JsonArray(emptyList()))
            put("headers", headers(row.requestHeaders))
            put("queryString", queryString(row.url))
            postData(row.requestBody, row.requestHeaders)?.let { put("postData", it) }
            put("headersSize", -1)
            put("bodySize", row.requestBody?.size ?: 0)
        })
        put("response", buildJsonObject {
            put("status", row.statusCode ?: 0)
            put("statusText", "")
            put("httpVersion", "HTTP/1.1")
            put("cookies", JsonArray(emptyList()))
            put("headers", headers(row.responseHeaders))
            put("content", content(row.responseBody, row.responseHeaders))
            put("redirectURL", "")
            put("headersSize", -1)
            put("bodySize", row.responseBody?.size ?: 0)
        })
        put("cache", buildJsonObject {})
        put("timings", buildJsonObject {
            put("send", 0)
            put("wait", timeMs)
            put("receive", 0)
        })
        put("_mocked", row.mocked)
        put("_connectionId", row.connectionId)
    }

    private fun headers(headers: List<Pair<String, List<String>>>): JsonArray = buildJsonArray {
        headers.forEach { (name, values) ->
            values.forEach { v -> add(buildJsonObject { put("name", name); put("value", v) }) }
        }
    }

    private fun queryString(url: String): JsonArray = buildJsonArray {
        val query = url.substringAfter('?', "").takeIf { it.isNotEmpty() } ?: return@buildJsonArray
        query.split('&').forEach { part ->
            if (part.isEmpty()) return@forEach
            val name = part.substringBefore('=')
            val value = part.substringAfter('=', "")
            add(buildJsonObject { put("name", name); put("value", value) })
        }
    }

    private fun postData(bytes: ByteArray?, headers: List<Pair<String, List<String>>>): JsonElement? {
        if (bytes == null || bytes.isEmpty()) return null
        val mime = contentType(headers).ifEmpty { "application/octet-stream" }
        val decoded = runCatching { decodeBody(bytes, headers, showFull = true) }.getOrNull()
        return buildJsonObject {
            put("mimeType", mime)
            if (decoded != null && !decoded.isBinary) {
                put("text", decoded.text)
            } else {
                put("text", Base64.getEncoder().encodeToString(bytes))
                put("comment", "base64-encoded binary body")
            }
        }
    }

    private fun content(bytes: ByteArray?, headers: List<Pair<String, List<String>>>): JsonElement = buildJsonObject {
        val mime = contentType(headers).ifEmpty { "application/octet-stream" }
        put("mimeType", mime)
        if (bytes == null || bytes.isEmpty()) {
            put("size", 0)
            return@buildJsonObject
        }
        put("size", bytes.size)
        val decoded = runCatching { decodeBody(bytes, headers, showFull = true) }.getOrNull()
        if (decoded != null && !decoded.isBinary) {
            put("text", decoded.text)
        } else {
            put("text", Base64.getEncoder().encodeToString(bytes))
            put("encoding", "base64")
        }
    }

    private fun contentType(headers: List<Pair<String, List<String>>>): String =
        headers.firstOrNull { it.first.equals("content-type", true) }?.second?.firstOrNull().orEmpty()
}
