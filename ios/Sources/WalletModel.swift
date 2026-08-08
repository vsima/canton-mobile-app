// Copyright (c) 2026 Victor Sima
// SPDX-License-Identifier: Apache-2.0

import CantonKit
import CantonWalletKit
import Foundation
import Observation

/// The wallet's state machine: onboard once, then keep the portfolio and
/// inbox in sync. All ledger writes are externally signed by the device's
/// driver — the participant never holds the key.
///
/// Progress is also printed as `WALLET:` lines so the demo loop can be
/// driven and verified headlessly (`xcrun simctl launch --console`).
@Observable
@MainActor
final class WalletModel {
    enum Phase: Equatable {
        case fresh
        case onboarding
        case ready
        case failed(String)
    }

    let environment = WalletEnvironment.localNet

    private(set) var phase: Phase = .fresh
    private(set) var partyId: String?
    private(set) var signerLabel = ""
    private(set) var holdings: [Holding] = []
    private(set) var inbox: [TransferInstruction] = []
    private(set) var history: [TokenStandardClient.HoldingsChange] = []
    private(set) var lastError: String?
    private(set) var busy = false
    var lastSend: SendReceipt?
    private(set) var preapproval: ScanClient.TransferPreapprovalInfo?
    private(set) var preapprovalRequested = false

    /// Convention key for human-readable transfer memos in the meta map.
    static let memoKey = "splice.lfdecentralizedtrust.org/reason"

    private var client: CantonClient?
    private var driver: (any SigningDriver)?
    private var allocated: AllocatedExternalParty?
    private var synchronizerId: String?
    private let store = KeychainWalletStore(service: "io.github.vsima.canton.app.wallet")

    var totalAmulet: Decimal {
        holdings.reduce(Decimal.zero) { total, holding in
            total + (Decimal(string: holding.amount) ?? .zero)
        }
    }

    /// Creates the device key and allocates this wallet's external party.
    func onboard() async {
        guard phase == .fresh else { return }
        phase = .onboarding
        do {
            let client = environment.makeClient()
            self.client = client

            // Restore an existing wallet identity before minting a new one:
            // the party is already allocated on the ledger; only the signer
            // needs reviving from its keychain-held handle.
            if let record = try? await store.list().first, let handle = record.keyHandle {
                let (driver, _, label) = try SignerFactory.make(restoring: handle)
                self.driver = driver
                self.signerLabel = label
                self.partyId = record.partyId
                self.allocated = AllocatedExternalParty(
                    partyId: record.partyId,
                    publicKeyFingerprint: record.publicKeyFingerprint
                )
                self.synchronizerId = record.synchronizerId
                phase = .ready
                print("WALLET: restored \(record.partyId) signer=\(label)")
                await refresh()
                return
            }

            let (driver, handle, label) = try SignerFactory.make(restoring: nil)
            self.driver = driver
            self.signerLabel = label

            let parties = ExternalPartyClient(client: client)
            guard let synchronizer = try await parties.connectedSynchronizers().first else {
                throw WalletUIError("no synchronizer reachable at \(environment.ledgerHost)")
            }
            synchronizerId = synchronizer

            let party = try await parties.allocate(
                driver: driver,
                synchronizerId: synchronizer,
                partyHint: "wallet",
                userId: environment.userId
            )
            partyId = party.partyId
            allocated = party
            try await store.save(
                WalletRecord(
                    partyId: party.partyId,
                    publicKeyFingerprint: party.publicKeyFingerprint,
                    synchronizerId: synchronizer,
                    keyHandle: handle,
                    createdAt: Date()
                )
            )
            phase = .ready
            print("WALLET: onboarded \(party.partyId) signer=\(label)")
            await refresh()
        } catch {
            phase = .failed("\(error)")
            print("WALLET: onboarding failed: \(error)")
        }
    }

    /// Re-reads holdings and the pending-offer inbox.
    func refresh() async {
        guard case .ready = phase, let client, let partyId else { return }
        do {
            let tokens = TokenStandardClient(client: client, registry: registry())
            holdings = try await tokens.listHoldings(partyId: partyId)
            inbox = try await tokens.pendingTransferInstructions(partyId: partyId)
                .filter { $0.status == .pendingReceiverAcceptance && $0.transfer.receiver == partyId }
            history = Array(try await tokens.holdingsHistory(partyId: partyId).reversed())
            preapproval = try? await scan().transferPreapprovalByParty(partyId)
            lastError = nil
            print("WALLET: holdings=\(totalAmulet) inbox=\(inbox.count) history=\(history.count)")
        } catch {
            lastError = "\(error)"
            print("WALLET: refresh failed: \(error)")
        }
    }

    /// Accepts a pending offer — signed on-device.
    func accept(_ instruction: TransferInstruction) async {
        await exercise(instruction, choice: .accept)
    }

    /// Rejects a pending offer — signed on-device.
    func reject(_ instruction: TransferInstruction) async {
        await exercise(instruction, choice: .reject)
    }

    struct SendReceipt: Identifiable {
        let id = UUID()
        let amount: Decimal
        let receiver: String
        let memo: String
        let at: Date
    }

    /// Sends Amulet to another party (two-step unless they're preapproved).
    func send(to receiver: String, amount: Decimal, memo: String = "") async {
        guard let client, let driver, let allocated, let synchronizerId else { return }
        busy = true
        defer { busy = false }
        do {
            let inputs = holdings.filter { $0.lock == nil }
            guard let instrument = inputs.first?.instrumentId else {
                throw WalletUIError("nothing to send")
            }
            let tokens = TokenStandardClient(client: client, registry: registry())
            try await tokens.createTransfer(
                driver: driver,
                party: allocated,
                receiver: receiver,
                instrumentId: instrument,
                amount: "\(amount)",
                inputHoldingCids: inputs.map(\.contractId),
                synchronizerId: synchronizerId,
                userId: environment.userId,
                meta: memo.isEmpty ? [:] : [Self.memoKey: memo]
            )
            lastSend = SendReceipt(amount: amount, receiver: receiver, memo: memo, at: Date())
            print("WALLET: sent \(amount) to \(receiver.prefix(24))…")
            await refresh()
        } catch {
            lastError = "\(error)"
            print("WALLET: send failed: \(error)")
        }
    }

    private func exercise(_ instruction: TransferInstruction, choice: TransferInstructionChoice) async {
        guard let client, let driver, let allocated, let synchronizerId else { return }
        busy = true
        defer { busy = false }
        do {
            let tokens = TokenStandardClient(client: client, registry: registry())
            try await tokens.exerciseTransferInstruction(
                driver: driver,
                party: allocated,
                transferInstructionId: instruction.contractId,
                choice: choice,
                synchronizerId: synchronizerId,
                userId: environment.userId
            )
            print("WALLET: \(choice) \(instruction.contractId.prefix(20))…")
            await refresh()
        } catch {
            lastError = "\(error)"
            print("WALLET: \(choice) failed: \(error)")
        }
    }

    /// "Receive instantly": requests a transfer preapproval from this
    /// wallet's validator operator, signed on-device. Acceptance shows up in
    /// [preapproval] once the operator's automation pays for it.
    func requestInstantReceive() async {
        guard let client, let driver, let allocated, let synchronizerId else { return }
        busy = true
        defer { busy = false }
        do {
            let provider = try await operatorParty()
            let dso = try await scan().dsoPartyId()
            let tokens = TokenStandardClient(client: client, registry: registry())
            try await tokens.requestTransferPreapproval(
                driver: driver,
                party: allocated,
                provider: provider,
                dso: dso,
                synchronizerId: synchronizerId,
                userId: environment.userId
            )
            preapprovalRequested = true
            print("WALLET: preapproval requested via \(provider.prefix(24))…")
        } catch {
            lastError = "\(error)"
            print("WALLET: preapproval request failed: \(error)")
        }
    }

    /// LocalNet dev lookup: the validator operator party (the preapproval
    /// provider). Real networks surface this through validator onboarding.
    private func operatorParty() async throws -> String {
        var request = URLRequest(url: URL(string: "\(environment.validatorURL)/v0/validator-user")!)
        request.setValue(
            "Bearer \(WalletEnvironment.unsafeJWT(sub: "app-user", audience: environment.jwtAudience, secret: environment.unsafeJWTSecret ?? ""))",
            forHTTPHeaderField: "Authorization"
        )
        let (data, _) = try await URLSession.shared.data(for: request)
        guard
            let json = try JSONSerialization.jsonObject(with: data) as? [String: Any],
            let party = json["party_id"] as? String
        else {
            throw WalletUIError("validator-user lookup failed")
        }
        return party
    }

    private func scan() -> ScanClient {
        ScanClient(baseURL: URL(string: environment.scanURL)!)
    }

    private func registry() -> TransferRegistryClient {
        TransferRegistryClient(baseURL: URL(string: environment.registryURL)!)
    }
}

struct WalletUIError: Error, CustomStringConvertible {
    let description: String
    init(_ description: String) { self.description = description }
}
