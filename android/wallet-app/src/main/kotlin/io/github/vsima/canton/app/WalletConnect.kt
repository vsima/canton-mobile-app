// Copyright (c) 2026 Victor Sima
// SPDX-License-Identifier: Apache-2.0

package io.github.vsima.canton.app

import android.app.Application
import android.util.Log
import com.reown.android.Core
import com.reown.android.CoreClient
import com.reown.walletkit.client.Wallet
import com.reown.walletkit.client.WalletKit
import io.github.vsima.canton.dapp.DappWallet
import io.github.vsima.canton.dapp.wallet.DappPeer
import io.github.vsima.canton.dapp.wc.Caip
import io.github.vsima.canton.dapp.wc.CantonWalletConnect
import io.github.vsima.canton.dapp.wc.WcRequest
import io.github.vsima.canton.dapp.wc.WcResponse
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json

/**
 * The Reown WalletKit binding: the relay/pairing/session client that carries
 * CIP-0103 frames to and from the SDK's [CantonWalletConnect] adapter.
 *
 * This is the one place that depends on Reown. It owns no protocol logic —
 * `onSessionProposal` approves with the adapter's namespaces, `onSessionRequest`
 * routes into the adapter's `handle`, and the adapter drives the engine
 * (`DappSession`) which does the approvals and signing. The wallet's identity
 * and the approval UI stay in [WalletModel]; nothing here touches a key.
 *
 * Reown delivers its callbacks on a background thread, so requests are handled
 * on an IO scope; the engine's approval delegate hops to Main to raise the
 * sheet.
 */
/** A live WalletConnect session, for display on the Connect screen. */
data class WcSessionInfo(val topic: String, val name: String, val url: String)

object WalletConnectController {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var networkId: String? = null
    private var adapterFactory: ((DappPeer) -> CantonWalletConnect)? = null
    private var accounts: (suspend () -> List<DappWallet>)? = null

    /** One adapter (and so one `DappSession` + grant) per session topic. */
    private val adapters = mutableMapOf<String, CantonWalletConnect>()

    /** Peer display names by topic, for the disconnect status line: the
     *  session is already gone from WalletKit when its delete event fires. */
    private val peerNames = mutableMapOf<String, String>()

    /** Set by [WalletModel] to surface status lines on the Connect screen. */
    var onStatus: ((String) -> Unit)? = null

    /** Set by [WalletModel] to surface the active sessions on the Connect screen. */
    var onSessions: ((List<WcSessionInfo>) -> Unit)? = null

    /**
     * Registers the accounts the wallet may share and the per-peer adapter
     * factory. The factory runs on a peer's first request, with the peer
     * built from that WalletConnect session's own metadata; see [adapterFor].
     */
    fun register(
        networkId: String,
        accounts: suspend () -> List<DappWallet>,
        adapterFactory: (DappPeer) -> CantonWalletConnect,
    ) {
        this.networkId = networkId
        this.accounts = accounts
        this.adapterFactory = adapterFactory
        synchronized(adapters) { adapters.clear() }
    }

    /**
     * The adapter for [topic], created on first use with the peer identity
     * the transport can attest: the WalletConnect session's peer metadata
     * (self-reported, so `verified` only when Reown's Verify API vouched for
     * the origin in [verify]).
     */
    private fun adapterFor(topic: String, verify: Wallet.Model.VerifyContext?): CantonWalletConnect? {
        val factory = adapterFactory ?: return null
        synchronized(adapters) {
            return adapters.getOrPut(topic) {
                val meta = try {
                    WalletKit.getActiveSessionByTopic(topic)?.metaData
                } catch (e: Throwable) {
                    Log.i("WALLET", "WC: session lookup for $topic failed: $e")
                    null
                }
                val name = meta?.name?.takeIf { it.isNotBlank() } ?: "Unidentified dApp"
                peerNames[topic] = name
                factory(
                    DappPeer(
                        id = topic,
                        name = name,
                        url = meta?.url?.takeIf { it.isNotBlank() },
                        iconUrl = meta?.icons?.firstOrNull { it.isNotBlank() },
                        verified = verify?.validation == Wallet.Model.Validation.VALID,
                    ),
                )
            }
        }
    }

    /** Reads WalletKit's active sessions and pushes them to the UI. */
    fun refreshSessions() {
        val sessions = try {
            WalletKit.getListOfActiveSessions().map { s ->
                WcSessionInfo(topic = s.topic, name = s.metaData?.name ?: "dApp", url = s.metaData?.url ?: "")
            }
        } catch (e: Throwable) {
            Log.i("WALLET", "WC: getListOfActiveSessions failed: $e")
            emptyList()
        }
        onSessions?.invoke(sessions)
    }

    /** Disconnects a session by topic. */
    fun disconnect(topic: String) {
        synchronized(adapters) { adapters.remove(topic) }
        WalletKit.disconnectSession(
            Wallet.Params.SessionDisconnect(sessionTopic = topic),
            onSuccess = { refreshSessions() },
            onError = { error -> status("Disconnect failed: ${error.throwable.message}") },
        )
    }

    /** The dApp side (or the relay) ended a session. */
    fun onSessionDelete(delete: Wallet.Model.SessionDelete) {
        if (delete is Wallet.Model.SessionDelete.Success) {
            val name = synchronized(adapters) {
                adapters.remove(delete.topic)
                peerNames.remove(delete.topic)
            }
            status("${name ?: "A dApp"} disconnected")
        }
        refreshSessions()
    }

    /** Hands a `wc:` pairing URI to the relay. */
    fun pair(uri: String) {
        WalletKit.pair(Wallet.Params.Pair(uri)) { error ->
            status("Pairing failed: ${error.throwable.message}")
        }
    }

    fun onSessionProposal(proposal: Wallet.Model.SessionProposal) {
        val networkId = networkId
        val accounts = accounts
        if (networkId == null || accounts == null || adapterFactory == null) {
            reject(proposal, "Wallet not ready")
            return
        }
        scope.launch {
            try {
                // Approve the methods the dApp asked for that the engine can
                // serve: the ecosystem proposes canton_-prefixed names, a
                // CIP-0103-verbatim dApp proposes bare ones, and both clients
                // refuse any request outside the approved set.
                val requested = (proposal.requiredNamespaces.values + proposal.optionalNamespaces.values)
                    .flatMap { it.methods }
                val ns = CantonWalletConnect.sessionNamespaces(Caip.chainId(networkId), accounts(), requested)
                val namespaces = mapOf(
                    Caip.CANTON_NAMESPACE to Wallet.Model.Namespace.Session(
                        chains = ns.chains,
                        methods = ns.methods,
                        events = ns.events,
                        accounts = ns.accounts,
                    ),
                )
                WalletKit.approveSession(
                    Wallet.Params.SessionApprove(
                        proposerPublicKey = proposal.proposerPublicKey,
                        namespaces = namespaces,
                    ),
                    onSuccess = {
                        status("Connected to ${proposal.name.ifBlank { "dApp" }}")
                        refreshSessions()
                    },
                    onError = { error -> status("Approve failed: ${error.throwable.message}") },
                )
            } catch (e: Exception) {
                status("Proposal error: ${e.message}")
            }
        }
    }

    fun onSessionRequest(request: Wallet.Model.SessionRequest, verify: Wallet.Model.VerifyContext?) {
        val adapter = adapterFor(request.topic, verify) ?: return
        scope.launch {
            val id = request.request.id
            val topic = request.topic
            val params = runCatching { Json.parseToJsonElement(request.request.params) }.getOrNull()
            val response = adapter.handle(
                WcRequest(
                    topic = topic,
                    requestId = id,
                    chainId = request.chainId ?: "",
                    method = request.request.method,
                    params = params,
                ),
            )
            val jsonRpc = when (response) {
                is WcResponse.Success ->
                    Wallet.Model.JsonRpcResponse.JsonRpcResult(id = id, result = response.result.toString())
                is WcResponse.Error ->
                    Wallet.Model.JsonRpcResponse.JsonRpcError(id = id, code = response.code, message = response.message)
            }
            WalletKit.respondSessionRequest(
                Wallet.Params.SessionRequestResponse(sessionTopic = topic, jsonRpcResponse = jsonRpc),
                onSuccess = {},
                onError = { error -> status("Respond failed: ${error.throwable.message}") },
            )
        }
    }

    private fun reject(proposal: Wallet.Model.SessionProposal, reason: String) {
        WalletKit.rejectSession(
            Wallet.Params.SessionReject(proposerPublicKey = proposal.proposerPublicKey, reason = reason),
            onSuccess = {},
            onError = {},
        )
    }

    private fun status(line: String) {
        Log.i("WALLET", "WC: $line")
        onStatus?.invoke(line)
    }
}

/**
 * The wallet's [Application], which initialises Reown WalletKit once for the
 * process and forwards its delegate callbacks to [WalletConnectController]. Init
 * must happen here, before any Activity, and Core before WalletKit.
 */
class WalletApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        val metadata = Core.Model.AppMetaData(
            name = "Canton Wallet",
            description = "Canton reference wallet",
            url = "https://github.com/vsima/canton-mobile-app",
            icons = emptyList(),
            redirect = "canton-wallet://wc",
        )
        CoreClient.initialize(
            application = this,
            projectId = WC_PROJECT_ID,
            metaData = metadata,
            onError = { error -> Log.i("WALLET", "WC core init failed: ${error.throwable}") },
        )
        WalletKit.initialize(
            Wallet.Params.Init(core = CoreClient),
            onSuccess = { Log.i("WALLET", "WC WalletKit ready") },
            onError = { error -> Log.i("WALLET", "WC WalletKit init failed: ${error.throwable}") },
        )
        WalletKit.setWalletDelegate(WalletDelegate)
    }

    private object WalletDelegate : WalletKit.WalletDelegate {
        override fun onSessionProposal(
            sessionProposal: Wallet.Model.SessionProposal,
            verifyContext: Wallet.Model.VerifyContext,
        ) = WalletConnectController.onSessionProposal(sessionProposal)

        override fun onSessionRequest(
            sessionRequest: Wallet.Model.SessionRequest,
            verifyContext: Wallet.Model.VerifyContext,
        ) = WalletConnectController.onSessionRequest(sessionRequest, verifyContext)

        override val onSessionAuthenticate:
            ((Wallet.Model.SessionAuthenticate, Wallet.Model.VerifyContext) -> Unit)? = null

        override fun onSessionDelete(sessionDelete: Wallet.Model.SessionDelete) {
            WalletConnectController.onSessionDelete(sessionDelete)
        }
        override fun onSessionExtend(session: Wallet.Model.Session) {}
        override fun onSessionSettleResponse(response: Wallet.Model.SettledSessionResponse) {}
        override fun onSessionUpdateResponse(response: Wallet.Model.SessionUpdateResponse) {}
        override fun onProposalExpired(proposal: Wallet.Model.ExpiredProposal) {}
        override fun onRequestExpired(request: Wallet.Model.ExpiredRequest) {}
        override fun onConnectionStateChange(state: Wallet.Model.ConnectionState) {}
        override fun onError(error: Wallet.Model.Error) {}
    }

    companion object {
        /** Non-secret WalletConnect project id (a public client key). */
        const val WC_PROJECT_ID = "cbef3d23404e895fdc178fadcf6798c1"
    }
}
