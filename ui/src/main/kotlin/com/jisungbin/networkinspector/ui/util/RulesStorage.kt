package com.jisungbin.networkinspector.ui.util

import com.jisungbin.networkinspector.ui.InterceptRule
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File

object RulesStorage {
    private val file: File by lazy {
        val home = System.getProperty("user.home")
        File(home, "Library/Application Support/NetworkInspector").apply { mkdirs() }
            .resolve("mock-rules.json")
    }
    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
        encodeDefaults = true
    }
    private val serializer = ListSerializer(InterceptRule.serializer())

    fun load(): List<InterceptRule> {
        if (!file.exists()) return emptyList()
        return runCatching {
            json.decodeFromString(serializer, file.readText())
        }.getOrElse { emptyList() }
    }

    fun save(rules: List<InterceptRule>) {
        runCatching {
            file.parentFile?.mkdirs()
            file.writeText(json.encodeToString(serializer, rules))
        }
    }

    fun exportTo(target: File, rules: List<InterceptRule>) {
        target.writeText(json.encodeToString(serializer, rules))
    }

    fun importFrom(source: File): List<InterceptRule> =
        json.decodeFromString(serializer, source.readText())
}
