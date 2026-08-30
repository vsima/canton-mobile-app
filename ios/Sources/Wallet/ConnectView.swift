// Copyright (c) 2026 Victor Sima
// SPDX-License-Identifier: Apache-2.0

import CantonDappKit
import CantonDappWalletKit
import SwiftUI

/// The Connect screen: pair a `wc:` link (typed, pasted, or scanned) and manage
/// active WalletConnect sessions. The iOS twin of Android's `ConnectScreen`.
struct ConnectView: View {
    @Environment(WalletModel.self) private var model
    @State private var uri = ""
    @State private var showScanner = false

    private var isPairable: Bool {
        uri.trimmingCharacters(in: .whitespacesAndNewlines).hasPrefix("wc:")
    }

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    HStack(alignment: .top) {
                        TextField("wc:…", text: $uri, axis: .vertical)
                            .font(.caption.monospaced())
                            .autocorrectionDisabled()
                            .textInputAutocapitalization(.never)
                        Button {
                            showScanner = true
                        } label: {
                            Image(systemName: "qrcode.viewfinder")
                        }
                        .buttonStyle(.borderless)
                        .accessibilityLabel("Scan a WalletConnect QR code")
                    }
                    Button("Connect") {
                        model.pairWalletConnect(uri)
                        uri = ""
                    }
                    .disabled(!isPairable)
                } header: {
                    Text("Pair a dApp")
                } footer: {
                    Text("Open a dApp's WalletConnect QR, then scan or paste its wc: link here.")
                }

                if let status = model.wcStatus {
                    Section {
                        Label(status, systemImage: "dot.radiowaves.left.and.right")
                            .font(.caption)
                    }
                }

                Section("Connected dApps") {
                    if model.wcSessions.isEmpty {
                        Text("No active sessions.").foregroundStyle(.secondary)
                    }
                    ForEach(model.wcSessions) { session in
                        HStack {
                            VStack(alignment: .leading, spacing: 2) {
                                Text(session.name).font(.body)
                                if !session.url.isEmpty {
                                    Text(session.url).font(.caption2).foregroundStyle(.secondary)
                                }
                            }
                            Spacer()
                            Button("Disconnect", role: .destructive) {
                                model.disconnectWcSession(topic: session.topic)
                            }
                            .buttonStyle(.bordered)
                            .controlSize(.small)
                        }
                    }
                }
            }
            .navigationTitle("Connect")
            .task { model.refreshWcSessions() }
            .sheet(isPresented: $showScanner) {
                QRScannerSheet { scanned in
                    let trimmed = scanned.trimmingCharacters(in: .whitespacesAndNewlines)
                    if trimmed.hasPrefix("wc:") {
                        model.pairWalletConnect(trimmed)
                    } else {
                        uri = trimmed
                    }
                }
            }
        }
    }
}

/// The WalletConnect approval sheet — Connect / Sign in / Approve transaction —
/// mounted once above the shell (see `WalletTabsView`). Switches on the CIP-0103
/// approval request the engine surfaced; the buttons answer it via `resolve`.
struct WcApprovalSheet: View {
    let approval: WalletModel.WcApproval

    /// Accent for the sheet that moves funds; the brand orange, deliberately
    /// not a semantic color so it reads the same in light and dark.
    private static let transactionAccent = Color(red: 0.91, green: 0.31, blue: 0.18)

    var body: some View {
        NavigationStack {
            VStack(alignment: .leading, spacing: 16) {
                switch approval.request {
                case .connection(let peer, let network, let available):
                    identity(icon: "link", tint: .accentColor, title: "Connect", peer: peer)
                    Text(
                        "Wants to see your Canton account. Sign-ins and payments it asks for "
                            + "later each come back to this phone for approval."
                    )
                    .font(.subheadline)
                    if let account = available.first {
                        labeled("Account", account.partyId)
                    }
                    factRow("Network", network.networkId)
                    buttons(approveTitle: "Connect") {
                        approval.resolve(.approved(accounts: available))
                    }
                case .message(let peer, _, let message):
                    identity(icon: "key", tint: .purple, title: "Sign in", peer: peer)
                    Text(
                        "Asks you to sign the message below. Signing proves you control "
                            + "your party; it moves no funds."
                    )
                    .font(.subheadline)
                    messageBlock(message)
                    buttons(approveTitle: "Sign") {
                        approval.resolve(.approved())
                    }
                case .transaction(let peer, let actAs, let network, let submission):
                    let transfer = DappCommandSummary.transferOf(submission)
                    identity(
                        icon: "arrow.up.right.circle",
                        tint: Self.transactionAccent,
                        title: transfer != nil ? "Payment request" : "Approve transaction",
                        peer: peer
                    )
                    if let transfer {
                        Text("\(transfer.amount) \(transfer.instrumentId == "Amulet" ? "CC" : transfer.instrumentId)")
                            .font(.largeTitle.weight(.semibold))
                        labeled("To", transfer.receiver)
                        if let memo = transfer.memo {
                            labeled("Memo", memo)
                        }
                        factRow("From", String(actAs.partyId.prefix(24)) + "…", mono: true)
                        factRow("Network", network.networkId)
                    } else {
                        // Not a token-standard transfer: never guess. Name each
                        // command and show the raw payload so what is on screen
                        // is exactly what was asked.
                        Text(
                            "Asks you to sign a transaction that is not a standard token "
                                + "transfer. Review its commands:"
                        )
                        .font(.subheadline)
                        ForEach(DappCommandSummary.describe(submission), id: \.self) { line in
                            Text("• \(line)").font(.subheadline)
                        }
                        messageBlock(rawCommands(submission))
                        factRow("Acting as", String(actAs.partyId.prefix(24)) + "…", mono: true)
                        factRow("Network", network.networkId)
                    }
                    Text(
                        "Prepared on your participant. This phone re-verifies the "
                            + "transaction hash before your hardware key signs."
                    )
                    .font(.caption2)
                    .foregroundStyle(.secondary)
                    buttons(approveTitle: "Approve") {
                        approval.resolve(.approved())
                    }
                }
                Spacer(minLength: 0)
            }
            .padding(24)
            .navigationBarTitleDisplayMode(.inline)
        }
        // Open at a height that shows the whole approval; the user can still
        // expand. SwiftUI sheets already respect the bottom safe area.
        .presentationDetents([.medium, .large])
    }

    /// The identity block every approval sheet leads with: what kind of
    /// request (icon + title) and who is asking (peer name, origin, and
    /// whether the transport could verify that identity). A WalletConnect
    /// peer names itself, so an unverified name is shown as a claim, not an
    /// identity.
    private func identity(icon: String, tint: Color, title: String, peer: DappPeer) -> some View {
        HStack(spacing: 12) {
            ZStack {
                Circle().fill(tint.opacity(0.14)).frame(width: 44, height: 44)
                Image(systemName: icon).font(.system(size: 20)).foregroundStyle(tint)
            }
            VStack(alignment: .leading, spacing: 2) {
                Text(title).font(.title2.bold())
                HStack(spacing: 6) {
                    Text(peer.name).font(.subheadline.weight(.medium))
                    if !peer.verified {
                        Text("Unverified")
                            .font(.caption2)
                            .foregroundStyle(.secondary)
                            .padding(.horizontal, 6)
                            .padding(.vertical, 2)
                            .background(Color(.secondarySystemFill), in: Capsule())
                    }
                }
                if let url = peer.url {
                    Text(url.replacingOccurrences(of: "https://", with: "")
                        .replacingOccurrences(of: "http://", with: ""))
                        .font(.caption2)
                        .foregroundStyle(.secondary)
                }
            }
        }
    }

    private func labeled(_ label: String, _ value: String) -> some View {
        VStack(alignment: .leading, spacing: 4) {
            Text(label.uppercased()).font(.caption2).foregroundStyle(.secondary)
            Text(value).font(.caption.monospaced()).textSelection(.enabled)
        }
    }

    private func factRow(_ label: String, _ value: String, mono: Bool = false) -> some View {
        HStack {
            Text(label).font(.caption).foregroundStyle(.secondary)
            Spacer()
            Text(value).font(mono ? .caption.monospaced() : .caption)
        }
    }

    private func messageBlock(_ text: String) -> some View {
        ScrollView {
            Text(text)
                .font(.caption.monospaced())
                .textSelection(.enabled)
                .frame(maxWidth: .infinity, alignment: .leading)
                .padding(12)
        }
        .frame(maxHeight: 170)
        .background(Color(.secondarySystemBackground), in: RoundedRectangle(cornerRadius: 10))
    }

    private func rawCommands(_ submission: PrepareSubmission) -> String {
        guard let data = try? JSONValue.array(submission.commands).serialized(),
              let text = String(data: data, encoding: .utf8)
        else { return "(unrenderable payload)" }
        return text
    }

    private func buttons(approveTitle: String, onApprove: @escaping () -> Void) -> some View {
        HStack {
            Button("Decline", role: .cancel) {
                approval.resolve(.rejected(reason: "Declined"))
            }
            .buttonStyle(.bordered)
            Spacer()
            Button(approveTitle, action: onApprove)
                .buttonStyle(.borderedProminent)
        }
        .padding(.top, 8)
    }
}
