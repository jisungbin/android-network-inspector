package com.jisungbin.networkinspector.cli

import com.jisungbin.networkinspector.adb.AdbBridge
import com.jisungbin.networkinspector.adb.findBySerial
import com.jisungbin.networkinspector.adb.snapshot
import com.jisungbin.networkinspector.engine.AttachMode
import com.jisungbin.networkinspector.engine.AttachOrchestrator
import com.jisungbin.networkinspector.engine.NetworkEventRenderer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.system.exitProcess

fun main(args: Array<String>) {
    when (args.firstOrNull()) {
        "list-devices" -> listDevices()
        "attach" -> attach(args.drop(1))
        null, "help", "--help", "-h" -> printUsage()
        else -> {
            println("Unknown command: ${args[0]}")
            printUsage()
            exitProcess(1)
        }
    }
}

private fun listDevices() {
    val bridge = AdbBridge.start(AdbBridge.resolveAdb())
    try {
        val devices = bridge.devices.toList()
        if (devices.isEmpty()) {
            println("No devices connected.")
            return
        }
        println("SERIAL\tSTATE\tABI\tSDK\tMODEL")
        devices.forEach { d ->
            val s = d.snapshot()
            println("${s.serial}\t${s.state}\t${s.abi}\t${s.sdkInt}\t${s.model}")
        }
    } finally {
        AdbBridge.stop()
    }
}

private fun attach(args: List<String>) {
    val opts = parseAttachArgs(args) ?: run {
        printUsage()
        exitProcess(2)
    }
    val bundleDir = resolveStudioBundleDevice()
    val bridge = AdbBridge.start(AdbBridge.resolveAdb())
    try {
        val device = bridge.devices.toList().findBySerial(opts.serial)
        val snap = device.snapshot()
        println("device: ${snap.serial}  abi=${snap.abi}  sdk=${snap.sdkInt}  ${snap.model}")

        val mode = if (opts.activity != null) AttachMode.ColdStart else AttachMode.AttachRunning
        val session = AttachOrchestrator(device, opts.packageName, bundleDir).attach(mode, opts.activity)
        try {
            runBlocking {
                val renderer = launch(Dispatchers.IO) {
                    session.client.events().collect { e ->
                        NetworkEventRenderer.render(e)?.let(::println)
                    }
                }
                delay(1_500)
                session.sendCreateAndStart()
                println("inspector started, pid=${session.pid} hostPort=${session.hostPort}")
                renderer.join()
            }
        } finally {
            session.close()
        }
    } finally {
        AdbBridge.stop()
    }
}

private data class AttachOptions(
    val serial: String,
    val packageName: String,
    val activity: String?,
)

private fun parseAttachArgs(args: List<String>): AttachOptions? {
    var serial: String? = null
    var pkg: String? = null
    var activity: String? = null
    val it = args.iterator()
    while (it.hasNext()) {
        when (val tok = it.next()) {
            "--device" -> serial = if (it.hasNext()) it.next() else return null
            "--package" -> pkg = if (it.hasNext()) it.next() else return null
            "--activity" -> activity = if (it.hasNext()) it.next() else return null
            else -> {
                System.err.println("Unknown option: $tok")
                return null
            }
        }
    }
    return AttachOptions(
        serial = serial ?: return null,
        packageName = pkg ?: return null,
        activity = activity,
    )
}

private fun resolveStudioBundleDevice(): File {
    val explicit = System.getProperty("network.inspector.studio.bundle")
        ?: error("Set -Dnetwork.inspector.studio.bundle=<path to studio-bundle/device>")
    val f = File(explicit)
    require(f.isDirectory) { "network.inspector.studio.bundle is not a directory: $explicit" }
    return f
}

private fun printUsage() {
    println(
        """
        network-inspector-mac

        Usage:
          list-devices
            List connected adb devices.

          attach --device <serial> --package <pkg> --activity <component>
            Attach the network inspector to the given app.
        """.trimIndent()
    )
}
