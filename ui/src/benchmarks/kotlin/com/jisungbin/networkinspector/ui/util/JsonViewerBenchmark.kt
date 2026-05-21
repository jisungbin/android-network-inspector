package com.jisungbin.networkinspector.ui.util

import kotlinx.benchmark.Benchmark
import kotlinx.benchmark.BenchmarkMode
import kotlinx.benchmark.Mode
import kotlinx.benchmark.OutputTimeUnit
import kotlinx.benchmark.Param
import kotlinx.benchmark.Scope
import kotlinx.benchmark.Setup
import kotlinx.benchmark.State
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import java.util.concurrent.TimeUnit
import kotlin.random.Random

@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
open class JsonViewerBenchmark {

    @Param("500", "5000", "50000", "200000")
    var nodes: Int = 0

    private lateinit var json: String
    private lateinit var parsedElement: JsonElement

    @Setup
    fun setup() {
        json = generateJson(nodes, branching = if (nodes <= 500) 4 else 6)
        parsedElement = Json.parseToJsonElement(json)
    }

    @Benchmark
    fun parseJson(): JsonElement = Json.parseToJsonElement(json)

    @Benchmark
    fun countMatchesShortQuery(): Int = countJsonMatches(json, "k_")

    @Benchmark
    fun countMatchesAbsentQuery(): Int = countJsonMatches(json, "__zzz__")

    @Benchmark
    fun countMatchesSingleChar(): Int = countJsonMatches(json, "a")
}

@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
open class JsonViewerSearchBenchmark {

    private lateinit var json: String

    @Setup
    fun setup() {
        json = generateJson(5_000, branching = 5)
    }

    @Benchmark
    fun countMatchesShortQuery(): Int = countJsonMatches(json, "k_")

    @Benchmark
    fun countMatchesLongQuery(): Int = countJsonMatches(json, "root_0")

    @Benchmark
    fun countMatchesEmptyQuery(): Int = countJsonMatches(json, "")
}

internal fun generateJson(targetNodes: Int, branching: Int, seed: Long = 42L): String {
    val rng = Random(seed)
    var produced = 0
    fun build(depth: Int): JsonElement {
        if (produced >= targetNodes || depth > 12) {
            produced++
            return JsonPrimitive(rng.nextInt())
        }
        return when (rng.nextInt(3)) {
            0 -> {
                produced++
                buildJsonObject {
                    repeat(branching) {
                        put("k_${rng.nextInt(100000).toString(16)}", build(depth + 1))
                    }
                }
            }
            1 -> {
                produced++
                buildJsonArray { repeat(branching) { add(build(depth + 1)) } }
            }
            else -> {
                produced++
                when (rng.nextInt(4)) {
                    0 -> JsonPrimitive(rng.nextInt())
                    1 -> JsonPrimitive(rng.nextDouble())
                    2 -> JsonPrimitive(rng.nextBoolean())
                    else -> JsonPrimitive(
                        buildString {
                            repeat(rng.nextInt(4, 28)) {
                                append('a' + rng.nextInt(26))
                            }
                        }
                    )
                }
            }
        }
    }
    val root = buildJsonObject { repeat(branching) { put("root_$it", build(0)) } }
    return Json.encodeToString(JsonElement.serializer(), root)
}
