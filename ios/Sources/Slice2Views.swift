// Copyright (c) 2026 Victor Sima
// SPDX-License-Identifier: Apache-2.0

import CantonWalletKit
import SwiftUI

/// Holdings changes as a wallet-history list, from the SDK's parsed
/// ACS-delta stream. Standard List with date-relative rows.
struct HistoryView: View {
    @Environment(WalletModel.self) private var model
    @State private var selected: TokenStandardClient.HoldingsChange?

    /// The offer leg of a two-step transfer nets to zero (holdings only
    /// lock); the settlement leg carries the value. Hide the zero-net noise
    /// from the list — the detail sheet still has everything.
    private var visible: [TokenStandardClient.HoldingsChange] {
        model.history.filter { change in
            guard let summary = change.summary else { return true }
            return (Decimal(string: summary.amount) ?? 0) != 0
        }
    }

    var body: some View {
        NavigationStack {
            List {
                if visible.isEmpty {
                    Text("No activity yet.")
                        .foregroundStyle(.secondary)
                }
                ForEach(visible, id: \.updateId) { change in
                    Button {
                        selected = change
                    } label: {
                        HistoryRow(change: change)
                    }
                    .buttonStyle(.plain)
                }
            }
            .navigationTitle("History")
            .refreshable { await model.refresh() }
            .sheet(item: $selected) { change in
                ChangeDetailView(change: change)
            }
        }
    }
}

/// One display label per history row and detail — from the SDK's transfer
/// summary when present, from the raw created/archived deltas otherwise.
/// `.unknown` with a positive net is how taps and preapproved direct
/// receives surface (no transfer view), so it reads "Received" — but the
/// row never invents a counterparty for it.
private func changeTitle(_ change: TokenStandardClient.HoldingsChange) -> String {
    guard let summary = change.summary else {
        return change.created.isEmpty ? "Sent / spent" : "Received"
    }
    switch summary.direction {
    case .sent: return "Sent"
    case .received: return "Received"
    case .selfTransfer: return "Sent to self"
    case .internal: return "Internal"
    case .unknown: return (Decimal(string: summary.amount) ?? 0) > 0 ? "Received" : "Activity"
    }
}

struct HistoryRow: View {
    let change: TokenStandardClient.HoldingsChange

    private var credited: Decimal {
        change.created.reduce(.zero) { $0 + (Decimal(string: $1.amount) ?? .zero) }
    }

    /// The summary's signed fee-inclusive net, when a summary exists.
    private var net: Decimal? {
        change.summary.flatMap { Decimal(string: $0.amount) }
    }

    private var received: Bool {
        guard let summary = change.summary else { return !change.created.isEmpty }
        guard let net, net > 0 else { return false }
        return summary.direction == .received || summary.direction == .unknown
    }

    private var iconName: String {
        switch change.summary?.direction {
        case .internal: "arrow.triangle.2.circlepath"
        case .unknown where !received: "arrow.left.arrow.right"
        default: received ? "arrow.down.left" : "arrow.up.right"
        }
    }

    var body: some View {
        HStack {
            Image(systemName: iconName)
                .foregroundStyle(received ? Color.green : Color.secondary)
            VStack(alignment: .leading) {
                Text(changeTitle(change))
                if let summary = change.summary, let counterparty = summary.counterparty {
                    Text("\(summary.direction == .sent ? "to" : "from") \(counterparty.prefix(30))…")
                        .font(.caption.monospaced())
                        .foregroundStyle(.secondary)
                }
                if let memo = change.summary?.memo, !memo.isEmpty {
                    Text("“\(memo)”")
                        .font(.caption)
                }
                Text(change.recordTime, style: .relative)
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }
            Spacer()
            VStack(alignment: .trailing) {
                if let net {
                    Text("\(net > 0 ? "+" : "")\(net as NSDecimalNumber, formatter: PortfolioView.amountFormat) CC")
                        .foregroundStyle(received ? Color.green : Color.primary)
                        .font(.body.monospacedDigit())
                } else {
                    if credited > 0 {
                        Text("+\(credited as NSDecimalNumber, formatter: PortfolioView.amountFormat) CC")
                            .foregroundStyle(.green)
                            .font(.body.monospacedDigit())
                    }
                    if !change.archivedContractIds.isEmpty {
                        Text("\(change.archivedContractIds.count) input\(change.archivedContractIds.count == 1 ? "" : "s") spent")
                            .font(.caption2)
                            .foregroundStyle(.secondary)
                    }
                }
            }
        }
    }
}

extension TokenStandardClient.HoldingsChange: @retroactive Identifiable {
    public var id: String { updateId }
}

/// Transaction detail: standard grouped Form with copyable identifiers.
struct ChangeDetailView: View {
    let change: TokenStandardClient.HoldingsChange
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            Form {
                Section("When") {
                    Text(change.recordTime.formatted(date: .abbreviated, time: .standard))
                }
                if let summary = change.summary {
                    Section("Transfer") {
                        LabeledContent("Direction", value: changeTitle(change))
                        if let counterparty = summary.counterparty {
                            VStack(alignment: .leading, spacing: 4) {
                                Text("Counterparty")
                                Text(counterparty)
                                    .font(.caption2.monospaced())
                                    .textSelection(.enabled)
                            }
                        }
                        if let memo = summary.memo, !memo.isEmpty {
                            LabeledContent("Memo", value: memo)
                        }
                    }
                }
                if !change.created.isEmpty {
                    Section("Credited") {
                        ForEach(change.created, id: \.contractId) { holding in
                            LabeledContent("\(holding.amount) \(holding.instrumentId.id)") {
                                Text(holding.contractId.prefix(16) + "…")
                                    .font(.caption2.monospaced())
                            }
                        }
                    }
                }
                if !change.archivedContractIds.isEmpty {
                    Section("Spent inputs") {
                        ForEach(change.archivedContractIds, id: \.self) { cid in
                            Text(cid.prefix(32) + "…")
                                .font(.caption2.monospaced())
                        }
                    }
                }
                Section("Update id") {
                    Text(change.updateId)
                        .font(.caption2.monospaced())
                        .textSelection(.enabled)
                }
            }
            .navigationTitle(changeTitle(change))
            .toolbar {
                ToolbarItem(placement: .confirmationAction) {
                    Button("Done") { dismiss() }
                }
            }
        }
    }
}

/// Post-send confirmation: the pattern users expect after committing money.
struct SendConfirmationView: View {
    let receipt: WalletModel.SendReceipt
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    VStack(spacing: 12) {
                        Image(systemName: "checkmark.circle.fill")
                            .font(.system(size: 56))
                            .foregroundStyle(.green)
                        Text("Transfer submitted")
                            .font(.headline)
                        Text("It settles instantly if the receiver is preapproved; otherwise it awaits their acceptance.")
                            .font(.footnote)
                            .foregroundStyle(.secondary)
                            .multilineTextAlignment(.center)
                    }
                    .frame(maxWidth: .infinity)
                    .padding(.vertical, 12)
                }
                Section {
                    LabeledContent(
                        "Amount",
                        value: "\(PortfolioView.amountFormat.string(from: receipt.amount as NSDecimalNumber) ?? "\(receipt.amount)") CC"
                    )
                    LabeledContent("To") {
                        Text(receipt.receiver.prefix(28) + "…")
                            .font(.caption.monospaced())
                    }
                    if !receipt.memo.isEmpty {
                        LabeledContent("Memo", value: receipt.memo)
                    }
                    LabeledContent("At", value: receipt.at.formatted(date: .omitted, time: .standard))
                }
            }
            .navigationTitle("Sent")
            .toolbar {
                ToolbarItem(placement: .confirmationAction) {
                    Button("Done") { dismiss() }
                }
            }
        }
    }
}

/// The trust surface: what "Signer: Secure Enclave" actually guarantees —
/// the differentiator iCloud-synced-key wallets cannot claim.
struct SignerDetailView: View {
    @Environment(WalletModel.self) private var model
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    Label(model.signerLabel, systemImage: "lock.shield.fill")
                        .font(.headline)
                }
                Section("What this means") {
                    if model.signerLabel.contains("Enclave") && !model.signerLabel.contains("Simulated") {
                        Text("Your signing key was generated inside this device's Secure Enclave. It cannot be exported, synced, backed up, or read — by this app, by Apple, or by anyone. Every transaction is signed by the enclave itself.")
                        Text("Keys that sync between devices (e.g. via iCloud Keychain) leave the hardware as encrypted blobs. This one never does.")
                            .foregroundStyle(.secondary)
                    } else {
                        Text("This is a software key for development in the simulator, which has no Secure Enclave. On a physical device the key is enclave-resident and non-exportable.")
                    }
                }
                Section("Your party") {
                    Text(model.partyId ?? "—")
                        .font(.caption2.monospaced())
                        .textSelection(.enabled)
                }
                Section("Network") {
                    LabeledContent("Environment", value: model.environment.name)
                    LabeledContent("Participant", value: "\(model.environment.ledgerHost):\(String(model.environment.ledgerPort))")
                    Text("DevNet and bring-your-own-validator arrive with real authentication flows.")
                        .font(.footnote)
                        .foregroundStyle(.secondary)
                }
            }
            .navigationTitle("Security")
            .toolbar {
                ToolbarItem(placement: .confirmationAction) {
                    Button("Done") { dismiss() }
                }
            }
        }
    }
}

/// "Receive instantly": preapproval as a user-facing feature.
struct InstantReceiveSection: View {
    @Environment(WalletModel.self) private var model

    var body: some View {
        Section {
            if model.preapprovalRequested && model.preapproval == nil {
                Label("Waiting for your validator to approve…", systemImage: "hourglass")
                    .foregroundStyle(.secondary)
            } else {
                Toggle(isOn: Binding(
                    get: { model.preapproval != nil },
                    set: { on in
                        Task {
                            if on {
                                await model.requestInstantReceive()
                            } else {
                                await model.cancelInstantReceive()
                            }
                        }
                    }
                )) {
                    Label("Instant receiving", systemImage: "bolt")
                }
                .disabled(model.busy)
                if let expiresAt = model.preapproval?.expiresAt {
                    LabeledContent("Renews", value: expiresAt.formatted(date: .abbreviated, time: .omitted))
                }
            }
        } header: {
            Text("Receive instantly")
        } footer: {
            Text(model.preapproval != nil
                ? "Transfers to you settle in one step — no acceptance needed. Turning this off archives the preapproval, signed on-device."
                : "Asks your validator to preapprove incoming transfers, so they settle without an inbox step. Signed on-device.")
        }
    }
}
