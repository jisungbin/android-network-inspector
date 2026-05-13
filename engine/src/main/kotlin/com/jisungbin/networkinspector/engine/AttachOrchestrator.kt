package com.jisungbin.networkinspector.engine

import com.android.ddmlib.IDevice
import com.android.tools.app.inspection.AppInspection
import com.android.tools.profiler.proto.Common
import com.jisungbin.networkinspector.adb.PortForwarder
import com.jisungbin.networkinspector.adb.pidOf
import com.jisungbin.networkinspector.adb.waitForPid
import com.jisungbin.networkinspector.log.DiskLogger
import com.jisungbin.networkinspector.protocol.Configs
import com.jisungbin.networkinspector.protocol.NETWORK_INSPECTOR_ID
import com.jisungbin.networkinspector.protocol.TRANSPORT_SOCKET_NAME
import com.jisungbin.networkinspector.protocol.TransportClient
import com.jisungbin.networkinspector.protocol.createNetworkInspectorCommand
import com.jisungbin.networkinspector.protocol.startNetworkInspectionCommand
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.onEach
import studio.network.inspection.NetworkInspectorProtocol
import java.io.File

enum class AttachMode { ColdStart, AttachRunning }
enum class AttachStage { Deploying, DaemonStart, AttachAgent, Forwarding, CreatingInspector }

class AttachOrchestrator(
    private val device: IDevice,
    private val packageName: String,
    private val bundleDir: File,
) {
    fun attach(mode: AttachMode, activity: String?, onStage: (AttachStage) -> Unit = {}): AttachSession {
        DiskLogger.log("=== attach start: device=${device.serialNumber} package=$packageName mode=$mode activity=$activity ===")
        onStage(AttachStage.Deploying)
        val deployer = AgentDeployer(device, bundleDir, packageName)
        val deployResult = deployer.deploy()
        DiskLogger.log("deploy: $deployResult")
        onStage(AttachStage.DaemonStart)

        val runner = DaemonRunner(device)
        runner.stop()
        runner.pushConfigs(
            daemonConfig = Configs.daemon().toByteArray(),
            agentConfig = Configs.agent().toByteArray(),
        )
        runner.start()
        if (!runner.waitForListening(timeoutMs = 10_000)) {
            val d = runner.diagnose()
            DiskLogger.logBlock("perfd-failed-diagnose", d)
            error("perfd did not emit 'Server listening' within 10s.\n$d")
        }
        DiskLogger.log("perfd: Server listening — ready")

        val agentConfigPath = "${AgentDeployer.DEVICE_DIR}/${DaemonRunner.AGENT_CONFIG}"
        val attacher = AgentAttacher(device, packageName)
        onStage(AttachStage.AttachAgent)

        val pid: Int = when (mode) {
            AttachMode.ColdStart -> {
                requireNotNull(activity) { "activity is required for ColdStart mode" }
                attacher.coldStart(activity, deployResult.agentPath, agentConfigPath)
                device.waitForPid(packageName, timeoutMs = 30_000)
            }
            AttachMode.AttachRunning -> {
                val existing = device.pidOf(packageName)
                    ?: error("Package $packageName is not running; switch to ColdStart")
                attacher.attachRunning(existing, deployResult.agentPath, agentConfigPath)
                existing
            }
        }
        DiskLogger.log("agent attached: pid=$pid mode=$mode")
        onStage(AttachStage.Forwarding)

        val forwarder = PortForwarder(device)
        val hostPort = forwarder.forwardAbstract(TRANSPORT_SOCKET_NAME)
        DiskLogger.log("adb forward: tcp:$hostPort -> localabstract:$TRANSPORT_SOCKET_NAME")

        val client = TransportClient("127.0.0.1", hostPort)
        DiskLogger.log("gRPC channel opened 127.0.0.1:$hostPort")
        onStage(AttachStage.CreatingInspector)
        return AttachSession(
            client = client,
            pid = pid,
            hostPort = hostPort,
            deployResult = deployResult,
            runner = runner,
            forwarder = forwarder,
            packageName = packageName,
        )
    }
}

class AttachSession(
    val client: TransportClient,
    val pid: Int,
    val hostPort: Int,
    val deployResult: DeployResult,
    val runner: DaemonRunner,
    private val forwarder: PortForwarder,
    private val packageName: String,
) : AutoCloseable {
    fun rawEvents(): Flow<Common.Event> = client.events()
        .onEach { DiskLogger.log("raw-event: kind=${it.kind} pid=${it.pid} groupId=${it.groupId} ts=${it.timestamp}") }

    fun networkEvents(): Flow<NetworkInspectorProtocol.Event> = client.events()
        .filter { it.kind == Common.Event.Kind.APP_INSPECTION_EVENT }
        .filter { it.appInspectionEvent.inspectorId == NETWORK_INSPECTOR_ID }
        .mapNotNull { event ->
            val ai = event.appInspectionEvent
            if (ai.unionCase != AppInspection.AppInspectionEvent.UnionCase.RAW_EVENT) return@mapNotNull null
            val raw = ai.rawEvent
            if (raw.dataCase != AppInspection.RawEvent.DataCase.CONTENT) return@mapNotNull null
            NetworkInspectorProtocol.Event.parseFrom(raw.content)
        }
        .onEach { DiskLogger.log("net-event: ${it.unionCase} ts=${it.timestamp}") }

    fun sendCreateAndStart() {
        val agentLibFileName = "libjvmtiagent_${deployResult.abi}.so"
        val agentConfigPath = "${AgentDeployer.DEVICE_DIR}/${DaemonRunner.AGENT_CONFIG}"
        DiskLogger.log("send ATTACH_AGENT pid=$pid lib=$agentLibFileName cfg=$agentConfigPath pkg=$packageName")
        client.execute(
            com.android.tools.profiler.proto.Commands.Command.newBuilder()
                .setStreamId(0L)
                .setPid(pid)
                .setType(com.android.tools.profiler.proto.Commands.Command.CommandType.ATTACH_AGENT)
                .setAttachAgent(
                    com.android.tools.profiler.proto.Commands.AttachAgent.newBuilder()
                        .setAgentLibFileName(agentLibFileName)
                        .setAgentConfigPath(agentConfigPath)
                        .setPackageName(packageName)
                )
                .build()
        )
        Thread.sleep(500)

        DiskLogger.log("send CreateInspectorCommand pid=$pid dex=${deployResult.networkInspectorPath}")
        client.execute(
            createNetworkInspectorCommand(
                pid = pid,
                streamId = 0L,
                dexPath = deployResult.networkInspectorPath,
            )
        )
        DiskLogger.log("send StartInspectionCommand pid=$pid")
        client.execute(startNetworkInspectionCommand(pid = pid, streamId = 0L))
    }

    fun sendRaw(command: NetworkInspectorProtocol.Command) {
        val app = AppInspection.AppInspectionCommand.newBuilder()
            .setInspectorId(NETWORK_INSPECTOR_ID)
            .setRawInspectorCommand(
                AppInspection.RawCommand.newBuilder().setContent(command.toByteString())
            )
            .build()
        val full = com.android.tools.profiler.proto.Commands.Command.newBuilder()
            .setStreamId(0L)
            .setPid(pid)
            .setType(com.android.tools.profiler.proto.Commands.Command.CommandType.APP_INSPECTION)
            .setAppInspectionCommand(app)
            .build()
        client.execute(full)
    }

    override fun close() {
        runCatching { client.close() }
        forwarder.remove(hostPort, TRANSPORT_SOCKET_NAME)
        runner.stop()
    }
}
