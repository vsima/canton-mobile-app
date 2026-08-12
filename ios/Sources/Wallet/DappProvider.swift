// Copyright (c) 2026 Victor Sima
// SPDX-License-Identifier: Apache-2.0

// The wallet acting as a CIP-0103 provider over the LAN — the iOS parity of
// the Android DappProviderController. A LanGrpcDappServer fronts a DappSession
// built from the wallet's own party and signing key.
//
// Additive: nothing in Send / Portfolio / Inbox changes. Scope is ping and
// sign-in — connect, listAccounts, getPrimaryAccount, status and signMessage
// are wired; prepareExecute is absent (it needs the JSON Ledger API pipeline),
// so a payment request comes back 4200 until that lands.

import CantonDappKit
import CantonDappLanKit
import CantonDappWalletKit
import CantonWalletKit
import Foundation
import SwiftUI

@MainActor
@Observable
final class DappProviderController {
    private(set) var listening = false
    private(set) var port: Int?
    private(set) var pending: PendingApproval?
    private(set) var lastActivity: String?

    private let account: DappWallet
    private let messageSigner: any DappMessageSigner
    private let networkId: String
    private var server: LanGrpcDappServer?

    init(account: DappWallet, messageSigner: any DappMessageSigner, networkId: String) {
        self.account = account
        self.messageSigner = messageSigner
        self.networkId = networkId
    }

    func start() async throws {
        guard !listening else { return }
        let session = DappSession(
            peer: DappPeer(id: "lan", name: "LAN dApp", verified: false),
            accounts: FixedAccounts(accounts: [account]),
            approver: Approver(controller: self),
            network: DappNetworkConfig(networkId: networkId),
            messageSigner: messageSigner
            // prepareExecute / ledgerApi intentionally absent for now.
        )
        // 0.0.0.0 so a dApp on this device reaches it at 127.0.0.1 and one on
        // another device over Wi-Fi.
        let server = LanGrpcDappServer(handler: session, host: "0.0.0.0")
        let boundPort = try await server.start(port: 0)
        self.server = server
        port = boundPort
        listening = true
        lastActivity = "Listening on port \(boundPort)"
    }

    func stop() {
        server?.shutdown()
        server = nil
        listening = false
        port = nil
        pending = nil
        lastActivity = "Stopped"
    }

    /// Called by the (Sendable) approver; raises the sheet and suspends the
    /// engine here until the user answers.
    fileprivate func requestApproval(_ request: DappApprovalRequest) async -> DappApproval {
        let (title, detail, accounts) = Self.describe(request, account: account)
        let decision = await withCheckedContinuation { (cont: CheckedContinuation<DappApproval, Never>) in
            self.pending = PendingApproval(title: title, detail: detail, accounts: accounts) { decision in
                cont.resume(returning: decision)
            }
        }
        pending = nil
        lastActivity = switch decision {
        case .approved: "Approved: \(title)"
        case .rejected: "Declined: \(title)"
        }
        return decision
    }

    private static func describe(
        _ request: DappApprovalRequest,
        account: DappWallet
    ) -> (String, String, [DappWallet]) {
        switch request {
        case .connection(let peer, let network, _):
            ("\(peer.name) wants to connect",
             "Share your account \(account.partyId.prefix(28))… on \(network.networkId)?",
             [account])
        case .message(let peer, _, let message):
            ("\(peer.name) wants a signature", message, [])
        case .transaction(let peer, let actAs, _, _):
            ("\(peer.name) wants to submit a transaction",
             "Acting as \(actAs.partyId.prefix(28))…", [])
        }
    }
}

/// One request awaiting the user. Completing it resumes the provider engine.
@MainActor
final class PendingApproval: Identifiable {
    let id = UUID()
    let title: String
    let detail: String
    private let accounts: [DappWallet]
    private let resolve: (DappApproval) -> Void

    init(title: String, detail: String, accounts: [DappWallet], resolve: @escaping (DappApproval) -> Void) {
        self.title = title
        self.detail = detail
        self.accounts = accounts
        self.resolve = resolve
    }

    func approve() { resolve(.approved(accounts: accounts)) }
    func reject() { resolve(.rejected(reason: "Declined in the wallet")) }
}

/// Sendable bridge from the provider engine to the MainActor controller.
private struct Approver: DappApprovalDelegate {
    let controller: DappProviderController

    func approve(_ request: DappApprovalRequest) async -> DappApproval {
        await controller.requestApproval(request)
    }
}

private struct FixedAccounts: DappAccountsSource {
    let accounts: [DappWallet]
    func accounts() async throws -> [DappWallet] { accounts }
}

// MARK: - UI

/// The wallet as a CIP-0103 provider over the LAN. Additive — it drives the
/// dApp side already shipped in the dApp reference app, and touches nothing in
/// Send / Portfolio / Inbox.
struct DappsView: View {
    @Environment(WalletModel.self) private var model

    var body: some View {
        NavigationStack {
            Form {
                if model.partyId == nil {
                    Text("Onboard the wallet first — a dApp connects to your party.")
                        .foregroundStyle(.secondary)
                } else {
                    Section {
                        Text("Let a CIP-0103 dApp connect to this wallet over the local "
                            + "network. The wallet approves every connection and signature; "
                            + "keys never leave it.")
                            .font(.callout)
                            .foregroundStyle(.secondary)
                    }
                    if let provider = model.dappProvider, provider.listening {
                        Section("Listening") {
                            LabeledContent("Port", value: "\(provider.port ?? 0)")
                            Text("A dApp on this device connects to 127.0.0.1:\(provider.port ?? 0); "
                                + "one on another device uses this phone's Wi-Fi address and the "
                                + "same port.")
                                .font(.footnote)
                                .foregroundStyle(.secondary)
                            Button("Stop listening", role: .destructive) { model.stopDappProvider() }
                        }
                        if let activity = provider.lastActivity {
                            Section { Text("Recent: \(activity)").font(.footnote) }
                        }
                    } else {
                        Section {
                            Button("Start listening") { model.startDappProvider() }
                        }
                    }
                }
            }
            .navigationTitle("dApps")
        }
        .sheet(item: Binding(
            get: { model.dappProvider?.pending },
            // Swiping the sheet away is a decline.
            set: { if $0 == nil { model.dappProvider?.pending?.reject() } }
        )) { pending in
            ApprovalSheet(pending: pending)
        }
    }
}

/// The connection / signature approval sheet — the wallet decides.
struct ApprovalSheet: View {
    let pending: PendingApproval

    var body: some View {
        VStack(spacing: 16) {
            Text(pending.title).font(.title2).bold()
            Text(pending.detail)
                .foregroundStyle(.secondary)
                .multilineTextAlignment(.center)
            Divider()
            Button { pending.approve() } label: {
                Text("Approve").frame(maxWidth: .infinity)
            }
            .buttonStyle(.borderedProminent)
            Button(role: .destructive) { pending.reject() } label: {
                Text("Decline").frame(maxWidth: .infinity)
            }
            .buttonStyle(.bordered)
        }
        .padding(24)
        .presentationDetents([.medium])
    }
}