package com.jisungbin.networkinspector.ui.util

import com.jisungbin.networkinspector.engine.NetworkRow

fun NetworkRow.toCurl(): String {
    val sb = StringBuilder("curl")
    if (method.isNotEmpty() && method != "GET") sb.append(" -X ").append(method)
    requestHeaders.forEach { (k, vs) ->
        vs.forEach { v -> sb.append(" -H '").append(escapeSingleQuotes("$k: $v")).append("'") }
    }
    val bodyText = requestBody?.let { runCatching { String(it, Charsets.UTF_8) }.getOrNull() }
    if (!bodyText.isNullOrEmpty()) {
        sb.append(" --data-raw '").append(escapeSingleQuotes(bodyText)).append("'")
    }
    sb.append(" '").append(escapeSingleQuotes(url)).append("'")
    return sb.toString()
}

private fun escapeSingleQuotes(s: String): String = s.replace("'", "'\\''")
