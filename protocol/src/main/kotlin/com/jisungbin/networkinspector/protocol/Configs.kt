package com.jisungbin.networkinspector.protocol

import com.android.tools.profiler.proto.Agent
import com.android.tools.profiler.proto.Common
import com.android.tools.profiler.proto.Transport

const val TRANSPORT_SOCKET_NAME = "AndroidStudioTransport"
const val TRANSPORT_FALLBACK_TCP = "127.0.0.1:12389"

object Configs {
    fun common(): Common.CommonConfig =
        Common.CommonConfig.newBuilder()
            .setSocketType(Common.CommonConfig.SocketType.ABSTRACT_SOCKET)
            .setServiceAddress(TRANSPORT_FALLBACK_TCP)
            .setServiceSocketName(TRANSPORT_SOCKET_NAME)
            .build()

    fun daemon(): Transport.DaemonConfig =
        Transport.DaemonConfig.newBuilder()
            .setCommon(common())
            .build()

    fun agent(): Agent.AgentConfig =
        Agent.AgentConfig.newBuilder()
            .setCommon(common())
            .build()
}
