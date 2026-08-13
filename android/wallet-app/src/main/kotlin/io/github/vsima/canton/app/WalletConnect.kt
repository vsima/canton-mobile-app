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
object WalletConnectController {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var adapter: CantonWalletConnect? = null
    private var accounts: (suspend () -> List<DappWallet>)? = null

    /** Set by [WalletModel] to surface status lines on the Connect screen. */
    var onStatus: ((String) -> Unit)? = null

    /** Registers the wallet's adapter + the accounts it may share. */
    fun register(adapter: CantonWalletConnect, accounts: suspend () -> List<DappWallet>) {
        this.adapter = adapter
        this.accounts = accounts
    }

    /** Hands a `wc:` pairing URI to the relay. */
    fun pair(uri: String) {
        WalletKit.pair(Wallet.Params.Pair(uri)) { error ->
            status("Pairing failed: ${error.throwable.message}")
        }
    }

    fun onSessionProposal(proposal: Wallet.Model.SessionProposal) {
        val adapter = adapter
        val accounts = accounts
        if (adapter == null || accounts == null) {
            reject(proposal, "Wallet not ready")
            return
        }
        scope.launch {
            try {
                val ns = adapter.sessionNamespaces(accounts())
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
                    onSuccess = { status("Connected to ${proposal.name.ifBlank { "dApp" }}") },
                    onError = { error -> status("Approve failed: ${error.throwable.message}") },
                )
            } catch (e: Exception) {
                status("Proposal error: ${e.message}")
            }
        }
    }

    fun onSessionRequest(request: Wallet.Model.SessionRequest) {
        val adapter = adapter ?: return
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
        ) = WalletConnectController.onSessionRequest(sessionRequest)

        override val onSessionAuthenticate:
            ((Wallet.Model.SessionAuthenticate, Wallet.Model.VerifyContext) -> Unit)? = null

        override fun onSessionDelete(sessionDelete: Wallet.Model.SessionDelete) {}
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
