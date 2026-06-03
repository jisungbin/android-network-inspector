package com.jisungbin.networkinspector.mcp

import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/** Tiny JSON-Schema builders so tool definitions read as a flat property list. */

internal fun strProp(description: String): JsonObject = buildJsonObject {
    put("type", "string"); put("description", description)
}

internal fun intProp(description: String): JsonObject = buildJsonObject {
    put("type", "integer"); put("description", description)
}

internal fun boolProp(description: String): JsonObject = buildJsonObject {
    put("type", "boolean"); put("description", description)
}

internal fun objProp(description: String): JsonObject = buildJsonObject {
    put("type", "object"); put("description", description)
}

internal fun enumProp(description: String, values: List<String>): JsonObject = buildJsonObject {
    put("type", "string"); put("description", description)
    putJsonArray("enum") { values.forEach { add(it) } }
}

/** Builds a [ToolSchema] from a property list; [required] keys default to none. */
internal fun toolSchema(
    required: List<String> = emptyList(),
    vararg props: Pair<String, JsonObject>,
): ToolSchema = ToolSchema(
    properties = buildJsonObject { props.forEach { (k, v) -> put(k, v) } },
    required = required.ifEmpty { null },
)

/** The single optional argument shared by almost every tool. */
internal val serialProp: Pair<String, JsonObject>
    get() = "serial" to strProp("Target device serial. Optional; defaults to the active inspector tab or the only attached device.")

/** Opt-in to surface traffic from Settings-ignored hosts; shared by the read/export tools. */
internal val includeIgnoredHostsProp: Pair<String, JsonObject>
    get() = "includeIgnoredHosts" to boolProp(
        "Include requests from Settings-ignored hosts. Default false — they are hidden, matching the inspector view.",
    )
