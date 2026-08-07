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

    var id: String { rawValue }
    var icon: String {
        switch self {
        case .portfolio: "creditcard"
        case .inbox: "tray"
        case .send: "paperplane"
        case .receive: "qrcode"
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
                    view(for: section ?? .portfolio)
                        .frame(maxWidth: 560)
                        .frame(maxWidth: .infinity)
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

    var body: some View {
        NavigationStack {
            List {
                Section {
                    VStack(alignment: .leading, spacing: 4) {
                        Text("\(model.totalAmulet as NSDecimalNumber, formatter: Self.amountFormat) CC")
                            .font(.system(size: 34, weight: .bold, design: .rounded))
                        Text("Signer: \(model.signerLabel)")
                            .font(.caption)
                            .foregroundStyle(.secondary)
                    }
                    .padding(.vertical, 8)
                }
                Section("Holdings") {
                    if model.holdings.isEmpty {
                        Text("No holdings yet — receive CC to get started.")
                            .foregroundStyle(.secondary)
                    }
                    ForEach(model.holdings, id: \.contractId) { holding in
                        HStack {
                            VStack(alignment: .leading) {
                                Text("\(holding.amount) \(holding.instrumentId.id)")
                                    .font(.body.monospacedDigit())
                                Text(holding.contractId.prefix(24) + "…")
                                    .font(.caption2.monospaced())
                                    .foregroundStyle(.secondary)
                            }
                            Spacer()
                            if holding.lock != nil {
                                Image(systemName: "lock.fill")
                                    .foregroundStyle(.secondary)
                            }
                        }
                    }
                }
                if let error = model.lastError {
                    Section {
                        Text(error).font(.caption2).foregroundStyle(.red)
                    }
                }
            }
            .navigationTitle(model.environment.name)
            .refreshable { await model.refresh() }
        }
    }

    static let amountFormat: NumberFormatter = {
        let format = NumberFormatter()
        format.minimumFractionDigits = 1
        format.maximumFractionDigits = 4
        return format
    }()
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
                        Text("\(offer.transfer.amount) \(offer.transfer.instrumentId.id)")
                            .font(.headline.monospacedDigit())
                        Text("from \(offer.transfer.sender.prefix(30))…")
                            .font(.caption.monospaced())
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
                        .disabled(model.busy)
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

    var body: some View {
        NavigationStack {
            Form {
                Section("To") {
                    TextField("party id", text: $receiver, axis: .vertical)
                        .font(.caption.monospaced())
                        .autocorrectionDisabled()
                        .textInputAutocapitalization(.never)
                }
                Section("Amount (CC)") {
                    TextField("0.0", text: $amount)
                        .keyboardType(.decimalPad)
                }
                Button(model.busy ? "Sending…" : "Send") {
                    Task {
                        if let value = Decimal(string: amount) {
                            await model.send(to: receiver.trimmingCharacters(in: .whitespacesAndNewlines), amount: value)
                            amount = ""
                        }
                    }
                }
                .disabled(model.busy || receiver.isEmpty || Decimal(string: amount) == nil)
            }
            .navigationTitle("Send")
        }
    }
}

struct ReceiveView: View {
    @Environment(WalletModel.self) private var model

    var body: some View {
        NavigationStack {
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
                Text("Senders create a transfer to this party; it arrives in your Inbox to accept — or instantly once preapprovals are set up.")
                    .font(.footnote)
                    .foregroundStyle(.secondary)
                    .multilineTextAlignment(.center)
            }
            .padding(24)
            .navigationTitle("Receive")
        }
    }
}
