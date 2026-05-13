package com.jisungbin.networkinspector.ui.util

import java.util.zip.GZIPInputStream

data class DecodedBody(
    val text: String,
    val encoding: String,
    val isBinary: Boolean,
)

fun decodeBody(bytes: ByteArray?, headers: List<Pair<String, List<String>>>): DecodedBody? {
    if (bytes == null || bytes.isEmpty()) return null
    val contentEncoding = headers
        .firstOrNull { it.first.equals("content-encoding", true) }
        ?.second?.firstOrNull()
        ?.lowercase()

    val decoded = when (contentEncoding) {
        "gzip" -> runCatching {
            GZIPInputStream(bytes.inputStream()).use { it.readAllBytes() }
        }.getOrDefault(bytes)
        else -> bytes
    }

    val isBinary = looksBinary(decoded)
    val text = if (isBinary) {
        "[binary ${decoded.size} bytes]"
    } else {
        String(decoded, Charsets.UTF_8)
    }
    return DecodedBody(text = text, encoding = contentEncoding ?: "identity", isBinary = isBinary)
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
