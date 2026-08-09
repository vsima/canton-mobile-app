// Copyright (c) 2026 Victor Sima
// SPDX-License-Identifier: Apache-2.0

package io.github.vsima.canton.app

import android.util.Base64
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.daml.ledger.api.v2.CommandServiceGrpcKt
import com.daml.ledger.api.v2.CommandServiceOuterClass.SubmitAndWaitRequest
import com.daml.ledger.api.v2.CommandsOuterClass
import com.daml.ledger.api.v2.ValueOuterClass
import io.github.vsima.canton.DamlValues
import io.github.vsima.canton.wallet.AllocatedExternalParty
import io.github.vsima.canton.wallet.ExternalPartyClient
import io.github.vsima.canton.wallet.Holding
import io.github.vsima.canton.wallet.OpenMiningRound
import io.github.vsima.canton.wallet.ScanClient
import io.github.vsima.canton.wallet.SigningDriver
import io.github.vsima.canton.wallet.TokenStandard
import io.github.vsima.canton.wallet.TokenStandardClient
import io.github.vsima.canton.wallet.Transfer
import io.github.vsima.canton.wallet.TransferFeeEstimator
import io.github.vsima.canton.wallet.TransferFeeSchedule
import io.github.vsima.canton.wallet.TransferInstruction
import io.github.vsima.canton.wallet.TransferInstructionChoice
import io.github.vsima.canton.wallet.TransferInstructionStatus
import io.github.vsima.canton.wallet.TransferRegistryClient
import io.github.vsima.canton.wallet.ValidatorClient
import io.github.vsima.canton.wallet.ValidatorException
import io.github.vsima.canton.wallet.latestUsable
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
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
class WalletModel(
    private val store: io.github.vsima.canton.wallet.WalletStore,
    /** Only read to migrate installs that predate [store]; see [migrateLegacyPrefs]. */
    private val legacyPrefs: android.content.SharedPreferences? = null,
) {
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
    /** True while the dev faucet ([getTestFunds]) runs — drives the inline
     *  progress on the "Get test funds" affordance. */
    var funding by mutableStateOf(false)
        private set
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

            val restoredKey = AndroidKeystoreSigningDriver.load(KEY_ALIAS)
            val saved = (store.list().firstOrNull() ?: migrateLegacyPrefs())

            // A restored party is only usable with the key it was allocated
            // under. If the keystore entry is gone while the party record
            // survives, fail loudly instead of pairing the party with a
            // fresh key that can never sign for it.
            if (saved != null && restoredKey == null) {
                phase = Phase.Failed(
                    "The signing key for ${saved.partyId} is no longer in the keystore, " +
                        "so this party can't sign. Clear the app's data to start a fresh wallet."
                )
                return
            }

            val keystore = restoredKey ?: AndroidKeystoreSigningDriver.generate(KEY_ALIAS)
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
            if (saved != null) {
                partyId = saved.partyId
                allocated = AllocatedExternalParty(saved.partyId, saved.publicKeyFingerprint)
                synchronizerId = saved.synchronizerId
                phase = Phase.Ready
                Log.i("WALLET", "restored ${saved.partyId} signer=$signerLabel")
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
            store.save(
                io.github.vsima.canton.wallet.WalletRecord(
                    partyId = party.partyId,
                    publicKeyFingerprint = party.publicKeyFingerprint,
                    synchronizerId = synchronizer,
                    // The Android driver's handle is its keystore alias; the
                    // key itself never leaves the TEE.
                    keyHandle = KEY_ALIAS.toByteArray(),
                    createdAt = java.time.Instant.now(),
                )
            )
            phase = Phase.Ready
            Log.i("WALLET", "onboarded ${party.partyId} signer=$signerLabel")
            refresh()
        } catch (error: Exception) {
            phase = Phase.Failed(error.toString())
            Log.i("WALLET", "onboarding failed: $error")
        }
    }

    /**
     * Moves a party record written by builds that predated the encrypted
     * store out of plain SharedPreferences, so an existing wallet survives
     * the upgrade instead of re-onboarding against a party the participant
     * already knows. Delete once no install can still be carrying one.
     */
    private suspend fun migrateLegacyPrefs(): io.github.vsima.canton.wallet.WalletRecord? {
        val prefs = legacyPrefs ?: return null
        val party = prefs.getString("partyId", null) ?: return null
        val fingerprint = prefs.getString("fingerprint", null) ?: return null
        val synchronizer = prefs.getString("synchronizerId", null) ?: return null

        val record = io.github.vsima.canton.wallet.WalletRecord(
            partyId = party,
            publicKeyFingerprint = fingerprint,
            synchronizerId = synchronizer,
            keyHandle = KEY_ALIAS.toByteArray(),
            createdAt = java.time.Instant.now(),
        )
        store.save(record)
        // Only drop the plaintext copy once the encrypted one is durable.
        prefs.edit().clear().apply()
        Log.i("WALLET", "migrated $party into the encrypted store")
        return record
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

    /** The scan reads feeding [estimatedFee]: the USD fee schedule plus the
     *  open rounds carrying the CC price. Compose state, so the fee row
     *  appears once the lazy fetch lands. */
    private data class FeePreviewInputs(
        val schedule: TransferFeeSchedule,
        val rounds: List<OpenMiningRound>,
    )

    private var feePreview by mutableStateOf<FeePreviewInputs?>(null)
    private var feePreviewAttemptedAt: java.time.Instant = java.time.Instant.EPOCH
    private var feePreviewInFlight = false

    /**
     * The estimated network fee in CC for sending [amountCc] — the SDK's
     * [TransferFeeEstimator] over the cached AmuletRules schedule, converted
     * at the latest usable open round's price. Null when the amount isn't
     * positive or the cache is empty (scan unreachable / not fetched yet) —
     * the fee row is simply absent then; never an error state. Pure cache
     * read: [ensureFeePreviewFresh] populates it.
     *
     * Known reality: CIP-0078 zeroed all Canton Coin transfer fees by
     * governance vote, and splice >= 0.5.16 (CIP-0107) hardcodes them to
     * zero — so on every current network this estimate is 0. The row is the
     * reference wiring for fee-charging registries/configs, and the app
     * renders the honest value, whatever the network's schedule says.
     */
    fun estimatedFee(amountCc: BigDecimal): BigDecimal? {
        if (amountCc.signum() <= 0) return null
        val inputs = feePreview ?: return null
        val price = inputs.rounds.latestUsable()?.amuletPriceUsd ?: return null
        if (price.signum() <= 0) return null
        return TransferFeeEstimator.estimate(inputs.schedule, price, amountCc).feeCc
    }

    /**
     * Lazily (re)fetches the fee-preview inputs from scan — at most one
     * attempt per [FEE_PREVIEW_TTL] (rounds rotate every ~2.5–10 minutes),
     * so per-keystroke calls recompute from cache and never hit the network.
     * Fire-and-forget on the model's scope: it never blocks or fails the
     * send path; on any error the preview is just absent.
     */
    fun ensureFeePreviewFresh() {
        val now = java.time.Instant.now()
        if (feePreviewInFlight ||
            java.time.Duration.between(feePreviewAttemptedAt, now) < FEE_PREVIEW_TTL
        ) return
        feePreviewAttemptedAt = now
        feePreviewInFlight = true
        scope.launch {
            try {
                val scan = ScanClient(WalletEnvironment.scanUrl, WalletEnvironment.http)
                val config = scan.amuletRulesConfig()
                val rounds = scan.openMiningRounds()
                feePreview = FeePreviewInputs(config.transferFees, rounds)
            } catch (error: Exception) {
                // Preview only: keep whatever cache exists; the row just
                // stays absent when there is none.
                Log.i("WALLET", "fee preview fetch failed: $error")
            } finally {
                feePreviewInFlight = false
            }
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

    fun getTestFundsAsync() {
        scope.launch { getTestFunds() }
    }

    /**
     * Dev faucet: funds this wallet with test CC by tapping the validator's
     * wallet and token-standard-transferring the CC here — the same flow the
     * SDK's `LocalNetFaucetTool` drives from a shell, in-app so an empty
     * wallet can seed itself (and demo the transfer flow doing it).
     *
     * The tap leg goes through [ValidatorClient] as the validator wallet
     * user (the LocalNet unsafe JWT — the same auth the ledger connection
     * uses); the transfer leg submits the registry's
     * `TransferFactory_Transfer` as that operator party over the command
     * service. If this wallet has instant receiving on, the funds settle
     * directly; otherwise they arrive as an inbox offer to accept — the
     * faucet never auto-accepts.
     *
     * This is a LocalNet/DevNet developer feature. The reference app only
     * targets dev networks today, so the button is always shown; on a
     * network without a tap (or with the validator API unreachable) the
     * attempt fails and the error is surfaced honestly via [lastError].
     */
    suspend fun getTestFunds() {
        val plain = channel ?: return
        val receiver = partyId ?: return
        if (funding) return
        funding = true
        try {
            // 1. Onboard the validator wallet user (idempotent) and tap.
            val validator = ValidatorClient(
                WalletEnvironment.validatorUrl,
                { WalletEnvironment.unsafeJwt(WalletEnvironment.walletUser) },
                WalletEnvironment.http,
            )
            val status = runCatching { validator.userStatus() }.getOrNull()
            val operatorParty =
                if (status != null && status.userOnboarded && status.partyId.isNotEmpty()) status.partyId
                else validator.register()
            val mintedCid = tapWithRetry(validator)
            Log.i("WALLET", "faucet tapped $FAUCET_TAP_USD USD to ${operatorParty.take(24)}…")

            // 2. Wait for the mint among the operator's unlocked holdings —
            //    the transfer's input UTXOs.
            val operatorChannel = WalletEnvironment.authed(plain, WalletEnvironment.walletUser)
            val operatorTokens = tokens(operatorChannel)
            var inputs = listOf<Holding>()
            for (attempt in 1..10) {
                inputs = operatorTokens.listHoldings(operatorParty).filter { it.lock == null }
                if (inputs.any { it.contractId == mintedCid }) break
                delay(500)
            }
            val instrument = inputs.firstOrNull()?.instrumentId
                ?: error("tapped funds not visible in the validator wallet yet — try again")

            // 3. Token-standard transfer operator → this wallet, via the
            //    registry's transfer factory (with its disclosed contracts).
            val transfer = Transfer(
                sender = operatorParty,
                receiver = receiver,
                amount = FAUCET_SEND_CC,
                instrumentId = instrument,
                requestedAt = java.time.Instant.now(),
                executeBefore = java.time.Instant.now().plusSeconds(24 * 3600),
                inputHoldingCids = inputs.map { it.contractId },
                meta = mapOf(MEMO_KEY to "Test funds"),
            )
            val factory = TransferRegistryClient(WalletEnvironment.registryUrl, WalletEnvironment.http)
                .transferFactory(FaucetValues.transferFactoryChoiceArguments(instrument.admin, transfer))
            CommandServiceGrpcKt.CommandServiceCoroutineStub(operatorChannel).submitAndWait(
                SubmitAndWaitRequest.newBuilder()
                    .setCommands(
                        CommandsOuterClass.Commands.newBuilder()
                            .setCommandId(java.util.UUID.randomUUID().toString())
                            .setUserId(WalletEnvironment.walletUser)
                            .addActAs(operatorParty)
                            .addCommands(
                                CommandsOuterClass.Command.newBuilder().setExercise(
                                    CommandsOuterClass.ExerciseCommand.newBuilder()
                                        .setTemplateId(TokenStandard.transferFactoryInterfaceId)
                                        .setContractId(factory.factoryId)
                                        .setChoice("TransferFactory_Transfer")
                                        .setChoiceArgument(
                                            DamlValues.record(
                                                "expectedAdmin" to DamlValues.party(instrument.admin),
                                                "transfer" to FaucetValues.transferValue(transfer),
                                                "extraArgs" to FaucetValues.extraArgsValue(
                                                    factory.choiceContext.choiceContextData
                                                ),
                                            )
                                        )
                                )
                            )
                            .addAllDisclosedContracts(
                                factory.choiceContext.disclosedContracts.map { it.toProto() }
                            )
                    )
                    .build()
            )
            Log.i("WALLET", "faucet sent $FAUCET_SEND_CC CC (kind=${factory.transferKind})")
            refresh()
        } catch (error: Exception) {
            lastError = error.toString()
            Log.i("WALLET", "faucet failed: $error")
        } finally {
            funding = false
        }
    }

    /** Taps the faucet, retrying the transient statuses the SDK documents
     *  (no open mining round yet, load shedding) with a stable command id
     *  so retries deduplicate instead of double-minting. */
    private suspend fun tapWithRetry(validator: ValidatorClient): String {
        val commandId = java.util.UUID.randomUUID().toString()
        var last: Exception? = null
        for (attempt in 1..4) {
            try {
                return validator.tap(FAUCET_TAP_USD, commandId)
            } catch (error: ValidatorException) {
                if (error.statusCode !in setOf(400, 404, 429, 503)) throw error
                last = error
                Log.i("WALLET", "faucet tap attempt $attempt: $error")
                if (attempt < 4) delay(2_000)
            }
        }
        throw last ?: IllegalStateException("tap failed")
    }

    private fun tokens(channel: Channel): TokenStandardClient =
        TokenStandardClient(
            channel,
            TransferRegistryClient(WalletEnvironment.registryUrl, WalletEnvironment.http),
        )

    companion object {
        /** Android Keystore alias holding this wallet's signing key. */
        const val KEY_ALIAS = "wallet"

        /** Convention key for human-readable transfer memos. */
        const val MEMO_KEY = "splice.lfdecentralizedtrust.org/reason"

        /** The faucet taps $26 — 5200 CC at LocalNet's 0.005 USD/CC — and
         *  forwards a round 5000 CC, leaving the operator headroom for the
         *  Amulet transfer fees it pays as sender. */
        val FAUCET_TAP_USD: BigDecimal = BigDecimal("26.0")
        val FAUCET_SEND_CC: BigDecimal = BigDecimal("5000.0")

        /** At most one fee-preview scan fetch per window; rounds rotate
         *  every ~2.5–10 minutes, so a few minutes of staleness is fine. */
        private val FEE_PREVIEW_TTL: java.time.Duration = java.time.Duration.ofMinutes(3)
    }
}

/**
 * Daml-JSON ⇄ proto bridging for the dev faucet's operator-side transfer.
 *
 * Mirrors the SDK's internal `ChoiceContextJson`/`toValue` helpers: the
 * faucet submits `TransferFactory_Transfer` as the participant-managed
 * validator wallet party over the raw command service — a leg the public
 * SDK surface doesn't cover (its `createTransfer` signs externally) — so
 * the app encodes the choice arguments itself from public SDK types.
 */
private object FaucetValues {

    /** `TransferFactory_Transfer` choice arguments in Daml JSON API encoding,
     *  for the registry's `GetFactoryRequest.choiceArguments`. */
    fun transferFactoryChoiceArguments(expectedAdmin: String, transfer: Transfer): JsonObject =
        buildJsonObject {
            put("expectedAdmin", expectedAdmin)
            putJsonObject("transfer") {
                put("sender", transfer.sender)
                put("receiver", transfer.receiver)
                put("amount", transfer.amount.toPlainString())
                putJsonObject("instrumentId") {
                    put("admin", transfer.instrumentId.admin)
                    put("id", transfer.instrumentId.id)
                }
                put("requestedAt", transfer.requestedAt.toString())
                put("executeBefore", transfer.executeBefore.toString())
                putJsonArray("inputHoldingCids") {
                    transfer.inputHoldingCids.forEach { add(JsonPrimitive(it)) }
                }
                putJsonObject("meta") {
                    putJsonObject("values") {
                        transfer.meta.forEach { (k, v) -> put(k, v) }
                    }
                }
            }
            putJsonObject("extraArgs") {
                putJsonObject("context") { putJsonObject("values") {} }
                putJsonObject("meta") { putJsonObject("values") {} }
            }
        }

    /** The transfer specification as a proto record for the choice argument. */
    fun transferValue(transfer: Transfer): ValueOuterClass.Value =
        DamlValues.record(
            "sender" to DamlValues.party(transfer.sender),
            "receiver" to DamlValues.party(transfer.receiver),
            "amount" to DamlValues.numeric(transfer.amount),
            "instrumentId" to DamlValues.record(
                "admin" to DamlValues.party(transfer.instrumentId.admin),
                "id" to DamlValues.text(transfer.instrumentId.id),
            ),
            "requestedAt" to DamlValues.timestamp(transfer.requestedAt),
            "executeBefore" to DamlValues.timestamp(transfer.executeBefore),
            "inputHoldingCids" to DamlValues.list(
                transfer.inputHoldingCids.map { DamlValues.contractId(it) }
            ),
            "meta" to metadataValue(transfer.meta),
        )

    /** `ExtraArgs { context, meta }` from the registry's `choiceContextData`. */
    fun extraArgsValue(choiceContextData: JsonElement?): ValueOuterClass.Value {
        val values = when (choiceContextData) {
            null, is JsonNull -> emptyMap()
            is JsonObject -> (choiceContextData["values"] as? JsonObject)
                ?.mapValues { anyValueToValue(it.value) } ?: emptyMap()
            else -> error("choiceContextData must be an object, was $choiceContextData")
        }
        return DamlValues.record(
            "context" to DamlValues.record("values" to textMapValue(values)),
            "meta" to metadataValue(emptyMap()),
        )
    }

    /** One `AnyValue` variant from Daml JSON to its proto encoding. */
    private fun anyValueToValue(json: JsonElement): ValueOuterClass.Value {
        val obj = json as? JsonObject ?: error("AnyValue must be a tagged object, was $json")
        val tag = (obj["tag"] as? JsonPrimitive)?.content ?: error("AnyValue object missing tag: $obj")
        val value = obj["value"] ?: JsonNull
        fun primitive(): String =
            (value as? JsonPrimitive)?.content ?: error("$tag value must be a primitive, was $value")
        val payload = when (tag) {
            "AV_Text" -> DamlValues.text(primitive())
            "AV_Int" -> DamlValues.int64(primitive().toLong())
            "AV_Decimal" -> DamlValues.numeric(primitive())
            "AV_Bool" -> DamlValues.bool(primitive().toBooleanStrict())
            "AV_Date" -> DamlValues.date(java.time.LocalDate.parse(primitive()))
            "AV_Time" -> DamlValues.timestamp(java.time.Instant.parse(primitive()))
            "AV_RelTime" -> DamlValues.record(
                "microseconds" to DamlValues.int64(
                    ((((value as? JsonObject)?.get("microseconds")) ?: value) as? JsonPrimitive)
                        ?.content?.toLong() ?: error("AV_RelTime value must carry microseconds")
                )
            )
            "AV_Party" -> DamlValues.party(primitive())
            "AV_ContractId" -> DamlValues.contractId(primitive())
            "AV_List" -> DamlValues.list(
                (value as? JsonArray ?: error("AV_List value must be an array"))
                    .map { anyValueToValue(it) }
            )
            "AV_Map" -> textMapValue(
                (value as? JsonObject ?: error("AV_Map value must be an object"))
                    .mapValues { anyValueToValue(it.value) }
            )
            else -> error("unknown AnyValue constructor $tag")
        }
        return DamlValues.variant(tag, payload)
    }

    private fun metadataValue(meta: Map<String, String>): ValueOuterClass.Value =
        DamlValues.record("values" to textMapValue(meta.mapValues { DamlValues.text(it.value) }))

    private fun textMapValue(entries: Map<String, ValueOuterClass.Value>): ValueOuterClass.Value =
        ValueOuterClass.Value.newBuilder()
            .setTextMap(
                ValueOuterClass.TextMap.newBuilder().addAllEntries(
                    entries.map { (key, value) ->
                        ValueOuterClass.TextMap.Entry.newBuilder().setKey(key).setValue(value).build()
                    }
                )
            )
            .build()
}
