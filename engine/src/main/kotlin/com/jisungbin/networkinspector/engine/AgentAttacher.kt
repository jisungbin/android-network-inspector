package com.jisungbin.networkinspector.engine

import com.android.ddmlib.IDevice
import com.jisungbin.networkinspector.adb.shell

class AgentAttacher(
    private val device: IDevice,
    private val packageName: String,
) {
    fun coldStart(
        activity: String,
        agentSoPath: String,
        agentConfigPath: String,
    ): String {
        device.shell("am force-stop $packageName")
        return device.shell("am start --attach-agent $agentSoPath=$agentConfigPath -n $activity")
    }

    fun attachRunning(
        pid: Int,
        agentSoPath: String,
        agentConfigPath: String,
    ): String = device.shell("am attach-agent $pid $agentSoPath=$agentConfigPath")
}
