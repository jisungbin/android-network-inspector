package com.jisungbin.networkinspector.protocol

import com.android.tools.app.inspection.AppInspection
import com.android.tools.profiler.proto.Commands
import java.util.concurrent.atomic.AtomicInteger

const val NETWORK_INSPECTOR_ID = "studio.network.inspection"

private val commandSeq = AtomicInteger(1)

fun nextCommandId(): Int = commandSeq.getAndIncrement()

fun createNetworkInspectorCommand(
    pid: Int,
    streamId: Long,
    dexPath: String,
    launchedBy: String = "network-inspector-mac",
): Commands.Command {
    val commandId = nextCommandId()
    val launchMetadata = AppInspection.LaunchMetadata.newBuilder()
        .setLaunchedByName(launchedBy)
        .setForce(true)
        .build()
    val createInspector = AppInspection.CreateInspectorCommand.newBuilder()
        .setDexPath(dexPath)
        .setLaunchMetadata(launchMetadata)
        .build()
    val appInspection = AppInspection.AppInspectionCommand.newBuilder()
        .setInspectorId(NETWORK_INSPECTOR_ID)
        .setCommandId(commandId)
        .setCreateInspectorCommand(createInspector)
        .build()
    return Commands.Command.newBuilder()
        .setStreamId(streamId)
        .setPid(pid)
        .setType(Commands.Command.CommandType.APP_INSPECTION)
        .setCommandId(commandId)
        .setAppInspectionCommand(appInspection)
        .build()
}

fun startNetworkInspectionCommand(
    pid: Int,
    streamId: Long,
): Commands.Command {
    val commandId = nextCommandId()
    val start = studio.network.inspection.NetworkInspectorProtocol.StartInspectionCommand
        .newBuilder()
        .build()
    val command = studio.network.inspection.NetworkInspectorProtocol.Command
        .newBuilder()
        .setStartInspectionCommand(start)
        .build()
    val raw = AppInspection.RawCommand.newBuilder()
        .setContent(command.toByteString())
        .build()
    val appInspection = AppInspection.AppInspectionCommand.newBuilder()
        .setInspectorId(NETWORK_INSPECTOR_ID)
        .setCommandId(commandId)
        .setRawInspectorCommand(raw)
        .build()
    return Commands.Command.newBuilder()
        .setStreamId(streamId)
        .setPid(pid)
        .setType(Commands.Command.CommandType.APP_INSPECTION)
        .setCommandId(commandId)
        .setAppInspectionCommand(appInspection)
        .build()
}
