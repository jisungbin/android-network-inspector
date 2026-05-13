package com.jisungbin.networkinspector.deploy

import com.android.ddmlib.IDevice
import com.android.ddmlib.SyncService
import com.jisungbin.networkinspector.adb.shell
import com.jisungbin.networkinspector.adb.snapshot
import java.io.File

class AgentDeployer(
    private val device: IDevice,
    private val bundleDir: File,
    private val packageName: String,
) {
    fun deploy(): DeployResult {
        val abi = device.snapshot().abi
            ?: error("Cannot detect device ABI for ${device.serialNumber}")
        ensureDeviceDir()
        val transport = pushTransport(abi)
        val perfa = pushPerfa()
        val networkInspector = pushNetworkInspector()
        val agent = pushJvmtiAgent(abi)
        return DeployResult(transport, perfa, networkInspector, agent, abi)
    }

    private fun ensureDeviceDir() {
        device.shell("rm -rf $DEVICE_DIR")
        device.shell("mkdir -p $DEVICE_DIR")
        device.shell("chown shell:shell $DEVICE_DIR")
    }

    private fun pushTransport(abi: String): String {
        val src = bundleDir.resolve("transport/$abi/$TRANSPORT_BIN")
        require(src.isFile) { "Missing transport binary for $abi at ${src.absolutePath}" }
        val dst = "$DEVICE_DIR/$TRANSPORT_BIN"
        device.pushFile(src, dst)
        device.shell("chmod 755 $dst")
        return dst
    }

    private fun pushPerfa(): String {
        val src = bundleDir.resolve(PERFA_JAR)
        require(src.isFile) { "Missing perfa.jar at ${src.absolutePath}" }
        val tmpDst = "$DEVICE_DIR/$PERFA_JAR"
        device.pushFile(src, tmpDst)
        device.shell("chmod 444 $tmpDst")
        device.shell(
            "run-as $packageName sh -c 'mkdir -p $CODE_CACHE_DIR && cp $tmpDst $CODE_CACHE_DIR/$PERFA_JAR'"
        )
        return "/data/data/$packageName/$CODE_CACHE_DIR/$PERFA_JAR"
    }

    private fun pushNetworkInspector(): String {
        val src = bundleDir.resolve("app-inspection/$NETWORK_INSPECTOR_JAR")
        require(src.isFile) { "Missing network-inspector.jar at ${src.absolutePath}" }
        val dst = "$DEVICE_DIR/$NETWORK_INSPECTOR_JAR"
        device.pushFile(src, dst)
        device.shell("chmod 444 $dst")
        return dst
    }

    private fun pushJvmtiAgent(abi: String): String {
        val src = bundleDir.resolve("transport/native/agent/$abi/$JVMTI_AGENT")
        require(src.isFile) { "Missing libjvmtiagent for $abi at ${src.absolutePath}" }
        val abiFileName = "libjvmtiagent_$abi.so"
        val tmpDst = "$DEVICE_DIR/$abiFileName"
        device.pushFile(src, tmpDst)
        device.shell("chmod 644 $tmpDst")
        device.shell(
            "run-as $packageName sh -c 'mkdir -p $CODE_CACHE_DIR && cp $tmpDst $CODE_CACHE_DIR/$abiFileName'"
        )
        return "/data/data/$packageName/$CODE_CACHE_DIR/$abiFileName"
    }

    private fun IDevice.pushFile(local: File, remote: String) {
        val sync = syncService ?: error("SyncService unavailable on $serialNumber")
        try {
            sync.pushFile(local.absolutePath, remote, SyncService.getNullProgressMonitor())
        } finally {
            sync.close()
        }
    }

    companion object {
        const val DEVICE_DIR = "/data/local/tmp/perfd"
        const val CODE_CACHE_DIR = "code_cache"
        const val TRANSPORT_BIN = "transport"
        const val JVMTI_AGENT = "libjvmtiagent.so"
        const val PERFA_JAR = "perfa.jar"
        const val NETWORK_INSPECTOR_JAR = "network-inspector.jar"
    }
}

data class DeployResult(
    val transportPath: String,
    val perfaPath: String,
    val networkInspectorPath: String,
    val agentPath: String,
    val abi: String,
)
