package com.jisungbin.networkinspector.adb

import com.android.ddmlib.IDevice
import java.net.ServerSocket

class PortForwarder(private val device: IDevice) {
    fun forwardAbstract(socketName: String, localPort: Int? = null): Int {
        val port = localPort ?: pickFreePort()
        device.createForward(port, socketName, IDevice.DeviceUnixSocketNamespace.ABSTRACT)
        return port
    }

    fun remove(port: Int, socketName: String) {
        runCatching {
            device.removeForward(port, socketName, IDevice.DeviceUnixSocketNamespace.ABSTRACT)
        }
    }

    private fun pickFreePort(): Int = ServerSocket(0).use { it.localPort }
}
