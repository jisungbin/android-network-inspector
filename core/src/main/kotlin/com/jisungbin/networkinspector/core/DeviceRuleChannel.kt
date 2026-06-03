package com.jisungbin.networkinspector.core

import com.jisungbin.networkinspector.engine.AttachSession
import com.jisungbin.networkinspector.protocol.HostRule
import com.jisungbin.networkinspector.protocol.RuleSender
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch

/**
 * Per-device channel for intercept rules: maps each [InterceptRule.id] to a stream-scoped protocol
 * id and pushes add/update/remove/reset over the inspector protocol. Created by [SessionController]
 * when a device attaches and driven by [InterceptRuleController].
 *
 * All wire sends go through one FIFO queue consumed by a single coroutine, so id-map mutations
 * (@Synchronized) and the sends they enqueue keep the same order — a per-device serial channel that
 * prevents e.g. an update overtaking the remove of the same rule. Every send first awaits the
 * learned streamId, so rules enqueued right after attach are not sent with streamId=0 (before the
 * STREAM event is observed).
 */
internal class DeviceRuleChannel(private val session: AttachSession, scope: CoroutineScope) {
    private var nextProtocolRuleId = 1
    private val ruleIdMap = mutableMapOf<String, Int>()
    private val sends = Channel<suspend () -> Unit>(Channel.UNLIMITED)

    init {
        scope.launch(Dispatchers.IO) {
            for (send in sends) send()
        }
    }

    @Synchronized
    fun pushInitial(rules: List<InterceptRule>) {
        val mapped = rules.map { assignId(it) to it }
        enqueue { mapped.forEach { (protocolId, rule) -> add(protocolId, rule) } }
    }

    @Synchronized
    fun upsert(rule: InterceptRule) {
        val existing = ruleIdMap[rule.id]
        if (existing == null) {
            val protocolId = assignId(rule)
            enqueue { add(protocolId, rule) }
        } else {
            enqueue { RuleSender.sendUpdate(session.client, session.pid, session.awaitStreamId(), existing, rule.toHostRule()) }
        }
    }

    @Synchronized
    fun remove(id: String) {
        val protocolId = ruleIdMap.remove(id) ?: return
        enqueue { RuleSender.sendRemove(session.client, session.pid, session.awaitStreamId(), protocolId) }
    }

    /** Drops every existing mapping and re-pushes [rules] fresh (rule import). */
    @Synchronized
    fun reset(rules: List<InterceptRule>) {
        val old = ruleIdMap.values.toList()
        ruleIdMap.clear()
        val mapped = rules.map { assignId(it) to it }
        enqueue {
            old.forEach { RuleSender.sendRemove(session.client, session.pid, session.awaitStreamId(), it) }
            mapped.forEach { (protocolId, rule) -> add(protocolId, rule) }
        }
    }

    /** Stops the send consumer; called by [SessionController] on detach to avoid leaking it. */
    fun close() {
        sends.close()
    }

    private fun assignId(rule: InterceptRule): Int {
        val protocolId = nextProtocolRuleId++
        ruleIdMap[rule.id] = protocolId
        return protocolId
    }

    private suspend fun add(protocolId: Int, rule: InterceptRule) {
        RuleSender.sendAdd(session.client, session.pid, session.awaitStreamId(), protocolId, rule.toHostRule())
    }

    private fun enqueue(send: suspend () -> Unit) {
        sends.trySend(send)
    }
}

private fun InterceptRule.toHostRule(): HostRule = HostRule(
    id = id,
    urlPattern = urlPattern,
    method = method,
    replacementStatus = replacementStatus,
    replacementContentType = replacementContentType,
    replacementBody = replacementBody,
    addedHeaders = addedHeaders,
    enabled = enabled,
)
