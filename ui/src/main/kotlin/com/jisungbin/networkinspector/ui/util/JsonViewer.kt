package com.jisungbin.networkinspector.ui.util

import androidx.compose.foundation.ContextMenuArea
import androidx.compose.foundation.ContextMenuItem
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.withStyle
import java.util.IdentityHashMap
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

private enum class LineKind {
    ObjectOpenExpanded,
    ObjectClose,
    ObjectCollapsed,
    ObjectEmpty,
    ArrayOpenExpanded,
    ArrayClose,
    ArrayCollapsed,
    ArrayEmpty,
    Primitive,
}

private data class JsonLine(
    val kind: LineKind,
    val indent: Int,
    val nodeId: Int,
    val parentId: Int,
    val indexInParent: Int,
    val key: String?,
    val element: JsonElement?,
    val trailingComma: Boolean,
    val matchOffset: Int,
    val matchCount: Int,
)

@Stable
class JsonViewerState internal constructor(
    val json: String,
    internal val parsed: JsonElement?,
    internal val nodeIds: Map<JsonElement, Int>,
    internal val toggleableIds: Set<Int>,
    internal val defaultExpandedDepth: Int,
) {
    internal val expandedOverrides: SnapshotStateMap<Int, Boolean> = mutableStateMapOf()

    var totalMatches: Int by mutableIntStateOf(0)
        internal set

    fun expandAll() {
        for (id in toggleableIds) expandedOverrides[id] = true
    }

    fun collapseAll() {
        for (id in toggleableIds) expandedOverrides[id] = false
    }
}

@Composable
fun rememberJsonViewerState(json: String, defaultExpandedDepth: Int = 2): JsonViewerState {
    return remember(json, defaultExpandedDepth) {
        val parsed = runCatching { Json.parseToJsonElement(json) }.getOrNull()
        val nodeIds = IdentityHashMap<JsonElement, Int>()
        val toggleableIds = LinkedHashSet<Int>()
        if (parsed != null) {
            var next = 0
            fun assign(e: JsonElement) {
                when (e) {
                    is JsonObject -> {
                        val id = next++
                        nodeIds[e] = id
                        if (e.isNotEmpty()) toggleableIds.add(id)
                        e.forEach { (_, v) -> assign(v) }
                    }
                    is JsonArray -> {
                        val id = next++
                        nodeIds[e] = id
                        if (e.isNotEmpty()) toggleableIds.add(id)
                        e.forEach { assign(it) }
                    }
                    else -> Unit
                }
            }
            assign(parsed)
        }
        JsonViewerState(json, parsed, nodeIds, toggleableIds, defaultExpandedDepth)
    }
}

@Composable
fun JsonViewer(
    state: JsonViewerState,
    search: String,
    currentMatchIndex: Int = -1,
    modifier: Modifier = Modifier,
) {
    val parsed = state.parsed
    if (parsed == null) {
        Text(
            text = AnnotatedString(state.json),
            fontFamily = FontFamily.Monospace,
            style = MaterialTheme.typography.bodySmall,
            modifier = modifier,
        )
        return
    }

    val skeleton by remember(state) {
        derivedStateOf {
            flattenJson(parsed, state.expandedOverrides, state.defaultExpandedDepth, state.nodeIds)
        }
    }
    val lines = remember(skeleton, search) { annotateMatches(skeleton, search) }
    val totalLineMatches = remember(lines) {
        lines.lastOrNull()?.let { it.matchOffset + it.matchCount } ?: 0
    }

    LaunchedEffect(totalLineMatches) {
        state.totalMatches = totalLineMatches
    }

    val listState = rememberLazyListState()
    LaunchedEffect(currentMatchIndex, lines) {
        if (currentMatchIndex < 0) return@LaunchedEffect
        val targetIndex = lines.indexOfFirst {
            it.matchCount > 0 && currentMatchIndex in it.matchOffset until (it.matchOffset + it.matchCount)
        }
        if (targetIndex >= 0) {
            runCatching { listState.animateScrollToItem(targetIndex) }
        }
    }

    LazyColumn(state = listState, modifier = modifier) {
        items(items = lines, key = ::lineKey) { line ->
            JsonLineRow(
                line = line,
                search = search,
                currentMatchIndex = currentMatchIndex,
                onToggle = { id, currentlyExpanded ->
                    state.expandedOverrides[id] = !currentlyExpanded
                },
            )
        }
    }
}

private fun lineKey(line: JsonLine): Any = when (line.kind) {
    LineKind.ObjectOpenExpanded -> "OO:${line.nodeId}"
    LineKind.ObjectClose -> "OC:${line.nodeId}"
    LineKind.ObjectCollapsed -> "OS:${line.nodeId}"
    LineKind.ObjectEmpty -> "OE:${line.nodeId}"
    LineKind.ArrayOpenExpanded -> "AO:${line.nodeId}"
    LineKind.ArrayClose -> "AC:${line.nodeId}"
    LineKind.ArrayCollapsed -> "AS:${line.nodeId}"
    LineKind.ArrayEmpty -> "AE:${line.nodeId}"
    LineKind.Primitive -> "P:${line.parentId}:${line.indexInParent}"
}

private fun flattenJson(
    root: JsonElement,
    expandedOverrides: Map<Int, Boolean>,
    defaultExpandedDepth: Int,
    nodeIds: Map<JsonElement, Int>,
): List<JsonLine> {
    val out = ArrayList<JsonLine>()

    fun walk(
        element: JsonElement,
        indent: Int,
        key: String?,
        trailingComma: Boolean,
        parentId: Int,
        indexInParent: Int,
    ) {
        when (element) {
            is JsonObject -> {
                val id = nodeIds[element] ?: -1
                if (element.isEmpty()) {
                    out.add(line(LineKind.ObjectEmpty, indent, id, parentId, indexInParent, key, element, trailingComma))
                    return
                }
                val isExpanded = expandedOverrides[id] ?: (indent < defaultExpandedDepth)
                if (!isExpanded) {
                    out.add(line(LineKind.ObjectCollapsed, indent, id, parentId, indexInParent, key, element, trailingComma))
                    return
                }
                out.add(line(LineKind.ObjectOpenExpanded, indent, id, parentId, indexInParent, key, element, trailingComma = false))
                val entries = element.entries.toList()
                entries.forEachIndexed { i, (k, v) ->
                    walk(v, indent + 1, k, i != entries.lastIndex, parentId = id, indexInParent = i)
                }
                out.add(line(LineKind.ObjectClose, indent, id, parentId, indexInParent, null, element, trailingComma))
            }
            is JsonArray -> {
                val id = nodeIds[element] ?: -1
                if (element.isEmpty()) {
                    out.add(line(LineKind.ArrayEmpty, indent, id, parentId, indexInParent, key, element, trailingComma))
                    return
                }
                val isExpanded = expandedOverrides[id] ?: (indent < defaultExpandedDepth)
                if (!isExpanded) {
                    out.add(line(LineKind.ArrayCollapsed, indent, id, parentId, indexInParent, key, element, trailingComma))
                    return
                }
                out.add(line(LineKind.ArrayOpenExpanded, indent, id, parentId, indexInParent, key, element, trailingComma = false))
                element.forEachIndexed { i, v ->
                    walk(v, indent + 1, null, i != element.lastIndex, parentId = id, indexInParent = i)
                }
                out.add(line(LineKind.ArrayClose, indent, id, parentId, indexInParent, null, element, trailingComma))
            }
            else -> {
                out.add(line(LineKind.Primitive, indent, -1, parentId, indexInParent, key, element, trailingComma))
            }
        }
    }

    walk(root, indent = 0, key = null, trailingComma = false, parentId = -1, indexInParent = 0)
    return out
}

private fun line(
    kind: LineKind,
    indent: Int,
    nodeId: Int,
    parentId: Int,
    indexInParent: Int,
    key: String?,
    element: JsonElement?,
    trailingComma: Boolean,
): JsonLine = JsonLine(
    kind = kind,
    indent = indent,
    nodeId = nodeId,
    parentId = parentId,
    indexInParent = indexInParent,
    key = key,
    element = element,
    trailingComma = trailingComma,
    matchOffset = 0,
    matchCount = 0,
)

private fun annotateMatches(skeleton: List<JsonLine>, search: String): List<JsonLine> {
    if (search.isBlank()) return skeleton
    var offset = 0
    return skeleton.map { l ->
        val count = countLineMatches(l, search)
        val annotated = l.copy(matchOffset = offset, matchCount = count)
        offset += count
        annotated
    }
}

private fun countLineMatches(line: JsonLine, search: String): Int {
    var count = 0
    if (line.key != null) count += countOccurrences("\"${line.key}\"", search)
    if (line.kind == LineKind.Primitive) {
        val rendered = renderPrimitive(line.element)
        if (rendered != null) count += countOccurrences(rendered, search)
    }
    return count
}

private fun countOccurrences(haystack: String, needle: String): Int {
    var count = 0
    var i = 0
    while (true) {
        val idx = haystack.indexOf(needle, i, ignoreCase = true)
        if (idx < 0) return count
        count++
        i = idx + needle.length
    }
}

private fun renderPrimitive(element: JsonElement?): String? = when (element) {
    null, JsonNull -> null
    is JsonPrimitive -> if (element.isString) "\"${escape(element.content)}\"" else element.content
    else -> null
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
private fun JsonLineRow(
    line: JsonLine,
    search: String,
    currentMatchIndex: Int,
    onToggle: (Int, Boolean) -> Unit,
) {
    val text = remember(line, search, currentMatchIndex) {
        buildLineText(line, search, currentMatchIndex)
    }
    val toggleMod = when (line.kind) {
        LineKind.ObjectOpenExpanded, LineKind.ArrayOpenExpanded ->
            Modifier.clickable { onToggle(line.nodeId, true) }
        LineKind.ObjectCollapsed, LineKind.ArrayCollapsed ->
            Modifier.clickable { onToggle(line.nodeId, false) }
        else -> Modifier
    }

    val container = line.element
    val menuTarget = if (line.indent != 0 && (container is JsonObject || container is JsonArray)) container else null

    if (menuTarget != null) {
        JsonLineContextMenu(menuTarget) {
            Text(
                text = text,
                fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier
                    .fillMaxWidth()
                    .then(toggleMod),
            )
        }
    } else {
        Text(
            text = text,
            fontFamily = FontFamily.Monospace,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier
                .fillMaxWidth()
                .then(toggleMod),
        )
    }
}

@Composable
private fun JsonLineContextMenu(element: JsonElement, content: @Composable () -> Unit) {
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
                    val text = Json.encodeToString(JsonElement.serializer(), element)
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

private fun buildLineText(
    line: JsonLine,
    search: String,
    currentMatchIndex: Int,
): AnnotatedString = buildAnnotatedString {
    append("  ".repeat(line.indent))
    val cursor = intArrayOf(line.matchOffset)

    fun appendKeyTok() {
        if (line.key == null) return
        val keyText = "\"${line.key}\""
        if (search.isBlank()) {
            withStyle(SpanStyle(color = KeyColor)) { append(keyText) }
        } else {
            appendHighlighted(keyText, search, KeyColor, cursor, currentMatchIndex)
        }
    }

    when (line.kind) {
        LineKind.ObjectEmpty -> {
            appendKeyTok()
            if (line.key != null) append(": ")
            pushBracket("{}")
            if (line.trailingComma) append(",")
        }
        LineKind.ObjectCollapsed -> {
            appendKeyTok()
            if (line.key != null) append(": ")
            pushBracket("▸ { … ")
            withStyle(SpanStyle(color = NullColor)) {
                append("${(line.element as? JsonObject)?.size ?: 0} keys")
            }
            pushBracket(" }")
            if (line.trailingComma) append(",")
        }
        LineKind.ObjectOpenExpanded -> {
            appendKeyTok()
            if (line.key != null) append(": ")
            pushBracket("▾ {")
        }
        LineKind.ObjectClose -> {
            pushBracket("}")
            if (line.trailingComma) append(",")
        }
        LineKind.ArrayEmpty -> {
            appendKeyTok()
            if (line.key != null) append(": ")
            pushBracket("[]")
            if (line.trailingComma) append(",")
        }
        LineKind.ArrayCollapsed -> {
            appendKeyTok()
            if (line.key != null) append(": ")
            pushBracket("▸ [ … ")
            withStyle(SpanStyle(color = NullColor)) {
                append("${(line.element as? JsonArray)?.size ?: 0} items")
            }
            pushBracket(" ]")
            if (line.trailingComma) append(",")
        }
        LineKind.ArrayOpenExpanded -> {
            appendKeyTok()
            if (line.key != null) append(": ")
            pushBracket("▾ [")
        }
        LineKind.ArrayClose -> {
            pushBracket("]")
            if (line.trailingComma) append(",")
        }
        LineKind.Primitive -> {
            appendKeyTok()
            if (line.key != null) append(": ")
            appendPrimitiveValue(line.element, search, cursor, currentMatchIndex)
            if (line.trailingComma) append(",")
        }
    }
}

private fun AnnotatedString.Builder.pushBracket(text: String) {
    withStyle(SpanStyle(color = BracketColor)) { append(text) }
}

private fun AnnotatedString.Builder.appendPrimitiveValue(
    element: JsonElement?,
    search: String,
    cursor: IntArray,
    currentMatchIndex: Int,
) {
    when (element) {
        null, JsonNull -> withStyle(SpanStyle(color = NullColor)) { append("null") }
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
                appendHighlighted(rendered, search, color, cursor, currentMatchIndex)
            }
        }
        else -> append(element.toString())
    }
}

private fun AnnotatedString.Builder.appendHighlighted(
    text: String,
    query: String,
    base: Color,
    cursor: IntArray,
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
        val isCurrent = cursor[0] == currentMatchIndex
        val bg = if (isCurrent) CurrentMatchBg.copy(alpha = 0.7f) else HighlightBg.copy(alpha = 0.6f)
        val fg = if (isCurrent) Color.White else base
        withStyle(SpanStyle(color = fg, background = bg)) {
            append(text.substring(idx, idx + query.length))
        }
        cursor[0]++
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
