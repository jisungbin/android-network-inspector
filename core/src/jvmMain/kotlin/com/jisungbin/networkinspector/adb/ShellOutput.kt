package com.jisungbin.networkinspector.adb

import com.android.ddmlib.IDevice
import com.android.ddmlib.IShellOutputReceiver

class CollectingShellOutputReceiver : IShellOutputReceiver {
    private val buffer = StringBuilder()
    private var cancelled = false

    override fun addOutput(data: ByteArray, offset: Int, length: Int) {
        buffer.append(String(data, offset, length))
    }

    override fun flush() = Unit

    override fun isCancelled(): Boolean = cancelled

    fun output(): String = buffer.toString()

    fun cancel() {
        cancelled = true
    }
}

fun IDevice.shell(cmd: String, timeoutMs: Long = 30_000): String {
    com.jisungbin.networkinspector.log.DiskLogger.log("shell> $cmd")
    val receiver = CollectingShellOutputReceiver()
    try {
        executeShellCommand(cmd, receiver, timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)
        val out = receiver.output()
        if (out.isNotBlank()) {
            com.jisungbin.networkinspector.log.DiskLogger.logBlock("shell-out: $cmd", out)
        } else {
            com.jisungbin.networkinspector.log.DiskLogger.log("shell< $cmd  (no output)")
        }
        return out
    } catch (t: Throwable) {
        com.jisungbin.networkinspector.log.DiskLogger.logError("shell-fail: $cmd", t)
        throw t
    }
}
