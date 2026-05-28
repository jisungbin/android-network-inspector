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

/**
 * JsonViewer 상태 빌드 비용(트리 walk + ID 할당 + 기본 펼침 깊이까지의 첫 flatten)을 잰다.
 * RequestDetail이 row를 클릭할 때 백그라운드에서 수행하는 작업의 총 시간을 추적하기 위함.
 *
 * 노트: flattenJson과 JsonLine은 internal이라 벤치마크 sourceSet(=다른 Kotlin module)에서
 * 직접 호출할 수 없다. 다행히 [buildJsonViewerState]가 내부적으로 첫 flatten까지 같이 수행하므로,
 * 빌드 시간만 측정해도 default-expand 시나리오의 flatten 비용은 자연스럽게 포함된다.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
open class JsonViewerStateBuildBenchmark {

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
    fun buildState(): JsonViewerState =
        buildJsonViewerState(parsedElement, json, defaultExpandedDepth = 2)
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
