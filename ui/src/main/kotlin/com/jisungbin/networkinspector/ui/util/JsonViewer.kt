package com.jisungbin.networkinspector.ui.util

import androidx.compose.foundation.ContextMenuArea
import androidx.compose.foundation.ContextMenuItem
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

@OptIn(ExperimentalSerializationApi::class)
private val PrettyJson = Json { prettyPrint = true; prettyPrintIndent = "  " }
private val CompactJson = Json { }

val LocalSnackbarHostState = staticCompositionLocalOf<SnackbarHostState> {
    error("LocalSnackbarHostState not provided")
}

private val KeyColor = Color(0xFF1565C0)
private val StringColor = Color(0xFF2E7D32)
private val NumberColor = Color(0xFFD84315)
private val BoolColor = Color(0xFF6A1B9A)
private val NullColor = Color(0xFF616161)
private val BracketColor = Color(0xFF455A64)
private val HighlightBg = Color(0xFFFFEB3B)
private val CurrentMatchBg = Color(0xFFFF6F00)

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun JsonViewer(
    json: String,
    search: String,
    currentMatchIndex: Int = -1,
    defaultExpandedDepth: Int = 2,
    modifier: Modifier = Modifier,
) {
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
    val counter = remember(json, search, currentMatchIndex) { intArrayOf(0) }
    counter[0] = 0
    val requester = remember { BringIntoViewRequester() }
    LaunchedEffect(currentMatchIndex, json, search) {
        if (currentMatchIndex >= 0) {
            delay(50)
            runCatching { requester.bringIntoView() }
        }
    }
    Column(modifier = modifier) {
        JsonNode(
            element,
            indent = 0,
            trailingComma = false,
            search = search,
            defaultExpandedDepth = defaultExpandedDepth,
            counter = counter,
            currentMatchIndex = currentMatchIndex,
            requester = requester,
        )
    }
}

fun countJsonMatches(text: String, query: String): Int {
    if (query.isBlank()) return 0
    var count = 0
    var i = 0
    while (true) {
        val idx = text.indexOf(query, i, ignoreCase = true)
        if (idx < 0) return count
        count++
        i = idx + query.length
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun JsonNode(
    element: JsonElement,
    indent: Int,
    trailingComma: Boolean,
    search: String,
    defaultExpandedDepth: Int,
    counter: IntArray,
    currentMatchIndex: Int,
    requester: BringIntoViewRequester,
) {
    when (element) {
        is JsonObject -> JsonObjectView(element, indent, trailingComma, search, defaultExpandedDepth, counter, currentMatchIndex, requester)
        is JsonArray -> JsonArrayView(element, indent, trailingComma, search, defaultExpandedDepth, counter, currentMatchIndex, requester)
        is JsonPrimitive -> JsonPrimitiveView(element, indent, trailingComma, search, counter, currentMatchIndex, requester)
        JsonNull -> JsonPrimitiveView(JsonNull, indent, trailingComma, search, counter, currentMatchIndex, requester)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun JsonObjectView(
    obj: JsonObject,
    indent: Int,
    trailingComma: Boolean,
    search: String,
    defaultExpandedDepth: Int,
    counter: IntArray,
    currentMatchIndex: Int,
    requester: BringIntoViewRequester,
) {
    var expanded by remember(obj) { mutableStateOf(indent < defaultExpandedDepth) }
    val pad = "  ".repeat(indent)
    val menuEnabled = indent != 0

    if (obj.isEmpty()) {
        JsonNodeContextMenu(obj, menuEnabled) {
            MatchAwareText(counter, currentMatchIndex, requester) {
                append(pad)
                pushBracket("{}")
                if (trailingComma) append(",")
            }
        }
        return
    }

    val toggleModifier = Modifier.clickable { expanded = !expanded }
    if (!expanded) {
        JsonNodeContextMenu(obj, menuEnabled) {
            MatchAwareText(counter, currentMatchIndex, requester, toggleModifier) {
                append(pad)
                pushBracket("▸ { … ")
                withStyle(SpanStyle(color = NullColor)) { append("${obj.size} keys") }
                pushBracket(" }")
                if (trailingComma) append(",")
            }
        }
        return
    }

    JsonNodeContextMenu(obj, menuEnabled) {
        MatchAwareText(counter, currentMatchIndex, requester, toggleModifier) {
            append(pad)
            pushBracket("▾ {")
        }
    }
    val entries = obj.entries.toList()
    entries.forEachIndexed { i, (key, value) ->
        val last = i == entries.lastIndex
        JsonKeyedValue(key, value, indent + 1, !last, search, defaultExpandedDepth, counter, currentMatchIndex, requester)
    }
    JsonNodeContextMenu(obj, menuEnabled) {
        MatchAwareText(counter, currentMatchIndex, requester) {
            append(pad)
            pushBracket("}")
            if (trailingComma) append(",")
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun JsonArrayView(
    arr: JsonArray,
    indent: Int,
    trailingComma: Boolean,
    search: String,
    defaultExpandedDepth: Int,
    counter: IntArray,
    currentMatchIndex: Int,
    requester: BringIntoViewRequester,
) {
    var expanded by remember(arr) { mutableStateOf(indent < defaultExpandedDepth) }
    val pad = "  ".repeat(indent)
    val menuEnabled = indent != 0

    if (arr.isEmpty()) {
        JsonNodeContextMenu(arr, menuEnabled) {
            MatchAwareText(counter, currentMatchIndex, requester) {
                append(pad)
                pushBracket("[]")
                if (trailingComma) append(",")
            }
        }
        return
    }

    val toggleModifier = Modifier.clickable { expanded = !expanded }
    if (!expanded) {
        JsonNodeContextMenu(arr, menuEnabled) {
            MatchAwareText(counter, currentMatchIndex, requester, toggleModifier) {
                append(pad)
                pushBracket("▸ [ … ")
                withStyle(SpanStyle(color = NullColor)) { append("${arr.size} items") }
                pushBracket(" ]")
                if (trailingComma) append(",")
            }
        }
        return
    }

    JsonNodeContextMenu(arr, menuEnabled) {
        MatchAwareText(counter, currentMatchIndex, requester, toggleModifier) {
            append(pad)
            pushBracket("▾ [")
        }
    }
    arr.forEachIndexed { i, value ->
        val last = i == arr.lastIndex
        JsonNode(value, indent + 1, !last, search, defaultExpandedDepth, counter, currentMatchIndex, requester)
    }
    JsonNodeContextMenu(arr, menuEnabled) {
        MatchAwareText(counter, currentMatchIndex, requester) {
            append(pad)
            pushBracket("]")
            if (trailingComma) append(",")
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun JsonKeyedValue(
    key: String,
    value: JsonElement,
    indent: Int,
    trailingComma: Boolean,
    search: String,
    defaultExpandedDepth: Int,
    counter: IntArray,
    currentMatchIndex: Int,
    requester: BringIntoViewRequester,
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
                JsonNodeContextMenu(value, enabled = true) {
                    MatchAwareText(counter, currentMatchIndex, requester, toggleModifier) {
                        append(pad)
                        appendKey(key, search, counter, currentMatchIndex)
                        append(": ")
                        if (empty) {
                            pushBracket("$open$close")
                        } else {
                            pushBracket("▸ $open … ")
                            withStyle(SpanStyle(color = NullColor)) { append("$size $unit") }
                            pushBracket(" $close")
                        }
                        if (trailingComma) append(",")
                    }
                }
                return
            }

            JsonNodeContextMenu(value, enabled = true) {
                MatchAwareText(counter, currentMatchIndex, requester, toggleModifier) {
                    append(pad)
                    appendKey(key, search, counter, currentMatchIndex)
                    append(": ")
                    pushBracket("▾ $open")
                }
            }
            when (value) {
                is JsonObject -> {
                    val entries = value.entries.toList()
                    entries.forEachIndexed { i, (k, v) ->
                        JsonKeyedValue(k, v, indent + 1, i != entries.lastIndex, search, defaultExpandedDepth, counter, currentMatchIndex, requester)
                    }
                }
                is JsonArray -> {
                    value.forEachIndexed { i, v ->
                        JsonNode(v, indent + 1, i != value.lastIndex, search, defaultExpandedDepth, counter, currentMatchIndex, requester)
                    }
                }
                else -> Unit
            }
            JsonNodeContextMenu(value, enabled = true) {
                MatchAwareText(counter, currentMatchIndex, requester) {
                    append(pad)
                    pushBracket(close)
                    if (trailingComma) append(",")
                }
            }
        }
        else -> {
            val pad = "  ".repeat(indent)
            MatchAwareText(counter, currentMatchIndex, requester) {
                append(pad)
                appendKey(key, search, counter, currentMatchIndex)
                append(": ")
                appendPrimitive(value, search, counter, currentMatchIndex)
                if (trailingComma) append(",")
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun JsonPrimitiveView(
    value: JsonElement,
    indent: Int,
    trailingComma: Boolean,
    search: String,
    counter: IntArray,
    currentMatchIndex: Int,
    requester: BringIntoViewRequester,
) {
    val pad = "  ".repeat(indent)
    MatchAwareText(counter, currentMatchIndex, requester) {
        append(pad)
        appendPrimitive(value, search, counter, currentMatchIndex)
        if (trailingComma) append(",")
    }
}

@Composable
private fun JsonNodeContextMenu(
    element: JsonElement,
    enabled: Boolean,
    content: @Composable () -> Unit,
) {
    if (!enabled) {
        content()
        return
    }
    val clipboard = LocalClipboardManager.current
    val snackbar = LocalSnackbarHostState.current
    val scope = rememberCoroutineScope()
    val (prettyLabel, compactLabel) = when (element) {
        is JsonArray -> "Copy this array" to "Copy as compact JSON"
        else -> "Copy this object" to "Copy as compact JSON"
    }
    ContextMenuArea(
        items = {
            listOf(
                ContextMenuItem(prettyLabel) {
                    val text = PrettyJson.encodeToString(JsonElement.serializer(), element)
                    scope.launch {
                        clipboard.setText(AnnotatedString(text))
                        snackbar.showSnackbar("Copied JSON to clipboard")
                    }
                },
                ContextMenuItem(compactLabel) {
                    val text = CompactJson.encodeToString(JsonElement.serializer(), element)
                    scope.launch {
                        clipboard.setText(AnnotatedString(text))
                        snackbar.showSnackbar("Copied JSON to clipboard")
                    }
                },
            )
        },
        content = content,
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MatchAwareText(
    counter: IntArray,
    currentMatchIndex: Int,
    requester: BringIntoViewRequester,
    modifier: Modifier = Modifier,
    content: AnnotatedString.Builder.() -> Unit,
) {
    val start = counter[0]
    val text = buildAnnotatedString(content)
    val end = counter[0]
    val containsCurrent = currentMatchIndex in start until end
    val finalModifier = if (containsCurrent) modifier.bringIntoViewRequester(requester) else modifier
    Text(
        text = text,
        fontFamily = FontFamily.Monospace,
        style = MaterialTheme.typography.bodySmall,
        modifier = finalModifier,
    )
}

private fun AnnotatedString.Builder.pushBracket(text: String) {
    withStyle(SpanStyle(color = BracketColor)) { append(text) }
}

private fun AnnotatedString.Builder.appendKey(
    key: String,
    search: String,
    counter: IntArray,
    currentMatchIndex: Int,
) {
    val keyText = "\"$key\""
    if (search.isBlank()) {
        withStyle(SpanStyle(color = KeyColor)) { append(keyText) }
        return
    }
    appendWithHighlight(keyText, search, KeyColor, counter, currentMatchIndex)
}

private fun AnnotatedString.Builder.appendPrimitive(
    element: JsonElement,
    search: String,
    counter: IntArray,
    currentMatchIndex: Int,
) {
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
                appendWithHighlight(rendered, search, color, counter, currentMatchIndex)
            }
        }
        else -> append(element.toString())
    }
}

private fun AnnotatedString.Builder.appendWithHighlight(
    text: String,
    query: String,
    base: Color,
    counter: IntArray,
    currentMatchIndex: Int,
) {
    var i = 0
    while (i < text.length) {
        val idx = text.indexOf(query, i, ignoreCase = true)
        if (idx < 0) {
            withStyle(SpanStyle(color = base)) { append(text.substring(i)) }
            break
        }
        if (idx > i) withStyle(SpanStyle(color = base)) { append(text.substring(i, idx)) }
        val isCurrent = counter[0] == currentMatchIndex
        val bg = if (isCurrent) CurrentMatchBg.copy(alpha = 0.7f) else HighlightBg.copy(alpha = 0.6f)
        val fg = if (isCurrent) Color.White else base
        withStyle(SpanStyle(color = fg, background = bg)) {
            append(text.substring(idx, idx + query.length))
        }
        counter[0]++
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
