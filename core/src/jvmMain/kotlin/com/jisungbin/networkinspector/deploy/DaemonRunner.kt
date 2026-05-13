package com.jisungbin.networkinspector.deploy

import com.android.ddmlib.IDevice
import com.android.ddmlib.IShellOutputReceiver
import com.android.ddmlib.SyncService
import com.jisungbin.networkinspector.adb.shell
import com.jisungbin.networkinspector.log.DiskLogger
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class DaemonRunner(private val device: IDevice) {
    @Volatile private var daemonThread: Thread? = null
    private val daemonAlive = AtomicBoolean(false)
    private val listeningLatch = CountDownLatch(1)
    @Volatile private var listening = false
    fun pushConfigs(daemonConfig: ByteArray, agentConfig: ByteArray) {
        pushBytes(daemonConfig, "${AgentDeployer.DEVICE_DIR}/$DAEMON_CONFIG", mode = 444)
        pushBytes(agentConfig, "${AgentDeployer.DEVICE_DIR}/$AGENT_CONFIG", mode = 444)
    }

    fun start() {
        val transport = "${AgentDeployer.DEVICE_DIR}/${AgentDeployer.TRANSPORT_BIN}"
        val cfg = "${AgentDeployer.DEVICE_DIR}/$DAEMON_CONFIG"
        daemonAlive.set(true)
        val receiver = object : IShellOutputReceiver {
            override fun addOutput(data: ByteArray, offset: Int, length: Int) {
                val s = String(data, offset, length, Charsets.UTF_8)
                DiskLogger.log("transport: ${s.trimEnd()}")
                if (s.contains("Server listening on")) {
                    listening = true
                    listeningLatch.countDown()
                }
            }
            override fun flush() = Unit
            override fun isCancelled(): Boolean = !daemonAlive.get()
        }
        daemonThread = Thread {
            DiskLogger.log("perfd-runner thread: start")
            try {
                device.executeShellCommand(
                    "$transport -config_file=$cfg",
                    receiver,
                    Long.MAX_VALUE,
                    Long.MAX_VALUE,
                    TimeUnit.MILLISECONDS,
                )
                DiskLogger.log("perfd-runner thread: shell returned (transport exited)")
            } catch (t: Throwable) {
                if (daemonAlive.get()) DiskLogger.logError("perfd-runner thread", t)
            } finally {
                daemonAlive.set(false)
                listeningLatch.countDown()
            }
        }.apply {
            name = "perfd-runner-${device.serialNumber}"
            isDaemon = true
            start()
        }
    }

    fun waitForListening(timeoutMs: Long = 10_000): Boolean {
        listeningLatch.await(timeoutMs, TimeUnit.MILLISECONDS)
        return listening && daemonAlive.get()
    }

    fun isRunning(): Boolean {
        val pid = device.shell("pidof ${AgentDeployer.TRANSPORT_BIN} 2>/dev/null").trim()
        return pid.isNotEmpty() && pid.split(Regex("\\s+")).any { it.toIntOrNull() != null }
    }

    fun waitRunning(timeoutMs: Long = 5_000, intervalMs: Long = 200): Boolean {
        val deadline = System.nanoTime() + timeoutMs * 1_000_000
        while (System.nanoTime() < deadline) {
            if (isRunning()) return true
            Thread.sleep(intervalMs)
        }
        return false
    }

    fun waitSocketReady(socketName: String, timeoutMs: Long = 8_000, intervalMs: Long = 150): Boolean {
        val deadline = System.nanoTime() + timeoutMs * 1_000_000
        while (System.nanoTime() < deadline) {
            val hit = device.shell("cat /proc/net/unix 2>/dev/null | grep -F $socketName")
                .trim()
            if (hit.isNotEmpty()) return true
            Thread.sleep(intervalMs)
        }
        return false
    }

    fun stop() {
        daemonAlive.set(false)
        runCatching { device.shell("pkill ${AgentDeployer.TRANSPORT_BIN} 2>/dev/null") }
        daemonThread?.interrupt()
        daemonThread = null
    }

    fun tail(lines: Int = 50): String =
        device.shell(
            "if [ -f ${AgentDeployer.DEVICE_DIR}/perfd.log ]; then " +
                "tail -n $lines ${AgentDeployer.DEVICE_DIR}/perfd.log; " +
                "else echo '(no perfd.log file)'; fi"
        )

    fun diagnose(): String {
        val transport = "${AgentDeployer.DEVICE_DIR}/${AgentDeployer.TRANSPORT_BIN}"
        val cfg = "${AgentDeployer.DEVICE_DIR}/$DAEMON_CONFIG"
        val abi = device.snapshotAbi()
        val running = runCatching { isRunning() }.getOrDefault(false)
        val listing = device.shell("ls -la ${AgentDeployer.DEVICE_DIR}")
        val pids = device.shell(
            "ps -A 2>/dev/null | grep -F ${AgentDeployer.TRANSPORT_BIN}"
        )
        val foreground = device.shell(
            "$transport -config_file=$cfg 2>&1 & P=\$!; sleep 1; kill -9 \$P 2>/dev/null; wait 2>/dev/null",
            timeoutMs = 15_000,
        )
        val logcat = device.shell(
            "logcat -d -t 400 2>/dev/null | grep -iE 'transport|perfd|grpc|jvmti|appinsp' | tail -n 60",
            timeoutMs = 15_000,
        )
        return buildString {
            appendLine("[abi] $abi  running=$running")
            appendLine("[pids matching transport]")
            appendLine(pids.ifBlank { "(none)" })
            appendLine("[dir listing]")
            appendLine(listing)
            appendLine("[perfd.log]")
            appendLine(tail().ifBlank { "(empty)" })
            appendLine("[foreground 1s run]")
            appendLine(foreground.ifBlank { "(empty)" })
            appendLine("[logcat filtered (transport|perfd|grpc|jvmti|appinsp)]")
            appendLine(logcat.ifBlank { "(empty)" })
        }
    }

    private fun com.android.ddmlib.IDevice.snapshotAbi(): String? =
        getProperty(com.android.ddmlib.IDevice.PROP_DEVICE_CPU_ABI)

    private fun pushBytes(bytes: ByteArray, remote: String, mode: Int) {
        val tmp = File.createTempFile("perfd-config-", ".bin").apply { deleteOnExit() }
        try {
            tmp.writeBytes(bytes)
            val sync = device.syncService ?: error("SyncService unavailable on ${device.serialNumber}")
            try {
                sync.pushFile(tmp.absolutePath, remote, SyncService.getNullProgressMonitor())
            } finally {
                sync.close()
            }
            device.shell("chmod $mode $remote")
            device.shell("chown shell:shell $remote")
        } finally {
            tmp.delete()
        }
    }

    companion object {
        const val DAEMON_CONFIG = "daemon.config"
        const val AGENT_CONFIG = "agent.config"
    }
}
