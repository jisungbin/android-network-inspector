package com.jisungbin.networkinspector.engine

import com.android.tools.app.inspection.AppInspection
import com.android.tools.profiler.proto.Common
import com.jisungbin.networkinspector.protocol.NETWORK_INSPECTOR_ID
import studio.network.inspection.NetworkInspectorProtocol

object NetworkEventRenderer {
    fun render(event: Common.Event): String? = when (event.kind) {
        Common.Event.Kind.APP_INSPECTION_RESPONSE -> {
            val r = event.appInspectionResponse
            "[ai-response] commandId=${r.commandId} status=${r.status}"
        }
        Common.Event.Kind.APP_INSPECTION_EVENT -> renderAi(event.appInspectionEvent, event.timestamp)
        Common.Event.Kind.PROCESS -> "[process] pid=${event.pid} groupId=${event.groupId}"
        Common.Event.Kind.STREAM -> "[stream] groupId=${event.groupId}"
        Common.Event.Kind.AGENT -> "[agent] pid=${event.pid} status=${event.agentData.status}"
        else -> null
    }

    private fun renderAi(ai: AppInspection.AppInspectionEvent, ts: Long): String? {
        if (ai.inspectorId != NETWORK_INSPECTOR_ID) return null
        return when (ai.unionCase) {
            AppInspection.AppInspectionEvent.UnionCase.RAW_EVENT -> {
                val raw = ai.rawEvent
                when (raw.dataCase) {
                    AppInspection.RawEvent.DataCase.CONTENT -> {
                        val nie = NetworkInspectorProtocol.Event.parseFrom(raw.content)
                        renderNetworkEvent(nie)
                    }
                    AppInspection.RawEvent.DataCase.PAYLOAD_ID ->
                        "[network-payload-id=${raw.payloadId}]"
                    else -> null
                }
            }
            AppInspection.AppInspectionEvent.UnionCase.DISPOSED_EVENT ->
                "[network-disposed] error=${ai.disposedEvent.errorMessage}"
            else -> null
        }
    }

    private fun renderNetworkEvent(e: NetworkInspectorProtocol.Event): String = when (e.unionCase) {
        NetworkInspectorProtocol.Event.UnionCase.HTTP_CONNECTION_EVENT -> renderHttp(e.httpConnectionEvent, e.timestamp)
        NetworkInspectorProtocol.Event.UnionCase.SPEED_EVENT ->
            "[speed] ts=${e.timestamp} rx=${e.speedEvent.rxSpeed} tx=${e.speedEvent.txSpeed}"
        NetworkInspectorProtocol.Event.UnionCase.GRPC_EVENT ->
            "[grpc] ts=${e.timestamp} ${e.grpcEvent.unionCase}"
        else -> "[other-network] ts=${e.timestamp} ${e.unionCase}"
    }

    private fun renderHttp(h: NetworkInspectorProtocol.HttpConnectionEvent, ts: Long): String {
        val id = h.connectionId
        return when (h.unionCase) {
            NetworkInspectorProtocol.HttpConnectionEvent.UnionCase.HTTP_REQUEST_STARTED -> {
                val r = h.httpRequestStarted
                "[http-req-start] id=$id ts=$ts ${r.method} ${r.url} ${r.transport}"
            }
            NetworkInspectorProtocol.HttpConnectionEvent.UnionCase.HTTP_REQUEST_COMPLETED ->
                "[http-req-done] id=$id ts=$ts"
            NetworkInspectorProtocol.HttpConnectionEvent.UnionCase.HTTP_RESPONSE_STARTED ->
                "[http-res-start] id=$id ts=$ts status=${h.httpResponseStarted.responseCode}"
            NetworkInspectorProtocol.HttpConnectionEvent.UnionCase.HTTP_RESPONSE_COMPLETED ->
                "[http-res-done] id=$id ts=$ts"
            NetworkInspectorProtocol.HttpConnectionEvent.UnionCase.REQUEST_PAYLOAD ->
                "[http-req-body] id=$id ts=$ts size=${h.requestPayload.payload.size()}"
            NetworkInspectorProtocol.HttpConnectionEvent.UnionCase.RESPONSE_PAYLOAD ->
                "[http-res-body] id=$id ts=$ts size=${h.responsePayload.payload.size()}"
            NetworkInspectorProtocol.HttpConnectionEvent.UnionCase.HTTP_CLOSED ->
                "[http-closed] id=$id ts=$ts completed=${h.httpClosed.completed}"
            else -> "[http-${h.unionCase}] id=$id ts=$ts"
        }
    }
}
