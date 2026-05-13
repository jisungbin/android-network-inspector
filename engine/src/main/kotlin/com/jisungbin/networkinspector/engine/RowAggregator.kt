package com.jisungbin.networkinspector.engine

import studio.network.inspection.NetworkInspectorProtocol as NIP

class RowAggregator {
    private val rows = LinkedHashMap<Long, NetworkRow>()

    val snapshot: List<NetworkRow> get() = rows.values.toList()

    fun reset() {
        rows.clear()
    }

    fun consume(event: NIP.Event): NetworkRow? {
        if (event.unionCase != NIP.Event.UnionCase.HTTP_CONNECTION_EVENT) return null
        val h = event.httpConnectionEvent
        val id = h.connectionId
        val existing = rows[id] ?: NetworkRow(connectionId = id, startTimestamp = event.timestamp)
        val next = when (h.unionCase) {
            NIP.HttpConnectionEvent.UnionCase.HTTP_REQUEST_STARTED -> {
                val r = h.httpRequestStarted
                existing.copy(
                    method = r.method,
                    url = r.url,
                    protocol = r.transport.toRowProtocol(),
                    requestHeaders = r.headersList.map { it.key to it.valuesList.toList() },
                    startTimestamp = event.timestamp,
                )
            }
            NIP.HttpConnectionEvent.UnionCase.HTTP_RESPONSE_STARTED -> {
                val r = h.httpResponseStarted
                existing.copy(
                    statusCode = r.responseCode.takeIf { it > 0 },
                    responseHeaders = r.headersList.map { it.key to it.valuesList.toList() },
                )
            }
            NIP.HttpConnectionEvent.UnionCase.REQUEST_PAYLOAD ->
                existing.copy(requestBody = h.requestPayload.payload.toByteArray())
            NIP.HttpConnectionEvent.UnionCase.RESPONSE_PAYLOAD ->
                existing.copy(responseBody = h.responsePayload.payload.toByteArray())
            NIP.HttpConnectionEvent.UnionCase.HTTP_CLOSED -> existing.copy(
                endTimestamp = event.timestamp,
                state = if (h.httpClosed.completed) ConnectionState.COMPLETED else ConnectionState.FAILED,
            )
            else -> existing
        }
        val stamped = next.copy(lastUpdatedAtMs = System.currentTimeMillis())
        rows[id] = stamped
        return stamped
    }

    private fun NIP.HttpConnectionEvent.HttpTransport.toRowProtocol(): TransportProtocol = when (this) {
        NIP.HttpConnectionEvent.HttpTransport.JAVA_NET -> TransportProtocol.JAVA_NET
        NIP.HttpConnectionEvent.HttpTransport.OKHTTP2 -> TransportProtocol.OKHTTP2
        NIP.HttpConnectionEvent.HttpTransport.OKHTTP3 -> TransportProtocol.OKHTTP3
        else -> TransportProtocol.UNKNOWN
    }
}
