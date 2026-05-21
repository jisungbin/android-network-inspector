package com.jisungbin.networkinspector.ui.util

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

private data class TreeStats(
    val objects: Int,
    val arrays: Int,
    val primitives: Int,
    val keyedValues: Int,
    val maxDepth: Int,
    val estimatedTextNodes: Int,
)

private fun analyze(root: JsonElement): TreeStats {
    var objects = 0
    var arrays = 0
    var primitives = 0
    var keyed = 0
    var maxDepth = 0
    var text = 0

    fun walk(e: JsonElement, depth: Int, asKeyedValue: Boolean) {
        if (depth > maxDepth) maxDepth = depth
        when (e) {
            is JsonObject -> {
                objects++
                text += 1
                if (e.isNotEmpty()) text += 1
                e.forEach { (_, v) ->
                    keyed++
                    when (v) {
                        is JsonObject, is JsonArray -> walk(v, depth + 1, asKeyedValue = true)
                        else -> {
                            text += 1
                            walk(v, depth + 1, asKeyedValue = false)
                        }
                    }
                }
            }
            is JsonArray -> {
                arrays++
                text += 1
                if (e.isNotEmpty()) text += 1
                e.forEach { v ->
                    walk(v, depth + 1, asKeyedValue = false)
                }
            }
            is JsonPrimitive, JsonNull -> {
                primitives++
                if (!asKeyedValue) text += 1
            }
        }
    }
    walk(root, 0, asKeyedValue = false)
    return TreeStats(objects, arrays, primitives, keyed, maxDepth, text)
}

fun main() {
    println("== JsonViewer tree stats ==")
    val scenarios = listOf(
        "tiny" to 50,
        "small" to 500,
        "medium" to 5_000,
        "large" to 50_000,
        "xlarge" to 200_000,
    )
    println(
        "%-8s %-12s %-10s %-10s %-12s %-10s %-12s %-14s".format(
            "name", "json-size", "objects", "arrays", "primitives", "depth", "totalNodes", "estTextNodes"
        )
    )
    println("-".repeat(96))
    for ((name, n) in scenarios) {
        val branching = if (n <= 500) 4 else 6
        val json = generateJson(n, branching)
        val s = analyze(Json.parseToJsonElement(json))
        val sizeKb = if (json.length < 1024) "${json.length} B" else "%.1f KB".format(json.length / 1024.0)
        println(
            "%-8s %-12s %-10d %-10d %-12d %-10d %-12d %-14d".format(
                name, sizeKb, s.objects, s.arrays, s.primitives, s.maxDepth,
                s.objects + s.arrays + s.primitives, s.estimatedTextNodes
            )
        )
    }
}
