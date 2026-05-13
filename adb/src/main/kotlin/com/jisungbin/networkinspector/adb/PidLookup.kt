package com.jisungbin.networkinspector.adb

import com.android.ddmlib.IDevice
import com.jisungbin.networkinspector.adb.shell

fun IDevice.pidOf(packageName: String): Int? {
    val out = shell("pidof $packageName").trim()
    return out.split(Regex("\\s+")).firstOrNull()?.toIntOrNull()
}

fun IDevice.waitForPid(packageName: String, timeoutMs: Long = 10_000): Int {
    val deadline = System.nanoTime() + timeoutMs * 1_000_000
    while (System.nanoTime() < deadline) {
        val pid = pidOf(packageName)
        if (pid != null && pid > 0) return pid
        Thread.sleep(150)
    }
    error("Timed out waiting for PID of $packageName")
}
