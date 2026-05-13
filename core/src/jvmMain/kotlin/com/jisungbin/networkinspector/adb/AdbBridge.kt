package com.jisungbin.networkinspector.adb

import com.android.ddmlib.AndroidDebugBridge
import java.io.File

object AdbBridge {
    fun start(adbPath: File, initTimeoutMs: Long = 10_000): AndroidDebugBridge {
        require(adbPath.isFile) { "adb not found at ${adbPath.absolutePath}" }
        AndroidDebugBridge.init(false)
        val bridge = AndroidDebugBridge.createBridge(adbPath.absolutePath, false)
            ?: error("Failed to create AndroidDebugBridge from ${adbPath.absolutePath}")
        val deadline = System.nanoTime() + initTimeoutMs * 1_000_000
        while (!bridge.hasInitialDeviceList()) {
            if (System.nanoTime() > deadline) error("Timed out waiting for ADB device list")
            Thread.sleep(100)
        }
        return bridge
    }

    fun stop() {
        AndroidDebugBridge.terminate()
    }

    fun resolveAdb(): File {
        val home = System.getenv("ANDROID_HOME")
            ?: System.getenv("ANDROID_SDK_ROOT")
            ?: "${System.getProperty("user.home")}/Library/Android/sdk"
        return File("$home/platform-tools/adb")
    }
}
