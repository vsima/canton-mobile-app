// Copyright (c) 2026 Victor Sima
// SPDX-License-Identifier: Apache-2.0

import CantonDappWalletKit
import CantonWalletKit
import SwiftUI

/// Filter chips on the Activity feed.
enum ActivityFilter: String, CaseIterable, Identifiable {
    case all = "All"
    case transfers = "Transfers"
    case requests = "Requests"
    case dapps = "dApps"
    var id: String { rawValue }
}

/// The Activity tab: one feed for everything that happened in the wallet.
/// Requests (the old Inbox) stay actionable, transfers (the old History)
/// keep their detail sheets, and agent events, including the spend policy's
/// sheetless outcomes, appear inline. Opening the tab clears the badge.
/// The iOS twin of Android's `ActivityScreen`.
struct ActivityView: View {
    @Environment(WalletModel.self) private var model
    @State private var filter: ActivityFilter = .all
    @State private var selected: TokenStandardClient.HoldingsChange?

    /// The offer leg of a two-step transfer nets to zero (holdings only
    /// lock); the settlement leg carries the value. Hide the zero-net noise.
    private var visibleTransfers: [TokenStandardClient.HoldingsChange] {
        model.history.filter { change in
            guard let summary = change.summary else { return true }
            return (Decimal(string: summary.amount) ?? 0) != 0
        }
    }

    private enum Entry: Identifiable {
        case transfer(TokenStandardClient.HoldingsChange)
        case agent(DappActivity, Int)

        var id: String {
            switch self {
            case .transfer(let change): "t-\(change.updateId)"
            case .agent(let activity, let index): "a-\(index)-\(activity.at.timeIntervalSince1970)"
            }
        }
        var at: Date {
            switch self {
            case .transfer(let change): change.recordTime
            case .agent(let activity, _): activity.at
            }
        }
    }

    /// One reverse-chronological feed across both sources.
    private var feed: [Entry] {
        var entries: [Entry] = []
        if filter != .dapps { entries += visibleTransfers.map(Entry.transfer) }
        if filter != .transfers {
            entries += model.agentActivity.enumerated().map { Entry.agent($0.element, $0.offset) }
        }
        return entries.sorted { $0.at > $1.at }
    }

    var body: some View {
        NavigationStack {
            VStack(spacing: 0) {
                Picker("Filter", selection: $filter) {
                    ForEach(ActivityFilter.allCases) { Text($0.rawValue).tag($0) }
                }
                .pickerStyle(.segmented)
                .padding([.horizontal, .top])
                .padding(.bottom, 8)
                List {
                    if filter == .requests {
                        if model.inbox.isEmpty, model.pendingApprovals.isEmpty {
                            Text("No pending requests.").foregroundStyle(.secondary)
                        }
                        pendingApprovalRows
                        ForEach(model.inbox, id: \.contractId) { offer in
                            InboxOfferRow(offer: offer)
                        }
                    } else {
                        // A request whose sheet was swiped away waits here,
                        // tappable, until it is answered or expires.
                        if filter == .all { pendingApprovalRows }
                        if filter == .all, !model.inbox.isEmpty {
                            Button {
                                filter = .requests
                            } label: {
                                Label {
                                    VStack(alignment: .leading, spacing: 2) {
                                        Text("\(model.inbox.count) pending request\(model.inbox.count == 1 ? "" : "s")")
                                        Text("Tap to review and accept or reject")
                                            .font(.caption)
                                            .foregroundStyle(.secondary)
                                    }
                                } icon: {
                                    Image(systemName: "tray.full").foregroundStyle(.tint)
                                }
                            }
                        }
                        if feed.isEmpty {
                            Text("No activity yet.").foregroundStyle(.secondary)
                        }
                        ForEach(feed) { entry in
                            switch entry {
                            case .transfer(let change):
                                Button {
                                    selected = change
                                } label: {
                                    HistoryRow(change: change)
                                }
                                .buttonStyle(.plain)
                            case .agent(let activity, _):
                                AgentActivityRow(activity: activity)
                            }
                        }
                    }
                }
                .listStyle(.insetGrouped)
            }
            // The same grouped canvas as Portfolio and dApps: the segmented
            // control's track and selected pill need a gray ground to read.
            .background(Color(.systemGroupedBackground))
            .navigationTitle("Activity")
            .refreshable { await model.refresh() }
            .sheet(item: $selected) { change in
                ChangeDetailView(change: change)
            }
            .onAppear { model.markAgentActivitySeen() }
        }
    }
}

extension ActivityView {
    @ViewBuilder
    fileprivate var pendingApprovalRows: some View {
        ForEach(model.pendingApprovals) { approval in
            Button {
                model.reopenApproval(approval.id)
            } label: {
                PendingApprovalRow(approval: approval)
            }
            .buttonStyle(.plain)
        }
    }
}

/// A WalletConnect request still waiting for an answer: its sheet was swiped
/// away (or another sheet was up when it arrived). Tapping brings the sheet
/// back; the countdown is the request's remaining life.
struct PendingApprovalRow: View {
    let approval: WalletModel.WcApproval

    private var style: (icon: String, tint: Color, title: String) {
        switch approval.request {
        case .connection: return ("link", Color.accentColor, "Connection request")
        case .message: return ("key", Color.purple, "Sign-in request")
        case .transaction(_, _, _, let submission):
            let amount = DappCommandSummary.transferOf(submission).map {
                "\($0.amount) \($0.instrumentId == "Amulet" ? "CC" : $0.instrumentId)"
            }
            return ("arrow.up.right.circle", Color.orange, "Payment request" + (amount.map { ": \($0)" } ?? ""))
        }
    }

    var body: some View {
        HStack(alignment: .top) {
            Image(systemName: style.icon)
                .foregroundStyle(style.tint)
                .frame(width: 24)
            VStack(alignment: .leading, spacing: 2) {
                Text(style.title)
                Text(approval.request.peer.name)
                    .font(.caption.weight(.medium))
                Text("Waiting for you · tap to review")
                    .font(.caption)
                    .foregroundStyle(.secondary)
                HStack(spacing: 4) {
                    Image(systemName: "hourglass")
                    Text("Expires in")
                    Text(approval.expiresAt, style: .timer).monospacedDigit()
                }
                .font(.caption2)
                .foregroundStyle(.secondary)
            }
            Spacer()
            Image(systemName: "chevron.right")
                .font(.caption)
                .foregroundStyle(.tertiary)
        }
    }
}

/// One agent-activity row: what a connected dApp did or tried, sheet or no
/// sheet. The silent kinds carry their own tints so refusals and
/// auto-approvals read at a glance.
struct AgentActivityRow: View {
    let activity: DappActivity

    private var amount: String? {
        activity.transfer.map { "\($0.amount) \($0.instrumentId == "Amulet" ? "CC" : $0.instrumentId)" }
    }

    private var style: (icon: String, tint: Color, title: String) {
        switch activity.kind {
        case .connected: ("link", Color.accentColor, "Connected")
        case .connectionDeclined: ("link", Color.secondary, "Connection declined")
        case .messageSigned: ("key", Color.purple, "Signed in")
        case .messageDeclined: ("key", Color.secondary, "Sign-in declined")
        case .transactionRequested: ("arrow.up.right.circle", Color.secondary, "Payment requested")
        case .transactionAutoApproved:
            ("arrow.up.right.circle", Color.green, "Auto-approved" + (amount.map { ": \($0)" } ?? ""))
        case .transactionRefused: ("arrow.up.right.circle", Color.red, "Refused by your policy")
        case .transactionRateLimited: ("arrow.up.right.circle", Color.red, "Rate-limited")
        case .transactionDeclined where activity.detail == WalletModel.expiredReason:
            ("arrow.up.right.circle", Color.secondary, "Payment request expired")
        case .transactionDeclined: ("arrow.up.right.circle", Color.secondary, "Payment declined")
        case .transactionExecuted:
            ("arrow.up.right.circle", Color.green, "Paid" + (amount.map { " \($0)" } ?? ""))
        case .transactionFailed: ("arrow.up.right.circle", Color.red, "Payment failed")
        }
    }

    /// Executed and auto-approved rows already carry the amount in the
    /// title; the others show amount + receiver on the detail line.
    private var transferLine: String? {
        guard let transfer = activity.transfer else { return nil }
        switch activity.kind {
        case .transactionExecuted, .transactionAutoApproved:
            return "to \(transfer.receiver.prefix(30))…"
        default:
            return "\(amount ?? "") to \(transfer.receiver.prefix(24))…"
        }
    }

    private var showsDetail: Bool {
        activity.kind != .transactionExecuted && activity.kind != .connected
    }

    var body: some View {
        HStack(alignment: .top) {
            Image(systemName: style.icon)
                .foregroundStyle(style.tint)
                .frame(width: 24)
            VStack(alignment: .leading, spacing: 2) {
                Text(style.title)
                Text(activity.peerName)
                    .font(.caption.weight(.medium))
                if let line = transferLine {
                    Text(line)
                        .font(.caption.monospaced())
                        .foregroundStyle(.secondary)
                }
                if showsDetail, let detail = activity.detail {
                    Text(detail)
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }
                Text(activity.at, style: .relative)
                    .font(.caption2)
                    .foregroundStyle(.secondary)
            }
        }
    }
}

/// One pending transfer offer with Accept / Reject; the old Inbox row.
struct InboxOfferRow: View {
    @Environment(WalletModel.self) private var model
    let offer: TransferInstruction

    var body: some View {
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
