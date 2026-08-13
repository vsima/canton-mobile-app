// Copyright (c) 2026 Victor Sima
// SPDX-License-Identifier: Apache-2.0

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

    var body: some View {
        NavigationStack {
            VStack(alignment: .leading, spacing: 16) {
                switch approval.request {
                case .connection(let peer, _, let available):
                    header("Connect", "“\(peer.name)” wants to connect and see your Canton account.")
                    if let account = available.first {
                        labeled("Account", account.partyId)
                    }
                    buttons(approveTitle: "Connect") {
                        approval.resolve(.approved(accounts: available))
                    }
                case .message(let peer, _, let message):
                    header("Sign in", "“\(peer.name)” asks you to sign a message with your Canton account.")
                    labeled("Message", message)
                    buttons(approveTitle: "Sign") {
                        approval.resolve(.approved())
                    }
                case .transaction(let peer, let actAs, _, _):
                    header(
                        "Approve transaction",
                        "“\(peer.name)” asks you to approve a transaction with \(String(actAs.partyId.prefix(24)))…."
                    )
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

    private func header(_ title: String, _ subtitle: String) -> some View {
        VStack(alignment: .leading, spacing: 8) {
            Text(title).font(.title2.bold())
            Text(subtitle).font(.subheadline).foregroundStyle(.secondary)
        }
    }

    private func labeled(_ label: String, _ value: String) -> some View {
        VStack(alignment: .leading, spacing: 4) {
            Text(label.uppercased()).font(.caption2).foregroundStyle(.secondary)
            Text(value).font(.caption.monospaced()).textSelection(.enabled)
        }
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
