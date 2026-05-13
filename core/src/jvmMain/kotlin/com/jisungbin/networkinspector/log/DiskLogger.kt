package com.jisungbin.networkinspector.log

import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

object DiskLogger {
    val file: File = File(System.getProperty("user.home"), "Desktop/network-inspector.log")
    private val lock = Any()
    private val formatter = DateTimeFormatter
        .ofPattern("yyyy-MM-dd HH:mm:ss.SSS")
        .withZone(ZoneId.systemDefault())

    init {
        synchronized(lock) {
            file.parentFile?.mkdirs()
            if (!file.exists()) file.createNewFile()
            file.appendText("\n========== session start ${formatter.format(Instant.now())} ==========\n")
        }
    }

    fun log(line: String) {
        val stamp = formatter.format(Instant.now())
        synchronized(lock) {
            file.appendText("[$stamp] $line\n")
        }
    }

    fun logBlock(label: String, body: String) {
        val stamp = formatter.format(Instant.now())
        synchronized(lock) {
            file.appendText("[$stamp] === $label ===\n")
            file.appendText(body.trimEnd())
            file.appendText("\n[$stamp] === /$label ===\n")
        }
    }

    fun logError(label: String, t: Throwable) {
        val stamp = formatter.format(Instant.now())
        synchronized(lock) {
            file.appendText("[$stamp] !! $label: ${t::class.simpleName}: ${t.message}\n")
            file.appendText(t.stackTraceToString())
        }
    }
}
