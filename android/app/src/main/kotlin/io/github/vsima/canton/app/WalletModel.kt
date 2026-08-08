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
        private set
    var lastSend by mutableStateOf<SendReceipt?>(null)
    var preapproval by mutableStateOf<ScanClient.TransferPreapprovalInfo?>(null)
        private set
    var preapprovalRequested by mutableStateOf(false)
        private set

    data class SendReceipt(val amount: BigDecimal, val receiver: String, val memo: String)

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

            val keystore = AndroidKeystoreSigningDriver.load("wallet")
                ?: AndroidKeystoreSigningDriver.generate("wallet")
            driver = keystore
            signerLabel = when (keystore.securityLevel) {
                AndroidKeystoreSigningDriver.SecurityLevel.STRONGBOX -> "StrongBox secure element"
                AndroidKeystoreSigningDriver.SecurityLevel.TRUSTED_ENVIRONMENT -> "Hardware keystore (TEE)"
                else -> "Software keystore (emulator)"
            }

            // The key lives in the keystore; the party record must persist
            // beside it or a relaunch re-allocates the same party and the
            // participant rightly refuses ("already exists").
            val savedParty = prefs.getString("partyId", null)
            val savedFingerprint = prefs.getString("fingerprint", null)
            val savedSynchronizer = prefs.getString("synchronizerId", null)
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
            val synchronizer = parties.connectedSynchronizers().first()
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
            preapproval = runCatching {
                ScanClient(WalletEnvironment.scanUrl, WalletEnvironment.http)
                    .transferPreapprovalByParty(party)
            }.getOrNull()
            lastError = null
            Log.i("WALLET", "holdings=$totalAmulet inbox=${inbox.size} history=${history.size}")
        } catch (error: Exception) {
            lastError = error.toString()
            Log.i("WALLET", "refresh failed: $error")
        }
    }

    suspend fun accept(instruction: TransferInstruction) = exercise(instruction, TransferInstructionChoice.ACCEPT)

    suspend fun reject(instruction: TransferInstruction) = exercise(instruction, TransferInstructionChoice.REJECT)

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
        busy = true
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
            busy = false
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
            val provider = WalletEnvironment.http.newCall(request).execute().use { response ->
                kotlinx.serialization.json.Json.parseToJsonElement(response.body!!.string())
                    .let { (it as kotlinx.serialization.json.JsonObject)["party_id"] }
                    .let { (it as kotlinx.serialization.json.JsonPrimitive).content }
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
