package com.jisungbin.networkinspector.protocol

import com.android.tools.idea.io.grpc.ManagedChannel
import com.android.tools.idea.io.grpc.ManagedChannelBuilder
import com.android.tools.profiler.proto.Commands
import com.android.tools.profiler.proto.Common
import com.android.tools.profiler.proto.Transport
import com.android.tools.profiler.proto.TransportServiceGrpc
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import java.util.concurrent.TimeUnit

class TransportClient(host: String, port: Int) : AutoCloseable {
    private val channel: ManagedChannel = ManagedChannelBuilder
        .forAddress(host, port)
        .usePlaintext()
        .maxInboundMessageSize(MAX_MESSAGE_SIZE)
        .build()
    private val blockingStub = TransportServiceGrpc.newBlockingStub(channel)

    fun execute(command: Commands.Command): Transport.ExecuteResponse =
        blockingStub.execute(
            Transport.ExecuteRequest.newBuilder().setCommand(command).build()
        )

    fun events(): Flow<Common.Event> = flow {
        val iterator = blockingStub.getEvents(
            Transport.GetEventsRequest.newBuilder().build()
        )
        while (iterator.hasNext()) {
            emit(iterator.next())
        }
    }.flowOn(Dispatchers.IO)

    override fun close() {
        channel.shutdown()
        runCatching { channel.awaitTermination(5, TimeUnit.SECONDS) }
    }

    companion object {
        private const val MAX_MESSAGE_SIZE = 512 * 1024 * 1024
    }
}
