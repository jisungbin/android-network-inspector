package com.jisungbin.networkinspector.intercept

import com.android.tools.idea.protobuf.ByteString
import com.jisungbin.networkinspector.deploy.AttachSession
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
    fun apply(session: AttachSession, rules: List<HostRule>) {
        rules.forEachIndexed { index, rule ->
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
            val ruleProto = NIP.InterceptRule.newBuilder()
                .setEnabled(rule.enabled)
                .setCriteria(criteriaBuilder.build())
                .addAllTransformation(transformations)
                .build()
            val added = NIP.InterceptRuleAdded.newBuilder()
                .setRuleId(index + 1)
                .setRule(ruleProto)
                .build()
            val command = NIP.Command.newBuilder()
                .setInterceptCommand(NIP.InterceptCommand.newBuilder().setInterceptRuleAdded(added))
                .build()
            session.sendRaw(command)
        }
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
