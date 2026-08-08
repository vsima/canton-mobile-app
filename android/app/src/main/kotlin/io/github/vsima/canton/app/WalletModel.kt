// Copyright (c) 2026 Victor Sima
// SPDX-License-Identifier: Apache-2.0

package io.github.vsima.canton.app

import android.util.Base64
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.github.vsima.canton.wallet.AllocatedExternalParty
import io.github.vsima.canton.wallet.ExternalPartyClient
import io.github.vsima.canton.wallet.Holding
import io.github.vsima.canton.wallet.ScanClient
import io.github.vsima.canton.wallet.SigningDriver
import io.github.vsima.canton.wallet.TokenStandardClient
import io.github.vsima.canton.wallet.TransferInstruction
import io.github.vsima.canton.wallet.TransferInstructionChoice
import io.github.vsima.canton.wallet.TransferInstructionStatus
import io.github.vsima.canton.wallet.TransferRegistryClient
import io.github.vsima.canton.wallet.android.AndroidKeystoreSigningDriver
import io.grpc.CallOptions
import io.grpc.Channel
import io.grpc.ClientCall
import io.grpc.ClientInterceptor
import io.grpc.ClientInterceptors
import io.grpc.ForwardingClientCall
import io.grpc.ManagedChannel
import io.grpc.Metadata
import io.grpc.MethodDescriptor
import io.grpc.okhttp.OkHttpChannelBuilder
import java.math.BigDecimal
import java.net.InetAddress
import kotlinx.coroutines.launch
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import okhttp3.Dns
import okhttp3.OkHttpClient

/**
 * LocalNet as seen from the Android emulator: 10.0.2.2 is the host's
 * loopback, and every `*.localhost` vhost is mapped there via OkHttp's DNS
 * hook so nginx still routes by Host header. Dev-only unsafe JWT, like the
 * iOS twin.
 */
object WalletEnvironment {
    const val name = "LocalNet"

    /** 10.0.2.2 on the emulator; 127.0.0.1 on physical devices with
     *  `adb reverse` tunnels (set via the MainActivity `host` extra). */
    var hostBridge = "10.0.2.2"
    const val ledgerPort = 2901
    const val registryUrl = "http://scan.localhost:4000"
    const val scanUrl = "http://scan.localhost:4000/api/scan"
    const val validatorUrl = "http://wallet.localhost:2000/api/validator"
    const val userId = "ledger-api-user"
    const val walletUser = "app-user"
    private const val audience = "https://canton.network.global"

    val http: OkHttpClient = OkHttpClient.Builder()
        .dns(object : Dns {
            override fun lookup(hostname: String): List<InetAddress> =
                if (hostname.endsWith(".localhost")) listOf(InetAddress.getByName(hostBridge))
                else Dns.SYSTEM.lookup(hostname)
        })
        .build()

    fun unsafeJwt(sub: String): String {
        fun b64(bytes: ByteArray) =
            Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
        val header = b64("""{"alg":"HS256","typ":"JWT"}""".toByteArray())
        val payload = b64("""{"sub":"$sub","aud":"$audience"}""".toByteArray())
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec("unsafe".toByteArray(), "HmacSHA256"))
        return "$header.$payload.${b64(mac.doFinal("$header.$payload".toByteArray()))}"
    }

    fun channel(): ManagedChannel =
        OkHttpChannelBuilder.forAddress(hostBridge, ledgerPort).usePlaintext().build()

    fun authed(channel: Channel, sub: String): Channel {
        val token = unsafeJwt(sub)
        return ClientInterceptors.intercept(channel, object : ClientInterceptor {
            override fun <ReqT, RespT> interceptCall(
                method: MethodDescriptor<ReqT, RespT>,
                callOptions: CallOptions,
                next: Channel,
            ): ClientCall<ReqT, RespT> =
                object : ForwardingClientCall.SimpleForwardingClientCall<ReqT, RespT>(
                    next.newCall(method, callOptions)
                ) {
                    override fun start(listener: Listener<RespT>, headers: Metadata) {
                        headers.put(
                            Metadata.Key.of("authorization", Metadata.ASCII_STRING_MARSHALLER),
                            "Bearer $token",
                        )
                        super.start(listener, headers)
                    }
                }
        })
    }
}

/** Mirror of the iOS WalletModel, on Compose state. `WALLET:` logcat lines
 *  drive the same headless verification loop. */
class WalletModel(private val prefs: android.content.SharedPreferences) {
    /** Operations outlive the composables that trigger them: a tapped
     *  Accept must not die because its row left the screen. */
    private val scope = kotlinx.coroutines.CoroutineScope(
        kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Main.immediate
    )
    sealed interface Phase {
        data object Fresh : Phase
        data object Onboarding : Phase
        data object Ready : Phase
        data class Failed(val message: String) : Phase
    }

    var phase by mutableStateOf<Phase>(Phase.Fresh)
        private set
    var partyId by mutableStateOf<String?>(null)
        private set
    var signerLabel by mutableStateOf("")
        private set
    var holdings by mutableStateOf<List<Holding>>(emptyList())
        private set
    var inbox by mutableStateOf<List<TransferInstruction>>(emptyList())
        private set
    var history by mutableStateOf<List<TokenStandardClient.HoldingsChange>>(emptyList())
        private set
    var lastError by mutableStateOf<String?>(null)
        private set
    var busy by mutableStateOf(false)
    /** Contract ids with an in-flight accept/reject, so only the tapped
     *  row's buttons disable — not the whole inbox. */
    var processing by mutableStateOf(setOf<String>())
    /** True when the signing key is hardware-resident (StrongBox or TEE) —
     *  drives the trust copy, which must never overclaim. */
    var hardwareSigner by mutableStateOf(false)
        private set
    var lastSend by mutableStateOf<SendReceipt?>(null)
    var preapproval by mutableStateOf<ScanClient.TransferPreapprovalInfo?>(null)
        private set
    var preapprovalRequested by mutableStateOf(false)
    /** Scan lags the ledger briefly after a cancel; skip re-reading the
     *  preapproval until then so the switch doesn't bounce back on. */
    private var preapprovalSuppressedUntil: java.time.Instant = java.time.Instant.EPOCH
        private set

    data class SendReceipt(
        val amount: BigDecimal,
        val receiver: String,
        val memo: String,
        val at: java.time.Instant = java.time.Instant.now(),
    )

    private var channel: ManagedChannel? = null
    private var authedChannel: Channel? = null
    private var driver: SigningDriver? = null
    private var allocated: AllocatedExternalParty? = null
    private var synchronizerId: String? = null

    val totalAmulet: BigDecimal
        get() = holdings.fold(BigDecimal.ZERO) { total, holding -> total + holding.amount }

    suspend fun onboard() {
        if (phase != Phase.Fresh) return
        phase = Phase.Onboarding
        try {
            val plain = WalletEnvironment.channel()
            channel = plain
            val authed = WalletEnvironment.authed(plain, WalletEnvironment.userId)
            authedChannel = authed

            val restoredKey = AndroidKeystoreSigningDriver.load("wallet")
            val savedParty = prefs.getString("partyId", null)
            val savedFingerprint = prefs.getString("fingerprint", null)
            val savedSynchronizer = prefs.getString("synchronizerId", null)

            // A restored party is only usable with the key it was allocated
            // under. If the keystore entry is gone while the party record
            // survives, fail loudly instead of pairing the party with a
            // fresh key that can never sign for it.
            if (savedParty != null && restoredKey == null) {
                phase = Phase.Failed(
                    "The signing key for $savedParty is no longer in the keystore, " +
                        "so this party can't sign. Clear the app's data to start a fresh wallet."
                )
                return
            }

            val keystore = restoredKey ?: AndroidKeystoreSigningDriver.generate("wallet")
            driver = keystore
            signerLabel = when (keystore.securityLevel) {
                AndroidKeystoreSigningDriver.SecurityLevel.STRONGBOX -> "StrongBox secure element"
                AndroidKeystoreSigningDriver.SecurityLevel.TRUSTED_ENVIRONMENT -> "Hardware keystore (TEE)"
                else -> "Software keystore (emulator)"
            }
            hardwareSigner = when (keystore.securityLevel) {
                AndroidKeystoreSigningDriver.SecurityLevel.STRONGBOX,
                AndroidKeystoreSigningDriver.SecurityLevel.TRUSTED_ENVIRONMENT -> true
                else -> false
            }

            // The key lives in the keystore; the party record must persist
            // beside it or a relaunch re-allocates the same party and the
            // participant rightly refuses ("already exists").
            if (savedParty != null && savedFingerprint != null && savedSynchronizer != null) {
                partyId = savedParty
                allocated = AllocatedExternalParty(savedParty, savedFingerprint)
                synchronizerId = savedSynchronizer
                phase = Phase.Ready
                Log.i("WALLET", "restored $savedParty signer=$signerLabel")
                refresh()
                return
            }

            val parties = ExternalPartyClient(authed)
            val synchronizer = parties.connectedSynchronizers().firstOrNull()
                ?: error("no synchronizer reachable at ${WalletEnvironment.hostBridge}")
            synchronizerId = synchronizer
            val party = parties.allocate(keystore, synchronizer, "droidwallet", WalletEnvironment.userId)
            partyId = party.partyId
            allocated = party
            prefs.edit()
                .putString("partyId", party.partyId)
                .putString("fingerprint", party.publicKeyFingerprint)
                .putString("synchronizerId", synchronizer)
                .apply()
            phase = Phase.Ready
            Log.i("WALLET", "onboarded ${party.partyId} signer=$signerLabel")
            refresh()
        } catch (error: Exception) {
            phase = Phase.Failed(error.toString())
            Log.i("WALLET", "onboarding failed: $error")
        }
    }

    suspend fun refresh() {
        val authed = authedChannel ?: return
        val party = partyId ?: return
        try {
            val tokens = tokens(authed)
            holdings = tokens.listHoldings(party)
            inbox = tokens.pendingTransferInstructions(party).filter {
                it.status == TransferInstructionStatus.PendingReceiverAcceptance &&
                    it.transfer.receiver == party
            }
            history = tokens.holdingsHistory(party).reversed()
            if (java.time.Instant.now() > preapprovalSuppressedUntil) {
                preapproval = runCatching {
                    ScanClient(WalletEnvironment.scanUrl, WalletEnvironment.http)
                        .transferPreapprovalByParty(party)
                }.getOrNull()
            }
            lastError = null
            Log.i("WALLET", "holdings=$totalAmulet inbox=${inbox.size} history=${history.size}")
        } catch (error: Exception) {
            lastError = error.toString()
            Log.i("WALLET", "refresh failed: $error")
        }
    }

    fun accept(instruction: TransferInstruction) {
        scope.launch { exercise(instruction, TransferInstructionChoice.ACCEPT) }
    }

    fun reject(instruction: TransferInstruction) {
        scope.launch { exercise(instruction, TransferInstructionChoice.REJECT) }
    }

    fun sendAsync(receiver: String, amount: BigDecimal, memo: String) {
        scope.launch { send(receiver, amount, memo) }
    }

    fun requestInstantReceiveAsync() {
        scope.launch { requestInstantReceive() }
    }

    fun cancelInstantReceiveAsync() {
        scope.launch { cancelInstantReceive() }
    }

    /** Turns instant receiving off — archives the preapproval, signed
     *  on-device (the receiver may cancel unilaterally). */
    suspend fun cancelInstantReceive() {
        val authed = authedChannel ?: return
        val driver = driver ?: return
        val party = allocated ?: return
        val synchronizer = synchronizerId ?: return
        val cid = preapproval?.contractId ?: return
        busy = true
        try {
            tokens(authed).cancelTransferPreapproval(
                driver = driver,
                party = party,
                preapprovalCid = cid,
                synchronizerId = synchronizer,
                userId = WalletEnvironment.userId,
            )
            preapproval = null
            preapprovalRequested = false
            preapprovalSuppressedUntil = java.time.Instant.now().plusSeconds(30)
            Log.i("WALLET", "preapproval cancelled")
            refresh()
        } catch (error: Exception) {
            lastError = error.toString()
            Log.i("WALLET", "preapproval cancel failed: $error")
        } finally {
            busy = false
        }
    }

    suspend fun send(receiver: String, amount: BigDecimal, memo: String) {
        val authed = authedChannel ?: return
        val driver = driver ?: return
        val party = allocated ?: return
        val synchronizer = synchronizerId ?: return
        busy = true
        try {
            val inputs = holdings.filter { it.lock == null }
            val instrument = inputs.firstOrNull()?.instrumentId ?: error("nothing to send")
            tokens(authed).createTransfer(
                driver = driver,
                party = party,
                receiver = receiver,
                instrumentId = instrument,
                amount = amount,
                inputHoldingCids = inputs.map { it.contractId },
                synchronizerId = synchronizer,
                userId = WalletEnvironment.userId,
                meta = if (memo.isBlank()) emptyMap() else mapOf(MEMO_KEY to memo),
            )
            lastSend = SendReceipt(amount, receiver, memo)
            Log.i("WALLET", "sent $amount to ${receiver.take(24)}…")
            refresh()
        } catch (error: Exception) {
            lastError = error.toString()
            Log.i("WALLET", "send failed: $error")
        } finally {
            busy = false
        }
    }

    private suspend fun exercise(instruction: TransferInstruction, choice: TransferInstructionChoice) {
        val authed = authedChannel ?: return
        val driver = driver ?: return
        val party = allocated ?: return
        val synchronizer = synchronizerId ?: return
        processing = processing + instruction.contractId
        try {
            tokens(authed).exerciseTransferInstruction(
                driver = driver,
                party = party,
                transferInstructionId = instruction.contractId,
                choice = choice,
                synchronizerId = synchronizer,
                userId = WalletEnvironment.userId,
            )
            Log.i("WALLET", "$choice ${instruction.contractId.take(20)}…")
            refresh()
        } catch (error: Exception) {
            lastError = error.toString()
            Log.i("WALLET", "$choice failed: $error")
        } finally {
            processing = processing - instruction.contractId
        }
    }

    /** "Receive instantly": on-device-signed preapproval request; the
     *  validator operator accepts and pays (LocalNet dev lookup). */
    suspend fun requestInstantReceive() {
        val authed = authedChannel ?: return
        val driver = driver ?: return
        val party = allocated ?: return
        val synchronizer = synchronizerId ?: return
        busy = true
        try {
            val request = okhttp3.Request.Builder()
                .url("${WalletEnvironment.validatorUrl}/v0/validator-user")
                .header("Authorization", "Bearer ${WalletEnvironment.unsafeJwt(WalletEnvironment.walletUser)}")
                .build()
            val provider = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                WalletEnvironment.http.newCall(request).execute().use { response ->
                    val body = response.body?.string()
                        ?: error("validator-user lookup returned no body")
                    val json = kotlinx.serialization.json.Json.parseToJsonElement(body)
                        as? kotlinx.serialization.json.JsonObject
                        ?: error("validator-user lookup failed: $body")
                    (json["party_id"] as? kotlinx.serialization.json.JsonPrimitive)?.content
                        ?: error("validator-user lookup failed: $body")
                }
            }
            val dso = ScanClient(WalletEnvironment.scanUrl, WalletEnvironment.http).dsoPartyId()
            tokens(authed).requestTransferPreapproval(
                driver, party, provider, dso, synchronizer, WalletEnvironment.userId,
            )
            preapprovalRequested = true
            Log.i("WALLET", "preapproval requested")
        } catch (error: Exception) {
            lastError = error.toString()
            Log.i("WALLET", "preapproval request failed: $error")
        } finally {
            busy = false
        }
    }

    private fun tokens(channel: Channel): TokenStandardClient =
        TokenStandardClient(
            channel,
            TransferRegistryClient(WalletEnvironment.registryUrl, WalletEnvironment.http),
        )

    companion object {
        /** Convention key for human-readable transfer memos. */
        const val MEMO_KEY = "splice.lfdecentralizedtrust.org/reason"
    }
}
