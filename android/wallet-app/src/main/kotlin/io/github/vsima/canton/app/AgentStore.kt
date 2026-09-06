// Copyright (c) 2026 Victor Sima
// SPDX-License-Identifier: Apache-2.0

package io.github.vsima.canton.app

import io.github.vsima.canton.dapp.wallet.DappActivity
import io.github.vsima.canton.dapp.wallet.DappSpendPolicy
import io.github.vsima.canton.dapp.wallet.DappTransferSummary
import io.github.vsima.canton.dapp.wallet.SpendLedger
import io.github.vsima.canton.dapp.wallet.SpendReceipt
import java.io.File
import java.math.BigDecimal
import java.time.Instant
import kotlin.time.Duration.Companion.seconds
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put

/** The receipts file could not be read. Never swallowed: an unreadable
 *  ledger read as empty would silently reset every spend cap. */
class AgentStoreUnreadableException(message: String, cause: Throwable? = null) :
    RuntimeException(message, cause)

/**
 * App-private persistence for the agent surface: per-dApp spend policies,
 * the activity feed, and the spend-receipt ledger the SDK's caps read.
 *
 * Files live in the app sandbox (`filesDir`), like the wallet store. The
 * receipts ledger is fail-loud per [SpendLedger]'s contract; the activity
 * feed is display-only, so a corrupt line there is dropped, not fatal.
 * All I/O is synchronous and small; callers keep it off the main thread.
 */
class AgentStore(private val dir: File) {

    private val policiesFile = File(dir, "agent-policies.json")
    private val activityFile = File(dir, "agent-activity.jsonl")
    private val receiptsFile = File(dir, "agent-receipts.jsonl")

    // ── Policies ───────────────────────────────────────────────────────

    /** The stored policy for one peer, or null (no policy: everything asks). */
    @Synchronized
    fun policy(peerId: String): DappSpendPolicy? = policies()[peerId]

    @Synchronized
    fun policies(): Map<String, DappSpendPolicy> {
        if (!policiesFile.exists()) return emptyMap()
        val root = Json.parseToJsonElement(policiesFile.readText()).jsonObject
        return root.mapValues { (_, value) -> policyFromJson(value.jsonObject) }
    }

    @Synchronized
    fun setPolicy(peerId: String, policy: DappSpendPolicy?) {
        val updated = policies().toMutableMap()
        if (policy == null) updated.remove(peerId) else updated[peerId] = policy
        val json = buildJsonObject {
            updated.forEach { (id, p) -> put(id, policyToJson(p)) }
        }
        policiesFile.writeText(json.toString())
    }

    private fun policyToJson(policy: DappSpendPolicy): JsonObject = buildJsonObject {
        policy.maxPerTransaction?.let { put("maxPerTransaction", it.toPlainString()) }
        policy.dailyCap?.let { put("dailyCap", it.toPlainString()) }
        policy.allowedInstruments?.let { set ->
            put("allowedInstruments", set.joinToString(","))
        }
        policy.allowedReceivers?.let { set ->
            put("allowedReceivers", set.joinToString(","))
        }
        if (policy.minRequestInterval.inWholeSeconds > 0) {
            put("minRequestIntervalSecs", policy.minRequestInterval.inWholeSeconds)
        }
        policy.autoApproveBelow?.let { put("autoApproveBelow", it.toPlainString()) }
    }

    private fun policyFromJson(json: JsonObject): DappSpendPolicy = DappSpendPolicy(
        maxPerTransaction = json["maxPerTransaction"]?.jsonPrimitive?.content?.let(::BigDecimal),
        dailyCap = json["dailyCap"]?.jsonPrimitive?.content?.let(::BigDecimal),
        allowedInstruments = json["allowedInstruments"]?.jsonPrimitive?.content
            ?.split(",")?.filter { it.isNotBlank() }?.toSet(),
        allowedReceivers = json["allowedReceivers"]?.jsonPrimitive?.content
            ?.split(",")?.filter { it.isNotBlank() }?.toSet(),
        minRequestInterval = (json["minRequestIntervalSecs"]?.jsonPrimitive?.long ?: 0).seconds,
        autoApproveBelow = json["autoApproveBelow"]?.jsonPrimitive?.content?.let(::BigDecimal),
    )

    // ── Activity feed ──────────────────────────────────────────────────

    /** Newest last. Corrupt lines are skipped: the feed is a record for
     *  display, not an input to any decision. */
    @Synchronized
    fun activity(): List<DappActivity> {
        if (!activityFile.exists()) return emptyList()
        return activityFile.readLines().mapNotNull { line ->
            try {
                activityFromJson(Json.parseToJsonElement(line).jsonObject)
            } catch (_: Exception) {
                null
            }
        }
    }

    @Synchronized
    fun appendActivity(activity: DappActivity) {
        activityFile.appendText(activityToJson(activity).toString() + "\n")
        // Keep the file bounded; the feed is recent history, not an archive.
        val lines = activityFile.readLines()
        if (lines.size > MAX_ACTIVITY_LINES) {
            activityFile.writeText(
                lines.takeLast(MAX_ACTIVITY_LINES).joinToString("\n", postfix = "\n"),
            )
        }
    }

    private fun activityToJson(a: DappActivity): JsonObject = buildJsonObject {
        put("peerId", a.peerId)
        put("peerName", a.peerName)
        put("atMillis", a.at.toEpochMilli())
        put("kind", a.kind.name)
        a.detail?.let { put("detail", it) }
        a.transfer?.let { t ->
            put("transferReceiver", t.receiver)
            put("transferAmount", t.amount)
            put("transferInstrument", t.instrumentId)
            t.memo?.let { put("transferMemo", it) }
        }
    }

    private fun activityFromJson(json: JsonObject): DappActivity = DappActivity(
        peerId = json["peerId"]!!.jsonPrimitive.content,
        peerName = json["peerName"]!!.jsonPrimitive.content,
        at = Instant.ofEpochMilli(json["atMillis"]!!.jsonPrimitive.long),
        kind = DappActivity.Kind.valueOf(json["kind"]!!.jsonPrimitive.content),
        transfer = json["transferAmount"]?.let {
            DappTransferSummary(
                receiver = json["transferReceiver"]!!.jsonPrimitive.content,
                amount = it.jsonPrimitive.content,
                instrumentId = json["transferInstrument"]!!.jsonPrimitive.content,
                memo = json["transferMemo"]?.jsonPrimitive?.content,
            )
        },
        detail = json["detail"]?.jsonPrimitive?.content,
    )

    // ── Receipts (the SDK's cap accounting) ────────────────────────────

    /** The [SpendLedger] the wallet's sessions read caps from and write
     *  executed spends to. Fail-loud on read, per the seam's contract. */
    val receiptsLedger: SpendLedger = object : SpendLedger {
        override suspend fun append(receipt: SpendReceipt) {
            synchronized(this@AgentStore) {
                receiptsFile.appendText(receiptToJson(receipt).toString() + "\n")
            }
        }

        override suspend fun receiptsSince(peerId: String, since: Instant): List<SpendReceipt> {
            synchronized(this@AgentStore) {
                if (!receiptsFile.exists()) return emptyList()
                return try {
                    receiptsFile.readLines().filter { it.isNotBlank() }.map { line ->
                        receiptFromJson(Json.parseToJsonElement(line).jsonObject)
                    }
                } catch (e: Exception) {
                    throw AgentStoreUnreadableException(
                        "the spend-receipt ledger at $receiptsFile is unreadable; " +
                            "refusing to treat it as empty",
                        e,
                    )
                }.filter { it.peerId == peerId && !it.at.isBefore(since) }
            }
        }
    }

    private fun receiptToJson(r: SpendReceipt): JsonObject = buildJsonObject {
        put("peerId", r.peerId)
        put("atMillis", r.at.toEpochMilli())
        put("instrumentId", r.instrumentId)
        put("amount", r.amount.toPlainString())
        put("receiver", r.receiver)
        put("autoApproved", r.autoApproved)
        put("commandId", r.commandId)
    }

    private fun receiptFromJson(json: JsonObject): SpendReceipt = SpendReceipt(
        peerId = json["peerId"]!!.jsonPrimitive.content,
        at = Instant.ofEpochMilli(json["atMillis"]!!.jsonPrimitive.long),
        instrumentId = json["instrumentId"]!!.jsonPrimitive.content,
        amount = BigDecimal(json["amount"]!!.jsonPrimitive.content),
        receiver = json["receiver"]!!.jsonPrimitive.content,
        autoApproved = json["autoApproved"]!!.jsonPrimitive.boolean,
        commandId = json["commandId"]!!.jsonPrimitive.content,
    )

    private companion object {
        const val MAX_ACTIVITY_LINES = 300
    }
}
