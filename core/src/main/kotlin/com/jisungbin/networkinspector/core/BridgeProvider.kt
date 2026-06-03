package com.jisungbin.networkinspector.core

import com.android.ddmlib.AndroidDebugBridge
import com.android.ddmlib.IDevice
import com.jisungbin.networkinspector.adb.AdbBridge
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Lazily starts one [AndroidDebugBridge] and shares it across the device/session controllers. */
internal class BridgeProvider {
    @Volatile
    private var bridge: AndroidDebugBridge? = null

    suspend fun get(): AndroidDebugBridge = withContext(Dispatchers.IO) {
        bridge ?: AdbBridge.start(AdbBridge.resolveAdb()).also { bridge = it }
    }

    /** The connected device for [serial], or null if the bridge is not started / device is gone. */
    fun deviceOf(serial: String): IDevice? =
        bridge?.devices?.toList()?.firstOrNull { it.serialNumber == serial }
}
