// Copyright (c) 2026 Victor Sima
// SPDX-License-Identifier: Apache-2.0

import CantonDappWalletKit
import Foundation

/// The receipts file could not be read. Never swallowed: an unreadable
/// ledger read as empty would silently reset every spend cap.
struct AgentStoreUnreadableError: Error, CustomStringConvertible {
    let description: String
}

/// App-private persistence for the agent surface: per-dApp spend policies,
/// the activity feed, and the spend-receipt ledger the SDK's caps read.
/// The iOS twin of Android's `AgentStore`, with the same on-disk shapes.
///
/// Files live in the app's Application Support directory. The receipts
/// ledger is fail-loud per `SpendLedger`'s contract; the activity feed is
/// display-only, so a corrupt line there is dropped, not fatal. All I/O is
/// synchronous and small, guarded by one lock.
final class AgentStore: @unchecked Sendable {
    private let lock = NSLock()
    private let policiesFile: URL
    private let activityFile: URL
    private let receiptsFile: URL

    private static let maxActivityLines = 300

    init(directory: URL? = nil) {
        let dir = directory ?? {
            let base = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
            return base.appendingPathComponent("canton-wallet-agent", isDirectory: true)
        }()
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        policiesFile = dir.appendingPathComponent("agent-policies.json")
        activityFile = dir.appendingPathComponent("agent-activity.jsonl")
        receiptsFile = dir.appendingPathComponent("agent-receipts.jsonl")
    }

    // MARK: - Policies

    /// The stored policy for one peer, or nil (no policy: everything asks).
    func policy(_ peerId: String) -> DappSpendPolicy? { policies()[peerId] }

    func policies() -> [String: DappSpendPolicy] {
        lock.withLock {
            guard let data = try? Data(contentsOf: policiesFile),
                  let root = try? JSONSerialization.jsonObject(with: data) as? [String: Any]
            else { return [:] }
            var out: [String: DappSpendPolicy] = [:]
            for (peerId, value) in root {
                if let json = value as? [String: Any] { out[peerId] = Self.policy(from: json) }
            }
            return out
        }
    }

    func setPolicy(_ peerId: String, _ policy: DappSpendPolicy?) {
        var updated = policies()
        if let policy { updated[peerId] = policy } else { updated.removeValue(forKey: peerId) }
        lock.withLock {
            let root = updated.mapValues { Self.json(for: $0) }
            if let data = try? JSONSerialization.data(withJSONObject: root, options: [.sortedKeys]) {
                try? data.write(to: policiesFile, options: .atomic)
            }
        }
    }

    private static func json(for policy: DappSpendPolicy) -> [String: Any] {
        var json: [String: Any] = [:]
        if let v = policy.maxPerTransaction { json["maxPerTransaction"] = "\(v)" }
        if let v = policy.dailyCap { json["dailyCap"] = "\(v)" }
        if let v = policy.allowedInstruments { json["allowedInstruments"] = v.sorted().joined(separator: ",") }
        if let v = policy.allowedReceivers { json["allowedReceivers"] = v.sorted().joined(separator: ",") }
        if policy.minRequestInterval > 0 { json["minRequestIntervalSecs"] = Int(policy.minRequestInterval) }
        if let v = policy.autoApproveBelow { json["autoApproveBelow"] = "\(v)" }
        return json
    }

    private static func policy(from json: [String: Any]) -> DappSpendPolicy {
        func decimal(_ key: String) -> Decimal? { (json[key] as? String).flatMap { Decimal(string: $0) } }
        func set(_ key: String) -> Set<String>? {
            (json[key] as? String).map { Set($0.split(separator: ",").map(String.init).filter { !$0.isEmpty }) }
        }
        return DappSpendPolicy(
            maxPerTransaction: decimal("maxPerTransaction"),
            dailyCap: decimal("dailyCap"),
            allowedInstruments: set("allowedInstruments"),
            allowedReceivers: set("allowedReceivers"),
            minRequestInterval: TimeInterval((json["minRequestIntervalSecs"] as? Int) ?? 0),
            autoApproveBelow: decimal("autoApproveBelow")
        )
    }

    // MARK: - Activity feed

    /// Oldest first. Corrupt lines are skipped: the feed is a record for
    /// display, not an input to any decision.
    func activity() -> [DappActivity] {
        lock.withLock {
            guard let text = try? String(contentsOf: activityFile, encoding: .utf8) else { return [] }
            return text.split(separator: "\n").compactMap { line in
                guard let data = line.data(using: .utf8),
                      let json = try? JSONSerialization.jsonObject(with: data) as? [String: Any]
                else { return nil }
                return Self.activity(from: json)
            }
        }
    }

    func appendActivity(_ activity: DappActivity) {
        lock.withLock {
            Self.appendLine(Self.json(for: activity), to: activityFile)
            // Keep the file bounded; the feed is recent history, not an archive.
            if let text = try? String(contentsOf: activityFile, encoding: .utf8) {
                let lines = text.split(separator: "\n")
                if lines.count > Self.maxActivityLines {
                    let kept = lines.suffix(Self.maxActivityLines).joined(separator: "\n") + "\n"
                    try? kept.write(to: activityFile, atomically: true, encoding: .utf8)
                }
            }
        }
    }

    private static func json(for a: DappActivity) -> [String: Any] {
        var json: [String: Any] = [
            "peerId": a.peerId,
            "peerName": a.peerName,
            "atMillis": Int64(a.at.timeIntervalSince1970 * 1000),
            "kind": name(of: a.kind),
        ]
        if let d = a.detail { json["detail"] = d }
        if let t = a.transfer {
            json["transferReceiver"] = t.receiver
            json["transferAmount"] = t.amount
            json["transferInstrument"] = t.instrumentId
            if let memo = t.memo { json["transferMemo"] = memo }
        }
        return json
    }

    private static func activity(from json: [String: Any]) -> DappActivity? {
        guard let peerId = json["peerId"] as? String,
              let peerName = json["peerName"] as? String,
              let millis = json["atMillis"] as? Int64 ?? (json["atMillis"] as? Int).map(Int64.init),
              let kindName = json["kind"] as? String,
              let kind = kind(named: kindName)
        else { return nil }
        var transfer: DappTransferSummary?
        if let amount = json["transferAmount"] as? String,
           let receiver = json["transferReceiver"] as? String,
           let instrument = json["transferInstrument"] as? String {
            transfer = DappTransferSummary(
                receiver: receiver, amount: amount, instrumentId: instrument,
                memo: json["transferMemo"] as? String
            )
        }
        return DappActivity(
            peerId: peerId,
            peerName: peerName,
            at: Date(timeIntervalSince1970: TimeInterval(millis) / 1000),
            kind: kind,
            transfer: transfer,
            detail: json["detail"] as? String
        )
    }

    /// Wire names shared with the Android store, so a feed reads the same
    /// on either platform's tooling.
    static func name(of kind: DappActivity.Kind) -> String {
        switch kind {
        case .connected: "CONNECTED"
        case .connectionDeclined: "CONNECTION_DECLINED"
        case .messageSigned: "MESSAGE_SIGNED"
        case .messageDeclined: "MESSAGE_DECLINED"
        case .transactionRequested: "TRANSACTION_REQUESTED"
        case .transactionAutoApproved: "TRANSACTION_AUTO_APPROVED"
        case .transactionRefused: "TRANSACTION_REFUSED"
        case .transactionRateLimited: "TRANSACTION_RATE_LIMITED"
        case .transactionDeclined: "TRANSACTION_DECLINED"
        case .transactionExecuted: "TRANSACTION_EXECUTED"
        case .transactionFailed: "TRANSACTION_FAILED"
        }
    }

    static func kind(named name: String) -> DappActivity.Kind? {
        switch name {
        case "CONNECTED": .connected
        case "CONNECTION_DECLINED": .connectionDeclined
        case "MESSAGE_SIGNED": .messageSigned
        case "MESSAGE_DECLINED": .messageDeclined
        case "TRANSACTION_REQUESTED": .transactionRequested
        case "TRANSACTION_AUTO_APPROVED": .transactionAutoApproved
        case "TRANSACTION_REFUSED": .transactionRefused
        case "TRANSACTION_RATE_LIMITED": .transactionRateLimited
        case "TRANSACTION_DECLINED": .transactionDeclined
        case "TRANSACTION_EXECUTED": .transactionExecuted
        case "TRANSACTION_FAILED": .transactionFailed
        default: nil
        }
    }

    // MARK: - Receipts (the SDK's cap accounting)

    /// The `SpendLedger` the wallet's sessions read caps from and write
    /// executed spends to. Fail-loud on read, per the seam's contract.
    var receiptsLedger: any SpendLedger { FileSpendLedger(store: self) }

    fileprivate func appendReceipt(_ r: SpendReceipt) {
        lock.withLock {
            Self.appendLine(
                [
                    "peerId": r.peerId,
                    "atMillis": Int64(r.at.timeIntervalSince1970 * 1000),
                    "instrumentId": r.instrumentId,
                    "amount": "\(r.amount)",
                    "receiver": r.receiver,
                    "autoApproved": r.autoApproved,
                    "commandId": r.commandId,
                ],
                to: receiptsFile
            )
        }
    }

    fileprivate func receipts(peerId: String, since: Date) throws -> [SpendReceipt] {
        try lock.withLock {
            guard FileManager.default.fileExists(atPath: receiptsFile.path) else { return [] }
            guard let text = try? String(contentsOf: receiptsFile, encoding: .utf8) else {
                throw AgentStoreUnreadableError(
                    description: "the spend-receipt ledger at \(receiptsFile.path) is unreadable; refusing to treat it as empty"
                )
            }
            var out: [SpendReceipt] = []
            for line in text.split(separator: "\n") where !line.isEmpty {
                guard let data = line.data(using: .utf8),
                      let json = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
                      let pid = json["peerId"] as? String,
                      let millis = json["atMillis"] as? Int64 ?? (json["atMillis"] as? Int).map(Int64.init),
                      let instrument = json["instrumentId"] as? String,
                      let amount = (json["amount"] as? String).flatMap({ Decimal(string: $0) }),
                      let receiver = json["receiver"] as? String,
                      let auto = json["autoApproved"] as? Bool,
                      let commandId = json["commandId"] as? String
                else {
                    throw AgentStoreUnreadableError(
                        description: "corrupt line in the spend-receipt ledger at \(receiptsFile.path)"
                    )
                }
                let at = Date(timeIntervalSince1970: TimeInterval(millis) / 1000)
                if pid == peerId, at >= since {
                    out.append(SpendReceipt(
                        peerId: pid, at: at, instrumentId: instrument, amount: amount,
                        receiver: receiver, autoApproved: auto, commandId: commandId
                    ))
                }
            }
            return out
        }
    }

    private static func appendLine(_ json: [String: Any], to file: URL) {
        guard let data = try? JSONSerialization.data(withJSONObject: json, options: [.sortedKeys]) else { return }
        var line = data
        line.append(0x0A)
        if let handle = try? FileHandle(forWritingTo: file) {
            defer { try? handle.close() }
            try? handle.seekToEnd()
            try? handle.write(contentsOf: line)
        } else {
            try? line.write(to: file, options: .atomic)
        }
    }
}

/// `SpendLedger` over the store's receipts file.
private struct FileSpendLedger: SpendLedger {
    let store: AgentStore
    func append(_ receipt: SpendReceipt) async throws { store.appendReceipt(receipt) }
    func receiptsSince(peerId: String, since: Date) async throws -> [SpendReceipt] {
        try store.receipts(peerId: peerId, since: since)
    }
}
