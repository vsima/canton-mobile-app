// Copyright (c) 2026 Victor Sima
// SPDX-License-Identifier: Apache-2.0

import CantonDappKit
import CantonDappWalletKit
import SwiftUI

/// The dApp roster: each connected agent or dApp with its spending limits at
/// a glance; tap to manage. Pairing is the roster's one action, a `wc:` link
/// scanned or pasted in the ``ConnectSheet``. The dApp's connect and each
/// signature surface as approval sheets (``WcApprovalSheet``) — the key never
/// leaves the device. The iOS twin of Android's `AgentsScreen`.
struct DappsView: View {
    @Environment(WalletModel.self) private var model
    @State private var showConnect = false
    @State private var selected: WcSessionInfo?

    var body: some View {
        NavigationStack {
            List {
                if model.wcSessions.isEmpty {
                    // The empty roster leads with the one action that fills it.
                    Section {
                        VStack(spacing: 12) {
                            Image(systemName: "link")
                                .font(.system(size: 40))
                                .foregroundStyle(.secondary)
                            Text("Nothing connected yet")
                                .font(.headline)
                            Text("An agent or dApp you connect can ask this wallet to sign in and pay. You set its spending limits; the key never leaves this device.")
                                .font(.footnote)
                                .foregroundStyle(.secondary)
                                .multilineTextAlignment(.center)
                            Button("Connect an agent or dApp") { showConnect = true }
                                .buttonStyle(.borderedProminent)
                        }
                        .frame(maxWidth: .infinity)
                        .padding(.vertical, 24)
                    }
                } else {
                    Section {
                        ForEach(model.wcSessions) { session in
                            let policy = model.dappPolicies[session.stableId]
                            Button {
                                selected = session
                            } label: {
                                VStack(alignment: .leading, spacing: 2) {
                                    Text(session.name).font(.body)
                                    if !session.url.isEmpty {
                                        Text(session.url).font(.caption2).foregroundStyle(.secondary)
                                    }
                                    Text(policySummary(policy))
                                        .font(.caption)
                                        .foregroundStyle(policy == nil ? Color.secondary : Color.accentColor)
                                }
                            }
                            .buttonStyle(.plain)
                        }
                    } header: {
                        Text("Connected dApps")
                    } footer: {
                        Text("Tap one to set its spending limits and see what it has done.")
                    }
                }
                if let status = model.wcStatus {
                    Section {
                        Label(status, systemImage: "dot.radiowaves.left.and.right")
                            .font(.caption)
                    }
                }
            }
            .navigationTitle("dApps")
            .toolbar {
                ToolbarItem(placement: .primaryAction) {
                    Button {
                        showConnect = true
                    } label: {
                        Label("Connect", systemImage: "plus")
                    }
                }
            }
            .task { model.refreshWcSessions() }
            .sheet(isPresented: $showConnect) { ConnectSheet() }
            .sheet(item: $selected) { session in DappDetailSheet(session: session) }
        }
    }
}

/// One line of limits for the roster card.
func policySummary(_ policy: DappSpendPolicy?) -> String {
    guard let policy else { return "No limits set · every payment asks you" }
    var parts: [String] = []
    if let v = policy.maxPerTransaction { parts.append("max \(v) CC/payment") }
    if let v = policy.dailyCap { parts.append("\(v) CC/day") }
    if let v = policy.autoApproveBelow { parts.append("auto under \(v) CC") }
    return parts.isEmpty ? "No limits set · every payment asks you" : parts.joined(separator: " · ")
}

/// The pairing action, hosted in a sheet off the dApps roster.
struct ConnectSheet: View {
    @Environment(WalletModel.self) private var model
    @Environment(\.dismiss) private var dismiss
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
                        dismiss()
                    }
                    .disabled(!isPairable)
                } footer: {
                    Text("Scan or paste a WalletConnect link (wc:…) shown by an agent or dApp. You approve sharing your account and approve each signature — the key never leaves this device.")
                }
            }
            .navigationTitle("Connect an agent or dApp")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Cancel") { dismiss() }
                }
            }
            .sheet(isPresented: $showScanner) {
                QRScannerSheet { scanned in
                    let trimmed = scanned.trimmingCharacters(in: .whitespacesAndNewlines)
                    if trimmed.hasPrefix("wc:") {
                        model.pairWalletConnect(trimmed)
                        dismiss()
                    } else {
                        uri = trimmed
                    }
                }
            }
        }
    }
}

/// Per-dApp detail: the spend-policy editor and that peer's slice of the
/// activity feed. Policies take effect immediately; sessions read them fresh
/// on every request. Auto-approval is a per-dApp opt-in with the caps as its
/// hard bound, and the footer says exactly what it removes: the sheet.
struct DappDetailSheet: View {
    let session: WcSessionInfo
    @Environment(WalletModel.self) private var model
    @Environment(\.dismiss) private var dismiss
    @State private var seeded = false
    @State private var maxPerTx = ""
    @State private var dailyCap = ""
    @State private var autoApprove = false
    @State private var autoBelow = ""
    @State private var saved = false

    /// The token standard's decimal shape, positive; `Decimal(string:)` alone
    /// accepts trailing garbage.
    private func parsed(_ text: String) -> Decimal? {
        let t = text.trimmingCharacters(in: .whitespaces)
        guard !t.isEmpty, t.wholeMatch(of: /\d+(\.\d+)?/) != nil,
              let value = Decimal(string: t), value > 0 else { return nil }
        return value
    }
    private var maxValid: Bool { maxPerTx.isEmpty || parsed(maxPerTx) != nil }
    private var capValid: Bool { dailyCap.isEmpty || parsed(dailyCap) != nil }
    private var autoValid: Bool { !autoApprove || parsed(autoBelow) != nil }

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    Text(session.name).font(.headline)
                    if !session.url.isEmpty {
                        Text(session.url).font(.caption2).foregroundStyle(.secondary)
                    }
                }
                Section {
                    TextField("Max per payment (CC)", text: $maxPerTx)
                        .keyboardType(.decimalPad)
                        .foregroundStyle(maxValid ? Color.primary : Color.red)
                        .onChange(of: maxPerTx) { saved = false }
                    TextField("Daily cap, rolling 24h (CC)", text: $dailyCap)
                        .keyboardType(.decimalPad)
                        .foregroundStyle(capValid ? Color.primary : Color.red)
                        .onChange(of: dailyCap) { saved = false }
                } header: {
                    Text("Spending limits")
                } footer: {
                    Text("Hard limits this wallet enforces before anything reaches you. A request outside them is refused without asking; it still shows in Activity. Empty = no cap.")
                }
                Section {
                    Toggle("Auto-approve small payments", isOn: $autoApprove)
                        .onChange(of: autoApprove) { saved = false }
                    if autoApprove {
                        TextField("Auto-approve at or under (CC)", text: $autoBelow)
                            .keyboardType(.decimalPad)
                            .foregroundStyle(autoValid ? Color.primary : Color.red)
                            .onChange(of: autoBelow) { saved = false }
                    }
                } footer: {
                    Text("Payments at or under the amount execute with no approval sheet. You get a notification and an Activity entry instead.")
                }
                Section {
                    Button(saved ? "Saved" : "Save limits") {
                        let policy = DappSpendPolicy(
                            maxPerTransaction: parsed(maxPerTx),
                            dailyCap: parsed(dailyCap),
                            autoApproveBelow: autoApprove ? parsed(autoBelow) : nil
                        )
                        let empty = policy.maxPerTransaction == nil
                            && policy.dailyCap == nil && policy.autoApproveBelow == nil
                        model.setDappPolicy(session.stableId, empty ? nil : policy)
                        saved = true
                    }
                    .disabled(!(maxValid && capValid && autoValid))
                }
                let peerActivity = model.agentActivity.filter { $0.peerId == session.stableId }
                if !peerActivity.isEmpty {
                    Section("Activity") {
                        ForEach(Array(peerActivity.prefix(20).enumerated()), id: \.offset) { _, activity in
                            AgentActivityRow(activity: activity)
                        }
                    }
                }
                Section {
                    Button("Disconnect", role: .destructive) {
                        model.disconnectDapp(stableId: session.stableId)
                        dismiss()
                    }
                }
            }
            .navigationTitle(session.name)
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .confirmationAction) {
                    Button("Done") { dismiss() }
                }
            }
            .onAppear {
                guard !seeded else { return }
                seeded = true
                let existing = model.dappPolicy(session.stableId)
                maxPerTx = existing?.maxPerTransaction.map { "\($0)" } ?? ""
                dailyCap = existing?.dailyCap.map { "\($0)" } ?? ""
                autoApprove = existing?.autoApproveBelow != nil
                autoBelow = existing?.autoApproveBelow.map { "\($0)" } ?? ""
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
    static let transactionAccent = Color(red: 0.91, green: 0.31, blue: 0.18)

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
        VStack(alignment: .leading, spacing: 10) {
            // The request has a clock. Swiping the sheet away keeps it waiting
            // on the Activity tab; this is how long it can wait.
            Label {
                HStack(spacing: 4) {
                    Text("Expires in")
                    Text(approval.expiresAt, style: .timer)
                        .monospacedDigit()
                    Text("· swipe down to decide later")
                }
            } icon: {
                Image(systemName: "hourglass")
            }
            .font(.caption2)
            .foregroundStyle(.secondary)
            HStack {
                Button("Decline", role: .cancel) {
                    approval.resolve(.rejected(reason: "Declined"))
                }
                .buttonStyle(.bordered)
                Spacer()
                Button(approveTitle, action: onApprove)
                    .buttonStyle(.borderedProminent)
            }
        }
        .padding(.top, 8)
    }
}
