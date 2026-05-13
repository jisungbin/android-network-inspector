package com.jisungbin.networkinspector.engine

import com.android.ddmlib.IDevice
import com.android.tools.app.inspection.AppInspection
import com.android.tools.profiler.proto.Commands
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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
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
    private val sessionScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val streamIdLatch = CompletableDeferred<Long>()

    @Volatile
    var streamId: Long = 0L
        private set

    private val sharedEvents: SharedFlow<Common.Event> = client.events()
        .onEach { event ->
            DiskLogger.log("raw-event: kind=${event.kind} pid=${event.pid} groupId=${event.groupId} ts=${event.timestamp}")
            if (streamId == 0L && event.kind == Common.Event.Kind.STREAM && event.groupId != 0L) {
                streamId = event.groupId
                if (!streamIdLatch.isCompleted) streamIdLatch.complete(event.groupId)
                DiskLogger.log("learned streamId=${event.groupId} from STREAM event")
            }
        }
        .shareIn(sessionScope, SharingStarted.Eagerly, replay = 0)

    fun rawEvents(): Flow<Common.Event> = sharedEvents

    fun networkEvents(): Flow<NetworkInspectorProtocol.Event> = sharedEvents
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

    private fun awaitStreamIdBlocking(timeoutMs: Long = 5_000): Long {
        if (streamId != 0L) return streamId
        return runBlocking {
            withTimeoutOrNull(timeoutMs) { streamIdLatch.await() } ?: 0L
        }
    }

    fun sendCreateAndStart() {
        val sid = awaitStreamIdBlocking()
        val agentLibFileName = "libjvmtiagent_${deployResult.abi}.so"
        val agentConfigPath = "${AgentDeployer.DEVICE_DIR}/${DaemonRunner.AGENT_CONFIG}"
        DiskLogger.log("send ATTACH_AGENT streamId=$sid pid=$pid lib=$agentLibFileName cfg=$agentConfigPath pkg=$packageName")
        client.execute(
            Commands.Command.newBuilder()
                .setStreamId(sid)
                .setPid(pid)
                .setType(Commands.Command.CommandType.ATTACH_AGENT)
                .setAttachAgent(
                    Commands.AttachAgent.newBuilder()
                        .setAgentLibFileName(agentLibFileName)
                        .setAgentConfigPath(agentConfigPath)
                        .setPackageName(packageName)
                )
                .build()
        )
        Thread.sleep(500)

        DiskLogger.log("send CreateInspectorCommand streamId=$sid pid=$pid dex=${deployResult.networkInspectorPath}")
        client.execute(
            createNetworkInspectorCommand(
                pid = pid,
                streamId = sid,
                dexPath = deployResult.networkInspectorPath,
            )
        )
        DiskLogger.log("send StartInspectionCommand streamId=$sid pid=$pid")
        client.execute(startNetworkInspectionCommand(pid = pid, streamId = sid))
    }

    override fun close() {
        runCatching { sessionScope.cancel() }
        runCatching { client.close() }
        forwarder.remove(hostPort, TRANSPORT_SOCKET_NAME)
        runner.stop()
    }
}
