// Copyright (c) 2026 Victor Sima
// SPDX-License-Identifier: Apache-2.0

import CantonWalletKit
import SwiftUI

@main
struct CantonApp: App {
    @State private var model = WalletModel()

    var body: some Scene {
        WindowGroup {
            WalletRootView()
                .environment(model)
        }
    }
}

struct WalletRootView: View {
    @Environment(WalletModel.self) private var model

    var body: some View {
        Group {
            switch model.phase {
            case .fresh, .onboarding:
                OnboardingView()
            case .ready:
                WalletTabsView()
            case .failed(let message):
                ConnectionFailedView(message: message)
            }
        }
        .task { await model.onboard() }
    }
}

struct OnboardingView: View {
    @Environment(WalletModel.self) private var model

    var body: some View {
        VStack(spacing: 16) {
            ProgressView()
            Text(model.phase == .onboarding ? "Creating your wallet…" : "Starting…")
                .font(.headline)
            Text("A signing key is being generated on this device and registered as your Canton party. The key never leaves the device.")
                .font(.footnote)
                .foregroundStyle(.secondary)
                .multilineTextAlignment(.center)
        }
        .padding(32)
    }
}

struct ConnectionFailedView: View {
    @Environment(WalletModel.self) private var model
    let message: String

    var body: some View {
        VStack(spacing: 12) {
            Image(systemName: "wifi.exclamationmark")
                .font(.largeTitle)
                .foregroundStyle(.orange)
            Text("Can't reach \(model.environment.name)")
                .font(.headline)
            Text(message)
                .font(.caption2.monospaced())
                .foregroundStyle(.secondary)
                .lineLimit(6)
        }
        .padding(32)
    }
}

enum WalletSection: String, CaseIterable, Identifiable {
    case portfolio = "Portfolio"
    case inbox = "Inbox"
    case send = "Send"
    case receive = "Receive"
    case history = "History"

    var id: String { rawValue }
    var icon: String {
        switch self {
        case .portfolio: "creditcard"
        case .inbox: "tray"
        case .send: "paperplane"
        case .receive: "qrcode"
        case .history: "clock"
        }
    }
}

/// Adaptive shell: tabs on iPhone, a real sidebar split view on iPad and
/// Mac — regular-width layouts are first-class, not a stretched phone.
struct WalletTabsView: View {
    @Environment(WalletModel.self) private var model
    @Environment(\.horizontalSizeClass) private var sizeClass
    @State private var section: WalletSection? = .portfolio

    var body: some View {
        Group {
            if sizeClass == .regular {
                NavigationSplitView {
                    List(WalletSection.allCases, selection: $section) { item in
                        Label(item.rawValue, systemImage: item.icon)
                            .badge(item == .inbox ? model.inbox.count : 0)
                            .tag(item)
                    }
                    .navigationTitle(model.environment.name)
                } detail: {
                    // Grouped lists are designed for full pane width (cf.
                    // Settings on iPad) — no artificial column.
                    view(for: section ?? .portfolio)
                }
            } else {
                TabView {
                    PortfolioView()
                        .tabItem { Label("Portfolio", systemImage: "creditcard") }
                    InboxView()
                        .tabItem { Label("Inbox", systemImage: "tray") }
                        .badge(model.inbox.count)
                    SendView()
                        .tabItem { Label("Send", systemImage: "paperplane") }
                    ReceiveView()
                        .tabItem { Label("Receive", systemImage: "qrcode") }
                    HistoryView()
                        .tabItem { Label("History", systemImage: "clock") }
                }
            }
        }
        .task { await autoRefresh() }
    }

    @ViewBuilder
    private func view(for section: WalletSection) -> some View {
        switch section {
        case .portfolio: PortfolioView()
        case .inbox: InboxView()
        case .send: SendView()
        case .receive: ReceiveView()
        case .history: HistoryView()
        }
    }

    /// Polls while the app is foregrounded; the demo loop's inbox-and-accept
    /// runs headlessly when WALLET_AUTO_ACCEPT=1 (verification only).
    private func autoRefresh() async {
        let autoAccept = ProcessInfo.processInfo.environment["WALLET_AUTO_ACCEPT"] == "1"
        while !Task.isCancelled {
            await model.refresh()
            if autoAccept, let offer = model.inbox.first {
                await model.accept(offer)
            }
            try? await Task.sleep(for: .seconds(3))
        }
    }
}

struct PortfolioView: View {
    @Environment(WalletModel.self) private var model
    @State private var showSigner = false
    // Progressive disclosure of the UTXO model: the rolled-up balance row
    // opens a sheet listing the discrete holding contracts backing it.
    @State private var selectedGroup: HoldingGroup?

    var body: some View {
        NavigationStack {
            List {
                Section {
                    VStack(alignment: .leading, spacing: 4) {
                        Text("\(model.totalAmulet as NSDecimalNumber, formatter: Self.amountFormat) CC")
                            .font(.system(size: 34, weight: .bold, design: .rounded))
                        Button {
                            showSigner = true
                        } label: {
                            Label(model.signerLabel, systemImage: "lock.shield")
                                .font(.caption)
                        }
                        .buttonStyle(.bordered)
                        .controlSize(.small)
                    }
                    .padding(.vertical, 8)
                }
                Section {
                    if model.holdings.isEmpty {
                        VStack(alignment: .leading, spacing: 12) {
                            Text("No holdings yet — receive CC to get started.")
                                .foregroundStyle(.secondary)
                            // Dev-network faucet (LocalNet/DevNet): lets an
                            // empty wallet fund itself. See
                            // WalletModel.getTestFunds.
                            GetTestFundsButton(prominent: true)
                        }
                        .padding(.vertical, 4)
                    }
                    ForEach(holdingGroups) { group in
                        Button {
                            selectedGroup = group
                        } label: {
                            HStack {
                                VStack(alignment: .leading) {
                                    Text("\(group.amount as NSDecimalNumber, formatter: Self.amountFormat) \(group.label)")
                                        .font(.body.monospacedDigit())
                                    Text(group.contractId.map { String($0.prefix(24)) + "…" }
                                        ?? "\(group.count) holding contracts")
                                        .font(.caption2.monospaced())
                                        .foregroundStyle(.secondary)
                                }
                                Spacer()
                                if group.locked {
                                    Image(systemName: "lock.fill")
                                        .foregroundStyle(.secondary)
                                }
                            }
                        }
                        .buttonStyle(.plain)
                    }
                } header: {
                    // The faucet affordance lives beside the Holdings header
                    // once funded (low-key), and in the empty state above
                    // (prominent) — mirrored on Android.
                    HStack {
                        Text("Holdings")
                        Spacer()
                        if !model.holdings.isEmpty {
                            GetTestFundsButton(prominent: false)
                        }
                    }
                }
                if let error = model.lastError {
                    Section {
                        Text(error).font(.caption2).foregroundStyle(.red)
                    }
                }
            }
            .navigationTitle("Portfolio")
            .refreshable { await model.refresh() }
            .sheet(isPresented: $showSigner) {
                SignerDetailView()
            }
            .sheet(item: $selectedGroup) { group in
                HoldingGroupDetailView(group: group)
            }
        }
    }

    /// Holdings are discrete UTXO-style contracts on the ledger; the
    /// portfolio rolls them up to one row per instrument (locked apart).
    fileprivate struct HoldingGroup: Identifiable {
        let id: String
        let label: String
        let amount: Decimal
        let count: Int
        let contractId: String?
        let locked: Bool
        let holdings: [Holding]
    }

    private var holdingGroups: [HoldingGroup] {
        Dictionary(grouping: model.holdings) { "\($0.instrumentId.id)|\($0.lock != nil)" }
            .map { id, group in
                HoldingGroup(
                    id: id,
                    label: group[0].instrumentId.id,
                    amount: group.reduce(Decimal.zero) { $0 + (Decimal(string: $1.amount) ?? 0) },
                    count: group.count,
                    contractId: group.count == 1 ? group[0].contractId : nil,
                    locked: group[0].lock != nil,
                    holdings: group
                )
            }
            .sorted { ($0.locked ? 1 : 0, $0.label) < ($1.locked ? 1 : 0, $1.label) }
    }

    static let amountFormat: NumberFormatter = {
        let format = NumberFormatter()
        format.minimumFractionDigits = 1
        format.maximumFractionDigits = 4
        return format
    }()
}

/// "Get test funds": the dev-network faucet affordance (see
/// WalletModel.getTestFunds). Prominent in the Portfolio empty state,
/// low-key beside the Holdings header once funded; inline progress while
/// the faucet runs; failures surface through the model's lastError.
private struct GetTestFundsButton: View {
    @Environment(WalletModel.self) private var model
    let prominent: Bool

    var body: some View {
        if prominent {
            Button {
                Task { await model.getTestFunds() }
            } label: {
                label
            }
            .buttonStyle(.borderedProminent)
            .disabled(model.funding)
        } else {
            Button {
                Task { await model.getTestFunds() }
            } label: {
                label.font(.footnote)
            }
            .buttonStyle(.borderless)
            .textCase(nil)
            .disabled(model.funding)
        }
    }

    private var label: some View {
        HStack(spacing: 6) {
            if model.funding {
                ProgressView()
                    .controlSize(.small)
            }
            Text(model.funding ? "Getting test funds…" : "Get test funds")
        }
    }
}

/// The contracts backing a rolled-up holdings row: the balance is not a
/// number in an account but discrete holding contracts (Amulets), consumed
/// whole and merged into change on sends.
private struct HoldingGroupDetailView: View {
    let group: PortfolioView.HoldingGroup
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            Form {
                Section("Backed by \(group.count) contract\(group.count == 1 ? "" : "s")") {
                    ForEach(group.holdings, id: \.contractId) { holding in
                        HoldingContractRow(holding: holding, label: group.label)
                    }
                }
            }
            .navigationTitle(group.locked ? "\(group.label) (locked)" : group.label)
            .toolbar {
                ToolbarItem(placement: .confirmationAction) {
                    Button("Done") { dismiss() }
                }
            }
        }
    }
}

/// One backing holding contract (Amulet): amount plus shortened contract id;
/// tapping the row reveals the full id, selectable for copying.
private struct HoldingContractRow: View {
    let holding: Holding
    let label: String
    @State private var expanded = false

    var body: some View {
        Button {
            withAnimation { expanded.toggle() }
        } label: {
            HStack {
                VStack(alignment: .leading, spacing: 4) {
                    Text("\((Decimal(string: holding.amount) ?? 0) as NSDecimalNumber, formatter: PortfolioView.amountFormat) \(label)")
                        .font(.body.monospacedDigit())
                    Text(holding.contractId.prefix(24) + "…")
                        .font(.caption2.monospaced())
                        .foregroundStyle(.secondary)
                }
                Spacer()
                Image(systemName: expanded ? "chevron.up" : "chevron.down")
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }
        }
        .buttonStyle(.plain)
        if expanded {
            Text(holding.contractId)
                .font(.caption2.monospaced())
                .textSelection(.enabled)
        }
    }
}

struct InboxView: View {
    @Environment(WalletModel.self) private var model

    var body: some View {
        NavigationStack {
            List {
                if model.inbox.isEmpty {
                    Text("No pending offers.")
                        .foregroundStyle(.secondary)
                }
                ForEach(model.inbox, id: \.contractId) { offer in
                    VStack(alignment: .leading, spacing: 8) {
                        Text("\((Decimal(string: offer.transfer.amount) ?? 0) as NSDecimalNumber, formatter: PortfolioView.amountFormat) \(offer.transfer.instrumentId.id)")
                            .font(.headline.monospacedDigit())
                        Text("from \(offer.transfer.sender.prefix(30))…")
                            .font(.caption.monospaced())
                            .foregroundStyle(.secondary)
                        if let memo = offer.transfer.meta[WalletModel.memoKey], !memo.isEmpty {
                            Label(memo, systemImage: "text.quote")
                                .font(.caption)
                        }
                        Label {
                            Text("Expires \(offer.transfer.executeBefore, style: .relative)")
                        } icon: {
                            Image(systemName: "hourglass")
                        }
                        .font(.caption2)
                        .foregroundStyle(.secondary)
                        HStack {
                            Button("Accept") {
                                Task { await model.accept(offer) }
                            }
                            .buttonStyle(.borderedProminent)
                            Button("Reject", role: .destructive) {
                                Task { await model.reject(offer) }
                            }
                            .buttonStyle(.bordered)
                        }
                        .disabled(model.processing.contains(offer.contractId))
                    }
                    .padding(.vertical, 4)
                }
            }
            .navigationTitle("Inbox")
            .refreshable { await model.refresh() }
        }
    }
}

struct SendView: View {
    @Environment(WalletModel.self) private var model
    @State private var receiver = ""
    @State private var amount = ""
    @State private var memo = ""
    @State private var showScanner = false

    var body: some View {
        @Bindable var model = model
        NavigationStack {
            Form {
                Section("To") {
                    HStack(alignment: .top) {
                        TextField("party id", text: $receiver, axis: .vertical)
                            .font(.caption.monospaced())
                            .autocorrectionDisabled()
                            .textInputAutocapitalization(.never)
                        Button {
                            showScanner = true
                        } label: {
                            Image(systemName: "qrcode.viewfinder")
                        }
                        .buttonStyle(.borderless)
                        .accessibilityLabel("Scan QR code")
                    }
                }
                Section {
                    TextField("0.0", text: $amount)
                        .keyboardType(.decimalPad)
                } header: {
                    Text("Amount (CC)")
                } footer: {
                    HStack {
                        Text("Available: \(model.totalAmulet as NSDecimalNumber, formatter: PortfolioView.amountFormat) CC")
                        Spacer()
                        Button("Max") { amount = "\(model.totalAmulet)" }
                            .font(.caption)
                    }
                }
                Section("Memo (optional)") {
                    TextField("What's it for?", text: $memo)
                }
                Button(model.busy ? "Sending…" : "Send") {
                    Task {
                        if let value = Decimal(string: amount) {
                            await model.send(
                                to: receiver.trimmingCharacters(in: .whitespacesAndNewlines),
                                amount: value,
                                memo: memo
                            )
                            amount = ""
                            memo = ""
                        }
                    }
                }
                .disabled(
                    model.busy
                        || receiver.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
                        || Decimal(string: amount) == nil
                )
            }
            .navigationTitle("Send")
            .sheet(item: $model.lastSend) { receipt in
                SendConfirmationView(receipt: receipt)
            }
            .sheet(isPresented: $showScanner) {
                QRScannerSheet { receiver = $0 }
            }
        }
    }
}

struct ReceiveView: View {
    @Environment(WalletModel.self) private var model

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    receiveCard
                        .frame(maxWidth: .infinity)
                        .listRowBackground(Color.clear)
                }
                InstantReceiveSection()
            }
            .navigationTitle("Receive")
        }
    }

    private var receiveCard: some View {
            VStack(spacing: 20) {
                if let partyId = model.partyId, let qr = QRCode.image(for: partyId) {
                    Image(uiImage: qr)
                        .interpolation(.none)
                        .resizable()
                        .scaledToFit()
                        .frame(width: 220, height: 220)
                        .padding(8)
                        .background(.white, in: RoundedRectangle(cornerRadius: 16))
                }
                Text("Your party id")
                    .font(.headline)
                Text(model.partyId ?? "—")
                    .font(.caption.monospaced())
                    .multilineTextAlignment(.center)
                    .textSelection(.enabled)
                    .padding()
                    .background(.quaternary, in: RoundedRectangle(cornerRadius: 12))
                Button {
                    UIPasteboard.general.string = model.partyId
                } label: {
                    Label("Copy", systemImage: "doc.on.doc")
                }
                .buttonStyle(.bordered)
                Text("Senders create a transfer to this party; it arrives in your Inbox to accept — or instantly with preapproval below.")
                    .font(.footnote)
                    .foregroundStyle(.secondary)
                    .multilineTextAlignment(.center)
            }
            .padding(.vertical, 8)
    }
}
