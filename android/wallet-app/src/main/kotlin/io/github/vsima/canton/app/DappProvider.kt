// Copyright (c) 2026 Victor Sima
// SPDX-License-Identifier: Apache-2.0

package io.github.vsima.canton.app

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.github.vsima.canton.dapp.DappWallet
import io.github.vsima.canton.dapp.wallet.DappApproval
import io.github.vsima.canton.dapp.wallet.DappApprovalDelegate
import io.github.vsima.canton.dapp.wallet.DappApprovalRequest
import io.github.vsima.canton.dapp.wallet.DappMessageSigner
import io.github.vsima.canton.dapp.wallet.DappNetworkConfig
import io.github.vsima.canton.dapp.wallet.DappPeer
import io.github.vsima.canton.dapp.wallet.DappSession
import io.github.vsima.canton.dapp.lan.LanGrpcDappServer
import java.net.InetSocketAddress
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The wallet acting as a CIP-0103 **provider** over the LAN: a
 * [LanGrpcDappServer] fronting a [DappSession] built from the wallet's own
 * onboarded party and signing key.
 *
 * Additive by design — nothing in Send / Portfolio / Inbox changes. This is
 * the other half of the LAN transport whose dApp side already ships in the
 * dApp reference app: start listening here, and that app can connect.
 *
 * Scope for now: **ping and sign-in.** `connect`, `listAccounts`,
 * `getPrimaryAccount`, `status` and `signMessage` are wired; `prepareExecute`
 * is not (it needs the JSON Ledger API pipeline), so a payment request comes
 * back `4200` until that lands. That is the honest current state.
 */
class DappProviderController(
    private val account: DappWallet,
    private val messageSigner: DappMessageSigner,
    private val networkId: String,
    private val scope: CoroutineScope,
) {
    /** Whether the LAN provider is listening. */
    var listening by mutableStateOf(false)
        private set

    /** The port it is listening on, once started. */
    var port by mutableStateOf<Int?>(null)
        private set

    /** A request awaiting the user's decision, or null. Drives the sheet. */
    var pending by mutableStateOf<PendingApproval?>(null)
        private set

    /** A short line of the most recent activity, for the screen. */
    var lastActivity by mutableStateOf<String?>(null)
        private set

    private var server: LanGrpcDappServer? = null

    /**
     * One request the user must approve or reject. Completing the deferred
     * resumes the provider engine, which is suspended inside `approve`.
     */
    class PendingApproval(
        val title: String,
        val detail: String,
        private val decision: CompletableDeferred<DappApproval>,
        private val approvedAccounts: List<DappWallet>,
    ) {
        fun approve() {
            decision.complete(DappApproval.Approved(approvedAccounts))
        }

        fun reject() {
            decision.complete(DappApproval.Rejected("Declined in the wallet"))
        }
    }

    private val approver = DappApprovalDelegate { request ->
        val deferred = CompletableDeferred<DappApproval>()
        val (title, detail, accounts) = describe(request)
        // approve() runs on a gRPC thread; surface the sheet on the main
        // thread so Compose observes it, then suspend here until the user
        // answers. The engine is holding the request open in the meantime.
        withContext(Dispatchers.Main) {
            pending = PendingApproval(title, detail, deferred, accounts)
        }
        val decision = deferred.await()
        withContext(Dispatchers.Main) {
            pending = null
            lastActivity = when (decision) {
                is DappApproval.Approved -> "Approved: $title"
                is DappApproval.Rejected -> "Declined: $title"
            }
        }
        decision
    }

    private fun describe(request: DappApprovalRequest): Triple<String, String, List<DappWallet>> =
        when (request) {
            is DappApprovalRequest.Connection -> Triple(
                "${request.peer.name} wants to connect",
                "Share your account ${account.partyId.take(28)}… on ${request.network.networkId}?",
                listOf(account),
            )
            is DappApprovalRequest.Message -> Triple(
                "${request.peer.name} wants a signature",
                request.message,
                emptyList(),
            )
            is DappApprovalRequest.Transaction -> Triple(
                "${request.peer.name} wants to submit a transaction",
                "Acting as ${request.actAs.partyId.take(28)}…",
                emptyList(),
            )
        }

    /** Starts listening on all interfaces (loopback for a same-device dApp, LAN otherwise). */
    fun start() {
        if (listening) return
        val session = DappSession(
            peer = DappPeer(id = "lan", name = "LAN dApp", verified = false),
            accounts = { listOf(account) },
            approver = approver,
            network = DappNetworkConfig(networkId = networkId),
            messageSigner = messageSigner,
            // prepareExecute / ledgerApi intentionally absent for now — see the class note.
        )
        // 0.0.0.0 so a second device on the LAN can reach it too; a dApp on
        // this same device connects via 127.0.0.1.
        val started = LanGrpcDappServer(session, InetSocketAddress("0.0.0.0", 0)).start()
        server = started
        port = started.port
        listening = true
        lastActivity = "Listening on port ${started.port}"
    }

    fun stop() {
        server?.shutdown()
        server = null
        listening = false
        port = null
        pending = null
        lastActivity = "Stopped"
    }
}