package com.jisungbin.networkinspector.core.util

import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import java.io.File

object IgnoredHostsStorage {
    private val file: File by lazy {
        val home = System.getProperty("user.home")
        File(home, "Library/Application Support/NetworkInspector").apply { mkdirs() }
            .resolve("ignored-hosts.json")
    }
    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
    }
    private val serializer = ListSerializer(String.serializer())

    fun load(): List<String> {
        if (!file.exists()) return emptyList()
        return runCatching {
            json.decodeFromString(serializer, file.readText())
        }.getOrElse { emptyList() }
    }

    fun save(hosts: List<String>) {
        runCatching {
            file.parentFile?.mkdirs()
            file.writeText(json.encodeToString(serializer, hosts))
        }
    }
}
