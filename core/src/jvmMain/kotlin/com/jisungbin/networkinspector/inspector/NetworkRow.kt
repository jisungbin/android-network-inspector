package com.jisungbin.networkinspector.inspector

data class NetworkRow(
    val connectionId: Long,
    val startTimestamp: Long,
    val endTimestamp: Long? = null,
    val method: String = "",
    val url: String = "",
    val protocol: TransportProtocol = TransportProtocol.UNKNOWN,
    val statusCode: Int? = null,
    val requestHeaders: List<Pair<String, List<String>>> = emptyList(),
    val responseHeaders: List<Pair<String, List<String>>> = emptyList(),
    val requestBody: ByteArray? = null,
    val responseBody: ByteArray? = null,
    val state: ConnectionState = ConnectionState.IN_FLIGHT,
)

enum class TransportProtocol { JAVA_NET, OKHTTP2, OKHTTP3, UNKNOWN }
enum class ConnectionState { IN_FLIGHT, COMPLETED, FAILED }
