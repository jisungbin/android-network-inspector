package com.jisungbin.networkinspector.ui.util

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import java.util.zip.GZIPInputStream

data class DecodedBody(
    val text: String,
    val encoding: String,
    val isBinary: Boolean,
    val isJson: Boolean,
    val originalSize: Int,
)

private val jsonFormatter = Json { prettyPrint = true; isLenient = true }

fun decodeBody(
    bytes: ByteArray?,
    headers: List<Pair<String, List<String>>>,
    maxPreview: Int = 64 * 1024,
    showFull: Boolean = false,
): DecodedBody? {
    if (bytes == null || bytes.isEmpty()) return null
    val contentEncoding = headers
        .firstOrNull { it.first.equals("content-encoding", true) }
        ?.second?.firstOrNull()
        ?.lowercase()
    val contentType = headers
        .firstOrNull { it.first.equals("content-type", true) }
        ?.second?.firstOrNull()
        ?.lowercase()
        .orEmpty()

    val decoded = when (contentEncoding) {
        "gzip" -> runCatching { GZIPInputStream(bytes.inputStream()).use { it.readAllBytes() } }
            .getOrDefault(bytes)
        else -> bytes
    }

    val isBinary = looksBinary(decoded)
    val originalSize = decoded.size
    val sliced = if (!showFull && originalSize > maxPreview) decoded.copyOf(maxPreview) else decoded

    val raw = if (isBinary) "[binary ${originalSize} bytes]" else String(sliced, Charsets.UTF_8)
    val maybeJson = !isBinary && (contentType.contains("json") || raw.startsWithJsonShape())
    val text = if (maybeJson) {
        runCatching {
            jsonFormatter.encodeToString(
                JsonElement.serializer(),
                jsonFormatter.parseToJsonElement(raw),
            )
        }.getOrDefault(raw)
    } else raw

    return DecodedBody(
        text = text,
        encoding = contentEncoding ?: "identity",
        isBinary = isBinary,
        isJson = maybeJson,
        originalSize = originalSize,
    )
}

private fun String.startsWithJsonShape(): Boolean {
    val trimmed = this.trimStart()
    return trimmed.startsWith("{") || trimmed.startsWith("[")
}

private fun looksBinary(bytes: ByteArray): Boolean {
    val sample = bytes.take(512)
    var nonText = 0
    for (b in sample) {
        val u = b.toInt() and 0xFF
        if (u == 0) return true
        if (u < 0x09 || (u in 0x0E..0x1F && u != 0x1B)) nonText++
    }
    return nonText > sample.size / 8
}
