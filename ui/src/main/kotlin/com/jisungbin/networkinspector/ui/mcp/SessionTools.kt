package com.jisungbin.networkinspector.ui.mcp

import com.jisungbin.networkinspector.engine.AttachMode
import com.jisungbin.networkinspector.ui.AttachState
import com.jisungbin.networkinspector.ui.DeviceSession
import io.modelcontextprotocol.kotlin.sdk.server.Server
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/**
 * Device + session lifecycle tools. attach/detach drive the very same [AppStore] flow the DEVICES
 * screen uses, so a tab appears/streams/closes in the window as the agent works.
 */
internal fun registerSessionTools(server: Server, ctx: McpToolContext) {

    ctx.tool(
        server,
        name = "list_devices",
        description = "List connected Android devices (serial, state, abi, sdk, model) and the current " +
            "attach/streaming state of each.",
        inputSchema = toolSchema(
            props = arrayOf("refresh" to boolProp("Re-scan adb before listing. Default false.")),
        ),
    ) { request ->
        if (request.args.bool("refresh") == true) {
            ctx.store.refreshDevices()
            delay(1200)
        }
        val ui = ctx.store.state.value
        ctx.ok(buildJsonObject {
            put("selectedSerial", ui.selectedSerial?.let { JsonPrimitive(it) } ?: JsonNull)
            put("count", ui.devices.size)
            putJsonArray("devices") {
                ui.devices.forEach { d ->
                    add(buildJsonObject {
                        put("serial", d.serial)
                        put("state", d.state?.name ?: "unknown")
                        put("abi", d.abi)
                        put("sdk", d.sdkInt)
                        put("model", d.model)
                        put("attach", attachJson(ui.sessions[d.serial]))
                    })
                }
            }
        })
    }

    ctx.tool(
        server,
        name = "list_packages",
        description = "List debuggable third-party packages installed on a device, suitable as attach targets.",
        inputSchema = toolSchema(
            props = arrayOf(
                serialProp,
                "refresh" to boolProp("Re-query the device package list. Default false."),
            ),
        ),
    ) { request ->
        val a = request.args
        val serial = resolveDeviceSerial(ctx, a.string("serial"))
        val current = ctx.store.state.value.sessions[serial]?.packages.orEmpty()
        if (current.isEmpty() || a.bool("refresh") == true) {
            ctx.store.loadPackages(serial)
            withTimeoutOrNull(10_000) {
                ctx.store.state.first { it.sessions[serial]?.packagesLoading == false }
            }
        }
        val packages = ctx.store.state.value.sessions[serial]?.packages.orEmpty()
        ctx.ok(buildJsonObject {
            put("serial", serial)
            put("count", packages.size)
            putJsonArray("packages") { packages.forEach { add(it) } }
        })
    }

    ctx.tool(
        server,
        name = "attach",
        description = "Attach the inspector to an app and start capturing. Deploys the agent and begins " +
            "streaming; reflects live in the app window. Blocks until streaming starts or attach fails. " +
            "If the app is already running, attaches to it; otherwise cold-starts it (needs an activity).",
        inputSchema = toolSchema(
            required = listOf("package"),
            props = arrayOf(
                serialProp,
                "package" to strProp("Target app package name, e.g. com.example.app."),
                "activity" to strProp("Launcher activity for a cold start. Auto-resolved if omitted."),
                "mode" to enumProp(
                    "Attach mode. 'auto' picks attachRunning if the app is running, else coldStart.",
                    listOf("auto", "coldStart", "attachRunning"),
                ),
            ),
        ),
    ) { request ->
        val a = request.args
        val serial = resolveDeviceSerial(ctx, a.string("serial"))
        val packageName = a.requireString("package")

        val current = ctx.store.state.value.sessions[serial]?.attach
        if (current is AttachState.Streaming) {
            throw ToolError("device '$serial' is already streaming (pid=${current.pid}). Detach first to re-attach.")
        }
        if (current is AttachState.Connecting) {
            throw ToolError("an attach is already in progress on '$serial'.")
        }

        // Reuse the UI flow: set the package (resolves pid + launcher activity asynchronously)...
        ctx.store.updatePackage(serial, packageName)
        withTimeoutOrNull(15_000) {
            ctx.store.state.first { it.sessions[serial]?.activityResolving == false }
        }
        // ...then apply explicit overrides.
        when (a.string("mode")) {
            "coldStart" -> ctx.store.updateMode(serial, AttachMode.ColdStart)
            "attachRunning" -> ctx.store.updateMode(serial, AttachMode.AttachRunning)
            else -> {} // auto: keep what updatePackage resolved
        }
        a.string("activity")?.let { ctx.store.updateActivity(serial, it) }

        val prepared = ctx.store.state.value.sessions[serial]
            ?: throw ToolError("could not prepare session for '$serial'.")
        if (prepared.attachMode == AttachMode.ColdStart && prepared.activity.isBlank()) {
            throw ToolError(
                "cold start needs a launcher activity but none was resolved for '$packageName'. " +
                    "Pass 'activity', or launch the app first and use mode=attachRunning.",
            )
        }

        ctx.store.attach(serial)
        val outcome = withTimeoutOrNull(90_000) {
            ctx.store.state.first {
                val st = it.sessions[serial]?.attach
                st is AttachState.Streaming || st is AttachState.Failed
            }.sessions[serial]?.attach
        }
        when (outcome) {
            is AttachState.Streaming -> ctx.ok(buildJsonObject {
                put("attached", true)
                put("serial", serial)
                put("package", packageName)
                put("mode", prepared.attachMode.name)
                put("pid", outcome.pid)
                put("hostPort", outcome.hostPort)
            })
            is AttachState.Failed -> ctx.err("attach failed for '$serial':\n${outcome.message}")
            else -> ctx.err("attach to '$serial' did not reach streaming within 90s; check the app window.")
        }
    }

    ctx.tool(
        server,
        name = "detach",
        description = "Detach the inspector from a device, stopping capture and closing its tab. " +
            "Clears that device's captured rows (intercept rules are kept).",
        inputSchema = toolSchema(props = arrayOf(serialProp)),
    ) { request ->
        val serial = ctx.requireSerial(request.args.string("serial"))
        ctx.store.detach(serial)
        ctx.ok(buildJsonObject {
            put("detached", true)
            put("serial", serial)
        })
    }

    ctx.tool(
        server,
        name = "get_status",
        description = "Overall inspector status: the MCP endpoint, connected devices, per-device attach " +
            "state and capture counts, and how many intercept rules are defined.",
        inputSchema = toolSchema(),
    ) { _ ->
        val ui = ctx.store.state.value
        ctx.ok(buildJsonObject {
            ctx.serverEndpoint?.let { put("mcpEndpoint", it) }
            put("selectedSerial", ui.selectedSerial?.let { JsonPrimitive(it) } ?: JsonNull)
            put("deviceCount", ui.devices.size)
            put("ruleCount", ui.interceptRules.size)
            putJsonArray("ignoredHosts") { ui.ignoredHosts.forEach { add(it) } }
            putJsonArray("sessions") {
                ui.sessions.values.forEach { s ->
                    add(buildJsonObject {
                        put("serial", s.serial)
                        put("model", s.model ?: "")
                        put("package", s.packageName)
                        put("attach", attachJson(s))
                        put("capturedRequests", s.rows.size)
                        put("mockedResponses", s.rows.count { it.mocked })
                        put("paused", s.paused)
                    })
                }
            }
        })
    }
}

/**
 * Serial resolution for device-targeting tools (attach/list_packages), where the device is not yet
 * streaming so the streaming-fallback in [McpToolContext.requireSerial] does not apply. Explicit
 * serial wins; otherwise the sole connected device is used.
 */
private fun resolveDeviceSerial(ctx: McpToolContext, serial: String?): String {
    val ui = ctx.store.state.value
    if (serial != null) {
        if (ui.devices.any { it.serial == serial } || ui.sessions.containsKey(serial)) return serial
        throw ToolError("unknown device serial '$serial'. Call list_devices.")
    }
    return when (ui.devices.size) {
        1 -> ui.devices.first().serial
        0 -> throw ToolError("no devices connected.")
        else -> throw ToolError("multiple devices connected; pass 'serial'. Call list_devices.")
    }
}

private fun attachJson(session: DeviceSession?): JsonObject = buildJsonObject {
    when (val st = session?.attach) {
        null, is AttachState.Idle -> put("state", "idle")
        is AttachState.Connecting -> {
            put("state", "connecting")
            put("phase", st.phase.label)
        }
        is AttachState.Streaming -> {
            put("state", "streaming")
            put("pid", st.pid)
            put("hostPort", st.hostPort)
        }
        is AttachState.Failed -> {
            put("state", "failed")
            put("message", st.message.lineSequence().firstOrNull().orEmpty())
        }
    }
}
