package com.jisungbin.networkinspector.ui.util

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

private val KeyColor = Color(0xFF1565C0)
private val StringColor = Color(0xFF2E7D32)
private val NumberColor = Color(0xFFD84315)
private val BoolColor = Color(0xFF6A1B9A)
private val NullColor = Color(0xFF616161)
private val BracketColor = Color(0xFF455A64)
private val HighlightBg = Color(0xFFFFEB3B)

@Composable
fun JsonViewer(json: String, search: String, modifier: Modifier = Modifier) {
    val element = remember(json) { runCatching { Json.parseToJsonElement(json) }.getOrNull() }
    if (element == null) {
        Text(
            text = AnnotatedString(json),
            fontFamily = FontFamily.Monospace,
            style = MaterialTheme.typography.bodySmall,
            modifier = modifier,
        )
        return
    }
    Column(modifier = modifier) {
        JsonNode(element, indent = 0, trailingComma = false, search = search, defaultExpandedDepth = 2)
    }
}

@Composable
private fun JsonNode(
    element: JsonElement,
    indent: Int,
    trailingComma: Boolean,
    search: String,
    defaultExpandedDepth: Int,
) {
    when (element) {
        is JsonObject -> JsonObjectView(element, indent, trailingComma, search, defaultExpandedDepth)
        is JsonArray -> JsonArrayView(element, indent, trailingComma, search, defaultExpandedDepth)
        is JsonPrimitive -> JsonPrimitiveView(element, indent, trailingComma, search)
        JsonNull -> JsonPrimitiveView(JsonNull, indent, trailingComma, search)
    }
}

@Composable
private fun JsonObjectView(
    obj: JsonObject,
    indent: Int,
    trailingComma: Boolean,
    search: String,
    defaultExpandedDepth: Int,
) {
    var expanded by remember(obj) { mutableStateOf(indent < defaultExpandedDepth) }
    val pad = "  ".repeat(indent)

    if (obj.isEmpty()) {
        Text(
            text = buildAnnotatedString {
                append(pad)
                pushBracket("{}")
                if (trailingComma) append(",")
            },
            fontFamily = FontFamily.Monospace,
            style = MaterialTheme.typography.bodySmall,
        )
        return
    }

    val toggleModifier = Modifier.clickable { expanded = !expanded }
    if (!expanded) {
        Text(
            text = buildAnnotatedString {
                append(pad)
                pushBracket("▸ { … ")
                withStyle(SpanStyle(color = NullColor)) { append("${obj.size} keys") }
                pushBracket(" }")
                if (trailingComma) append(",")
            },
            fontFamily = FontFamily.Monospace,
            style = MaterialTheme.typography.bodySmall,
            modifier = toggleModifier,
        )
        return
    }

    Text(
        text = buildAnnotatedString {
            append(pad)
            pushBracket("▾ {")
        },
        fontFamily = FontFamily.Monospace,
        style = MaterialTheme.typography.bodySmall,
        modifier = toggleModifier,
    )
    val entries = obj.entries.toList()
    entries.forEachIndexed { i, (key, value) ->
        val last = i == entries.lastIndex
        JsonKeyedValue(key, value, indent + 1, !last, search, defaultExpandedDepth)
    }
    Text(
        text = buildAnnotatedString {
            append(pad)
            pushBracket("}")
            if (trailingComma) append(",")
        },
        fontFamily = FontFamily.Monospace,
        style = MaterialTheme.typography.bodySmall,
    )
}

@Composable
private fun JsonArrayView(
    arr: JsonArray,
    indent: Int,
    trailingComma: Boolean,
    search: String,
    defaultExpandedDepth: Int,
) {
    var expanded by remember(arr) { mutableStateOf(indent < defaultExpandedDepth) }
    val pad = "  ".repeat(indent)

    if (arr.isEmpty()) {
        Text(
            text = buildAnnotatedString {
                append(pad)
                pushBracket("[]")
                if (trailingComma) append(",")
            },
            fontFamily = FontFamily.Monospace,
            style = MaterialTheme.typography.bodySmall,
        )
        return
    }

    val toggleModifier = Modifier.clickable { expanded = !expanded }
    if (!expanded) {
        Text(
            text = buildAnnotatedString {
                append(pad)
                pushBracket("▸ [ … ")
                withStyle(SpanStyle(color = NullColor)) { append("${arr.size} items") }
                pushBracket(" ]")
                if (trailingComma) append(",")
            },
            fontFamily = FontFamily.Monospace,
            style = MaterialTheme.typography.bodySmall,
            modifier = toggleModifier,
        )
        return
    }

    Text(
        text = buildAnnotatedString {
            append(pad)
            pushBracket("▾ [")
        },
        fontFamily = FontFamily.Monospace,
        style = MaterialTheme.typography.bodySmall,
        modifier = toggleModifier,
    )
    arr.forEachIndexed { i, value ->
        val last = i == arr.lastIndex
        JsonNode(value, indent + 1, !last, search, defaultExpandedDepth)
    }
    Text(
        text = buildAnnotatedString {
            append(pad)
            pushBracket("]")
            if (trailingComma) append(",")
        },
        fontFamily = FontFamily.Monospace,
        style = MaterialTheme.typography.bodySmall,
    )
}

@Composable
private fun JsonKeyedValue(
    key: String,
    value: JsonElement,
    indent: Int,
    trailingComma: Boolean,
    search: String,
    defaultExpandedDepth: Int,
) {
    when (value) {
        is JsonObject, is JsonArray -> {
            val pad = "  ".repeat(indent)
            val isObj = value is JsonObject
            val empty = (value is JsonObject && value.isEmpty()) || (value is JsonArray && value.isEmpty())
            var expanded by remember(value) { mutableStateOf(indent < defaultExpandedDepth && !empty) }
            val toggleModifier = if (empty) Modifier else Modifier.clickable { expanded = !expanded }

            val open = if (isObj) "{" else "["
            val close = if (isObj) "}" else "]"
            val size = if (value is JsonObject) value.size else (value as JsonArray).size
            val unit = if (isObj) "keys" else "items"

            if (empty || !expanded) {
                Text(
                    text = buildAnnotatedString {
                        append(pad)
                        appendKey(key, search)
                        append(": ")
                        if (empty) {
                            pushBracket("$open$close")
                        } else {
                            pushBracket("▸ $open … ")
                            withStyle(SpanStyle(color = NullColor)) { append("$size $unit") }
                            pushBracket(" $close")
                        }
                        if (trailingComma) append(",")
                    },
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = toggleModifier,
                )
                return
            }

            Text(
                text = buildAnnotatedString {
                    append(pad)
                    appendKey(key, search)
                    append(": ")
                    pushBracket("▾ $open")
                },
                fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.bodySmall,
                modifier = toggleModifier,
            )
            when (value) {
                is JsonObject -> {
                    val entries = value.entries.toList()
                    entries.forEachIndexed { i, (k, v) ->
                        JsonKeyedValue(k, v, indent + 1, i != entries.lastIndex, search, defaultExpandedDepth)
                    }
                }
                is JsonArray -> {
                    value.forEachIndexed { i, v ->
                        JsonNode(v, indent + 1, i != value.lastIndex, search, defaultExpandedDepth)
                    }
                }
                else -> Unit
            }
            Text(
                text = buildAnnotatedString {
                    append(pad)
                    pushBracket(close)
                    if (trailingComma) append(",")
                },
                fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.bodySmall,
            )
        }
        else -> {
            val pad = "  ".repeat(indent)
            Text(
                text = buildAnnotatedString {
                    append(pad)
                    appendKey(key, search)
                    append(": ")
                    appendPrimitive(value, search)
                    if (trailingComma) append(",")
                },
                fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun JsonPrimitiveView(value: JsonElement, indent: Int, trailingComma: Boolean, search: String) {
    val pad = "  ".repeat(indent)
    Text(
        text = buildAnnotatedString {
            append(pad)
            appendPrimitive(value, search)
            if (trailingComma) append(",")
        },
        fontFamily = FontFamily.Monospace,
        style = MaterialTheme.typography.bodySmall,
    )
}

private fun androidx.compose.ui.text.AnnotatedString.Builder.pushBracket(text: String) {
    withStyle(SpanStyle(color = BracketColor)) { append(text) }
}

private fun androidx.compose.ui.text.AnnotatedString.Builder.appendKey(key: String, search: String) {
    val keyText = "\"$key\""
    if (search.isBlank()) {
        withStyle(SpanStyle(color = KeyColor)) { append(keyText) }
        return
    }
    appendWithHighlight(keyText, search, KeyColor)
}

private fun androidx.compose.ui.text.AnnotatedString.Builder.appendPrimitive(element: JsonElement, search: String) {
    when (element) {
        JsonNull -> withStyle(SpanStyle(color = NullColor)) { append("null") }
        is JsonPrimitive -> {
            val color = when {
                element.isString -> StringColor
                element.booleanOrNullPrim() != null -> BoolColor
                element.content.toDoubleOrNull() != null -> NumberColor
                else -> NumberColor
            }
            val rendered = if (element.isString) "\"${escape(element.content)}\"" else element.content
            if (search.isBlank()) {
                withStyle(SpanStyle(color = color)) { append(rendered) }
            } else {
                appendWithHighlight(rendered, search, color)
            }
        }
        else -> append(element.toString())
    }
}

private fun androidx.compose.ui.text.AnnotatedString.Builder.appendWithHighlight(
    text: String,
    query: String,
    base: Color,
) {
    var i = 0
    while (i < text.length) {
        val idx = text.indexOf(query, i, ignoreCase = true)
        if (idx < 0) {
            withStyle(SpanStyle(color = base)) { append(text.substring(i)) }
            break
        }
        if (idx > i) withStyle(SpanStyle(color = base)) { append(text.substring(i, idx)) }
        withStyle(SpanStyle(color = base, background = HighlightBg.copy(alpha = 0.6f))) {
            append(text.substring(idx, idx + query.length))
        }
        i = idx + query.length
    }
}

private fun JsonPrimitive.booleanOrNullPrim(): Boolean? =
    if (this.isString) null else when (content) {
        "true" -> true
        "false" -> false
        else -> null
    }

private fun escape(s: String): String = s.replace("\\", "\\\\").replace("\"", "\\\"")

@Composable
fun JsonViewerHeader(onExpandAll: () -> Unit, onCollapseAll: () -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 4.dp)) {
        Text("[ Expand all ]", modifier = Modifier.clickable { onExpandAll() })
        Text("[ Collapse all ]", modifier = Modifier.clickable { onCollapseAll() })
    }
}

fun computeJsonString(text: String): AnnotatedString = AnnotatedString(text)
