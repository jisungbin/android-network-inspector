package com.jisungbin.networkinspector.protocol

import com.android.tools.app.inspection.AppInspection
import com.android.tools.idea.protobuf.ByteString
import com.android.tools.profiler.proto.Commands
import studio.network.inspection.NetworkInspectorProtocol as NIP

data class HostRule(
    val id: String,
    val urlPattern: String,
    val method: String = "ANY",
    val replacementStatus: Int,
    val replacementContentType: String = "",
    val replacementBody: String,
    val addedHeaders: List<Pair<String, String>> = emptyList(),
    val enabled: Boolean,
)

object RuleSender {
    fun sendAdd(client: TransportClient, pid: Int, streamId: Long, ruleId: Int, rule: HostRule) {
        val added = NIP.InterceptRuleAdded.newBuilder()
            .setRuleId(ruleId)
            .setRule(buildRuleProto(rule))
            .build()
        execute(
            client, pid, streamId,
            NIP.InterceptCommand.newBuilder().setInterceptRuleAdded(added).build(),
        )
    }

    fun sendUpdate(client: TransportClient, pid: Int, streamId: Long, ruleId: Int, rule: HostRule) {
        val updated = NIP.InterceptRuleUpdated.newBuilder()
            .setRuleId(ruleId)
            .setRule(buildRuleProto(rule))
            .build()
        execute(
            client, pid, streamId,
            NIP.InterceptCommand.newBuilder().setInterceptRuleUpdated(updated).build(),
        )
    }

    fun sendRemove(client: TransportClient, pid: Int, streamId: Long, ruleId: Int) {
        val removed = NIP.InterceptRuleRemoved.newBuilder()
            .setRuleId(ruleId)
            .build()
        execute(
            client, pid, streamId,
            NIP.InterceptCommand.newBuilder().setInterceptRuleRemoved(removed).build(),
        )
    }

    private fun buildRuleProto(rule: HostRule): NIP.InterceptRule {
        val criteriaBuilder = NIP.InterceptCriteria.newBuilder()
            .setHost(parseHost(rule.urlPattern))
            .setPath(parsePath(rule.urlPattern))
        parseMethod(rule.method)?.let { criteriaBuilder.method = it }

        val transformations = buildList {
            add(
                NIP.Transformation.newBuilder()
                    .setStatusCodeReplaced(
                        NIP.Transformation.StatusCodeReplaced.newBuilder()
                            .setNewCode(rule.replacementStatus.toString())
                    )
                    .build()
            )
            if (rule.replacementBody.isNotEmpty()) {
                add(
                    NIP.Transformation.newBuilder()
                        .setBodyReplaced(
                            NIP.Transformation.BodyReplaced.newBuilder()
                                .setBody(ByteString.copyFromUtf8(rule.replacementBody))
                        )
                        .build()
                )
            }
            if (rule.replacementContentType.isNotEmpty()) {
                add(
                    NIP.Transformation.newBuilder()
                        .setHeaderAdded(
                            NIP.Transformation.HeaderAdded.newBuilder()
                                .setName("Content-Type")
                                .setValue(rule.replacementContentType)
                        )
                        .build()
                )
            }
            rule.addedHeaders.forEach { (key, value) ->
                if (key.isBlank()) return@forEach
                add(
                    NIP.Transformation.newBuilder()
                        .setHeaderAdded(
                            NIP.Transformation.HeaderAdded.newBuilder()
                                .setName(key)
                                .setValue(value)
                        )
                        .build()
                )
            }
        }
        return NIP.InterceptRule.newBuilder()
            .setEnabled(rule.enabled)
            .setCriteria(criteriaBuilder.build())
            .addAllTransformation(transformations)
            .build()
    }

    private fun execute(client: TransportClient, pid: Int, streamId: Long, interceptCommand: NIP.InterceptCommand) {
        val nipCommand = NIP.Command.newBuilder()
            .setInterceptCommand(interceptCommand)
            .build()
        val appInspection = AppInspection.AppInspectionCommand.newBuilder()
            .setInspectorId(NETWORK_INSPECTOR_ID)
            .setRawInspectorCommand(
                AppInspection.RawCommand.newBuilder().setContent(nipCommand.toByteString())
            )
            .build()
        val full = Commands.Command.newBuilder()
            .setStreamId(streamId)
            .setPid(pid)
            .setType(Commands.Command.CommandType.APP_INSPECTION)
            .setAppInspectionCommand(appInspection)
            .build()
        client.execute(full)
    }

    private fun parseHost(pattern: String): String {
        val noScheme = pattern.removePrefix("https://").removePrefix("http://")
        return noScheme.substringBefore("/").substringBefore("?").ifBlank { "" }
    }

    private fun parsePath(pattern: String): String {
        val noScheme = pattern.removePrefix("https://").removePrefix("http://")
        val raw = noScheme.substringAfter("/", "").substringBefore("?")
        return if (raw.isBlank()) "" else "/$raw"
    }

    private fun parseMethod(name: String): NIP.InterceptCriteria.Method? = when (name.uppercase()) {
        "ANY", "", "*" -> null
        "GET" -> NIP.InterceptCriteria.Method.METHOD_GET
        "POST" -> NIP.InterceptCriteria.Method.METHOD_POST
        "HEAD" -> NIP.InterceptCriteria.Method.METHOD_HEAD
        "PUT" -> NIP.InterceptCriteria.Method.METHOD_PUT
        "DELETE" -> NIP.InterceptCriteria.Method.METHOD_DELETE
        "TRACE" -> NIP.InterceptCriteria.Method.METHOD_TRACE
        "CONNECT" -> NIP.InterceptCriteria.Method.METHOD_CONNECT
        "PATCH" -> NIP.InterceptCriteria.Method.METHOD_PATCH
        "OPTIONS" -> NIP.InterceptCriteria.Method.METHOD_OPTIONS
        else -> null
    }
}
