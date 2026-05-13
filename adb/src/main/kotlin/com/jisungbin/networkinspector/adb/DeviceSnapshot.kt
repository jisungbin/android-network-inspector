package com.jisungbin.networkinspector.adb

import com.android.ddmlib.IDevice

data class DeviceSnapshot(
    val serial: String,
    val state: IDevice.DeviceState?,
    val abi: String?,
    val sdkInt: Int?,
    val model: String?,
)

fun IDevice.snapshot(): DeviceSnapshot = DeviceSnapshot(
    serial = serialNumber,
    state = state,
    abi = getProperty(IDevice.PROP_DEVICE_CPU_ABI),
    sdkInt = getProperty(IDevice.PROP_BUILD_API_LEVEL)?.toIntOrNull(),
    model = getProperty(IDevice.PROP_DEVICE_MODEL),
)

fun List<IDevice>.findBySerial(serial: String): IDevice =
    firstOrNull { it.serialNumber == serial }
        ?: error("Device not found: $serial. Available: ${joinToString { it.serialNumber }}")
