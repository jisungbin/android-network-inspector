package com.jisungbin.networkinspector.intercept

import com.android.tools.idea.protobuf.ByteString
import com.jisungbin.networkinspector.deploy.AttachSession
import studio.network.inspection.NetworkInspectorProtocol as NIP

data class HostRule(
    val id: String,
    val urlPattern: String,
    val replacementStatus: Int,
    val replacementBody: String,
    val enabled: Boolean,
)

object RuleSender {
    fun apply(session: AttachSession, rules: List<HostRule>) {
        rules.forEachIndexed { index, rule ->
            val criteria = NIP.InterceptCriteria.newBuilder()
                .setHost(parseHost(rule.urlPattern))
                .setPath(parsePath(rule.urlPattern))
                .build()
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
            }
            val ruleProto = NIP.InterceptRule.newBuilder()
                .setEnabled(rule.enabled)
                .setCriteria(criteria)
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
}
