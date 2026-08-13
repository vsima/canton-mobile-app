// Copyright (c) 2026 Victor Sima
// SPDX-License-Identifier: Apache-2.0

import CantonKit
import CantonLedgerAPI
import CantonWalletKit
import Foundation
import Observation

/// The wallet's state machine: onboard once, then keep the portfolio and
/// inbox in sync. All ledger writes are externally signed by the device's
/// driver — the participant never holds the key.
///
/// Progress is also printed as `WALLET:` lines so the demo loop can be
/// driven and verified headlessly (`xcrun simctl launch --console`).
/// A dApp checkout fetched from a `canton-checkout:` QR — what's being bought,
/// for how much, to whom, referencing which memo. Shown for review, then used
/// to prefill the Send form.
struct CheckoutInfo {
    let shop: String
    let item: String?
    let amount: String
    let instrumentId: String?
    let payTo: String
    let memo: String
    let status: String
}

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
    /// True while the dev faucet (``getTestFunds()``) runs — drives the
    /// inline progress on the "Get test funds" affordance.
    private(set) var funding = false
    /// Contract ids of transfer instructions with an in-flight accept/reject,
    /// so only the tapped row's buttons disable — not the whole inbox.
    private(set) var processing: Set<String> = []
    var lastSend: SendReceipt?
    private(set) var preapproval: ScanClient.TransferPreapprovalInfo?
    private(set) var preapprovalRequested = false
    /// Scan lags the ledger briefly after a cancel; skip re-reading the
    /// preapproval until then so the toggle doesn't bounce back on.
    private var preapprovalSuppressedUntil = Date.distantPast

    /// Convention key for human-readable transfer memos in the meta map.
    static let memoKey = "splice.lfdecentralizedtrust.org/reason"

    /// The faucet taps $26 — 5200 CC at LocalNet's 0.005 USD/CC — and
    /// forwards a round 5000 CC, leaving the operator headroom for the
    /// Amulet transfer fees it pays as sender.
    static let faucetTapUsd = "26.0"
    static let faucetSendCc = "5000.0"

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
            let existing = try? await store.list().first
            if let record = existing, let handle = record.keyHandle {
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
            // A party without its signer handle can never sign again; fail
            // loudly rather than mint a fresh key under the old identity.
            if let record = existing {
                phase = .failed(
                    "The stored wallet for \(record.partyId) has no signer handle, "
                        + "so it can't sign. Delete and reinstall the app to start fresh."
                )
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
            if Date() > preapprovalSuppressedUntil {
                preapproval = try? await scan().transferPreapprovalByParty(partyId)
            }
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

    /// Fetches a dApp checkout the Send scanner read from a `canton-checkout:`
    /// QR, so the wallet can reproduce it for review before paying. The fetched
    /// fields only prefill the form — the user still reviews and sends — so an
    /// untrusted URL can mislead the prefill but not move funds without approval.
    func fetchCheckout(_ urlString: String) async -> CheckoutInfo? {
        guard let url = URL(string: urlString) else { return nil }
        do {
            let (data, _) = try await URLSession.shared.data(from: url)
            guard let json = try JSONSerialization.jsonObject(with: data) as? [String: Any] else { return nil }
            func str(_ key: String) -> String? { json[key] as? String }
            guard let payTo = str("payTo"), let amount = str("amount"), let memo = str("memo") else { return nil }
            return CheckoutInfo(
                shop: str("shop") ?? "",
                item: str("item"),
                amount: amount,
                instrumentId: str("instrumentId"),
                payTo: payTo,
                memo: memo,
                status: str("status") ?? ""
            )
        } catch {
            print("WALLET: fetchCheckout failed: \(error)")
            return nil
        }
    }

    /// Sends Amulet to another party (two-step unless they're preapproved).
    func send(to receiver: String, amount: Decimal, memo: String = "") async {
        guard let client, let driver, let allocated, let synchronizerId else { return }
        let memo = memo.trimmingCharacters(in: .whitespacesAndNewlines)
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

    /// The scan reads feeding ``estimatedFee(amountCc:)``: the USD fee
    /// schedule plus the open rounds carrying the CC price.
    private struct FeePreviewInputs {
        let schedule: TransferFeeSchedule
        let rounds: [OpenMiningRound]
    }

    private var feePreview: FeePreviewInputs?
    private var feePreviewAttemptedAt = Date.distantPast
    private var feePreviewInFlight = false
    /// At most one fee-preview scan fetch per window; rounds rotate every
    /// ~2.5–10 minutes, so a few minutes of staleness is fine.
    private static let feePreviewTTL: TimeInterval = 3 * 60

    /// The estimated network fee in CC for sending `amountCc` — the SDK's
    /// `TransferFeeEstimator` over the cached AmuletRules schedule,
    /// converted at the latest usable open round's price. Nil when the
    /// amount isn't positive or the cache is empty (scan unreachable / not
    /// fetched yet) — the fee row is simply absent then; never an error
    /// state. Pure cache read: ``ensureFeePreviewFresh()`` populates it.
    ///
    /// Known reality: CIP-0078 zeroed all Canton Coin transfer fees by
    /// governance vote, and splice >= 0.5.16 (CIP-0107) hardcodes them to
    /// zero — so on every current network this estimate is 0. The row is
    /// the reference wiring for fee-charging registries/configs, and the
    /// app renders the honest value, whatever the network's schedule says.
    func estimatedFee(amountCc: Decimal) -> Decimal? {
        guard amountCc > 0,
              let inputs = feePreview,
              let price = inputs.rounds.latestUsable()?.amuletPriceUsd, price > 0
        else { return nil }
        return TransferFeeEstimator.estimate(
            schedule: inputs.schedule,
            amuletPriceUsd: price,
            amountCc: amountCc
        ).feeCc
    }

    /// Lazily (re)fetches the fee-preview inputs from scan — at most one
    /// attempt per ``feePreviewTTL``, so per-keystroke calls recompute from
    /// cache and never hit the network. Fire-and-forget: it never blocks or
    /// fails the send path; on any error the preview is just absent.
    func ensureFeePreviewFresh() {
        guard !feePreviewInFlight,
              Date().timeIntervalSince(feePreviewAttemptedAt) >= Self.feePreviewTTL
        else { return }
        feePreviewAttemptedAt = Date()
        feePreviewInFlight = true
        Task {
            defer { self.feePreviewInFlight = false }
            do {
                let scan = self.scan()
                let config = try await scan.amuletRulesConfig()
                let rounds = try await scan.openMiningRounds()
                self.feePreview = FeePreviewInputs(schedule: config.transferFees, rounds: rounds)
            } catch {
                // Preview only: keep whatever cache exists; the row just
                // stays absent when there is none.
                print("WALLET: fee preview fetch failed: \(error)")
            }
        }
    }

    private func exercise(_ instruction: TransferInstruction, choice: TransferInstructionChoice) async {
        guard let client, let driver, let allocated, let synchronizerId else { return }
        processing.insert(instruction.contractId)
        defer { processing.remove(instruction.contractId) }
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

    /// Turns instant receiving off — archives the preapproval, signed
    /// on-device (the receiver may cancel unilaterally).
    func cancelInstantReceive() async {
        guard let client, let driver, let allocated, let synchronizerId,
              let cid = preapproval?.contractId else { return }
        busy = true
        defer { busy = false }
        do {
            let tokens = TokenStandardClient(client: client, registry: registry())
            try await tokens.cancelTransferPreapproval(
                driver: driver,
                party: allocated,
                preapprovalCid: cid,
                synchronizerId: synchronizerId,
                userId: environment.userId
            )
            preapproval = nil
            preapprovalRequested = false
            preapprovalSuppressedUntil = Date().addingTimeInterval(30)
            print("WALLET: preapproval cancelled")
            await refresh()
        } catch {
            lastError = "\(error)"
            print("WALLET: preapproval cancel failed: \(error)")
        }
    }

    /// Dev faucet: funds this wallet with test CC by tapping the validator's
    /// wallet and token-standard-transferring the CC here — the same flow
    /// the SDK's `LocalNetFaucetTool` drives from a shell, in-app so an
    /// empty wallet can seed itself (and demo the transfer flow doing it).
    ///
    /// The tap leg goes through `ValidatorClient` as the validator wallet
    /// user (the LocalNet unsafe JWT — the same auth the ledger connection
    /// uses); the transfer leg submits the registry's
    /// `TransferFactory_Transfer` as that operator party over the command
    /// service. If this wallet has instant receiving on, the funds settle
    /// directly; otherwise they arrive as an inbox offer to accept — the
    /// faucet never auto-accepts.
    ///
    /// This is a LocalNet/DevNet developer feature. The reference app only
    /// targets dev networks today, so the button is always shown; on a
    /// network without a tap (or with the validator API unreachable) the
    /// attempt fails and the error is surfaced honestly via ``lastError``.
    func getTestFunds() async {
        guard case .ready = phase, let receiver = partyId, !funding else { return }
        funding = true
        defer { funding = false }
        do {
            // 1. Onboard the validator wallet user (idempotent) and tap.
            let environment = environment
            let validator = ValidatorClient(
                baseURL: URL(string: environment.validatorURL)!,
                accessTokenProvider: { @Sendable in
                    WalletEnvironment.unsafeJWT(
                        sub: environment.walletUser,
                        audience: environment.jwtAudience,
                        secret: environment.unsafeJWTSecret ?? ""
                    )
                }
            )
            let status = try? await validator.userStatus()
            let operatorParty: String
            if let status, status.userOnboarded, !status.partyId.isEmpty {
                operatorParty = status.partyId
            } else {
                operatorParty = try await validator.register()
            }
            let mintedCid = try await tapWithRetry(validator)
            print("WALLET: faucet tapped \(Self.faucetTapUsd) USD to \(operatorParty.prefix(24))…")

            // 2. Wait for the mint among the operator's unlocked holdings —
            //    the transfer's input UTXOs.
            let operatorClient = environment.makeClient(user: environment.walletUser)
            let tokens = TokenStandardClient(client: operatorClient, registry: registry())
            var inputs: [Holding] = []
            for _ in 0..<10 {
                inputs = try await tokens.listHoldings(partyId: operatorParty).filter { $0.lock == nil }
                if inputs.contains(where: { $0.contractId == mintedCid }) { break }
                try await Task.sleep(for: .milliseconds(500))
            }
            guard let instrument = inputs.first?.instrumentId else {
                throw WalletUIError("tapped funds not visible in the validator wallet yet — try again")
            }

            // 3. Token-standard transfer operator → this wallet, via the
            //    registry's transfer factory (with its disclosed contracts).
            let transfer = Transfer(
                sender: operatorParty,
                receiver: receiver,
                amount: Self.faucetSendCc,
                instrumentId: instrument,
                requestedAt: Date(),
                executeBefore: Date().addingTimeInterval(24 * 3600),
                inputHoldingCids: inputs.map(\.contractId),
                meta: [Self.memoKey: "Test funds"]
            )
            let factory = try await registry().transferFactory(
                choiceArguments: FaucetValues.transferFactoryChoiceArguments(
                    expectedAdmin: instrument.admin,
                    transfer: transfer
                )
            )
            var exercise = Com_Daml_Ledger_Api_V2_ExerciseCommand()
            exercise.templateID = TokenStandard.transferFactoryInterfaceID
            exercise.contractID = factory.factoryId
            exercise.choice = "TransferFactory_Transfer"
            exercise.choiceArgument = .record([
                "expectedAdmin": .party(instrument.admin),
                "transfer": FaucetValues.transferValue(transfer),
                "extraArgs": try FaucetValues.extraArgsValue(factory.choiceContext.choiceContextData),
            ])
            var command = Com_Daml_Ledger_Api_V2_Command()
            command.exercise = exercise
            var commands = Com_Daml_Ledger_Api_V2_Commands()
            commands.commandID = UUID().uuidString
            commands.userID = environment.walletUser
            commands.actAs = [operatorParty]
            commands.commands = [command]
            commands.disclosedContracts = try factory.choiceContext.disclosedContracts.map {
                try $0.toProto()
            }
            var request = Com_Daml_Ledger_Api_V2_SubmitAndWaitRequest()
            request.commands = commands
            let submit = request
            _ = try await operatorClient.withServices { services in
                try await services.command.submitAndWait(submit)
            }
            print("WALLET: faucet sent \(Self.faucetSendCc) CC (kind=\(factory.transferKind))")
            await refresh()
        } catch {
            lastError = "\(error)"
            print("WALLET: faucet failed: \(error)")
        }
    }

    /// Taps the faucet, retrying the transient statuses the SDK documents
    /// (no open mining round yet, load shedding) with a stable command id so
    /// retries deduplicate instead of double-minting.
    private func tapWithRetry(_ validator: ValidatorClient) async throws -> String {
        let commandId = UUID().uuidString
        var last: any Error = WalletUIError("tap failed")
        for attempt in 1...4 {
            do {
                return try await validator.tap(amountUsd: Self.faucetTapUsd, commandId: commandId)
            } catch let error as ValidatorError where [400, 404, 429, 503].contains(error.statusCode ?? 0) {
                last = error
                print("WALLET: faucet tap attempt \(attempt): \(error)")
                if attempt < 4 { try await Task.sleep(for: .seconds(2)) }
            }
        }
        throw last
    }

    /// LocalNet dev lookup: the validator operator party (the preapproval
    /// provider). Real networks surface this through validator onboarding.
    private func operatorParty() async throws -> String {
        var request = URLRequest(url: URL(string: "\(environment.validatorURL)/v0/validator-user")!)
        request.setValue(
            "Bearer \(WalletEnvironment.unsafeJWT(sub: environment.walletUser, audience: environment.jwtAudience, secret: environment.unsafeJWTSecret ?? ""))",
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

/// Daml-JSON ⇄ proto bridging for the dev faucet's operator-side transfer.
///
/// Mirrors the SDK's internal `ChoiceContextJSON`/`metadataValue` helpers:
/// the faucet submits `TransferFactory_Transfer` as the participant-managed
/// validator wallet party over the raw command service — a leg the public
/// SDK surface doesn't cover (its `createTransfer` signs externally) — so
/// the app encodes the choice arguments itself from public SDK types.
private enum FaucetValues {

    /// `TransferFactory_Transfer` choice arguments in Daml JSON API
    /// encoding, for the registry's `GetFactoryRequest.choiceArguments`.
    static func transferFactoryChoiceArguments(
        expectedAdmin: String,
        transfer: Transfer
    ) -> [String: Any] {
        [
            "expectedAdmin": expectedAdmin,
            "transfer": [
                "sender": transfer.sender,
                "receiver": transfer.receiver,
                "amount": transfer.amount,
                "instrumentId": ["admin": transfer.instrumentId.admin, "id": transfer.instrumentId.id],
                "requestedAt": isoInstant(transfer.requestedAt),
                "executeBefore": isoInstant(transfer.executeBefore),
                "inputHoldingCids": transfer.inputHoldingCids,
                "meta": ["values": transfer.meta],
            ] as [String: Any],
            "extraArgs": [
                "context": ["values": [String: Any]()],
                "meta": ["values": [String: Any]()],
            ] as [String: Any],
        ]
    }

    /// The transfer specification as a proto record for the choice argument.
    static func transferValue(_ transfer: Transfer) -> Com_Daml_Ledger_Api_V2_Value {
        .record([
            "sender": .party(transfer.sender),
            "receiver": .party(transfer.receiver),
            "amount": .numeric(transfer.amount),
            "instrumentId": .record([
                "admin": .party(transfer.instrumentId.admin),
                "id": .text(transfer.instrumentId.id),
            ]),
            "requestedAt": .timestamp(transfer.requestedAt),
            "executeBefore": .timestamp(transfer.executeBefore),
            "inputHoldingCids": .list(transfer.inputHoldingCids.map { .contractId($0) }),
            "meta": metadata(transfer.meta),
        ])
    }

    /// `ExtraArgs { context, meta }` from the registry's `choiceContextData`.
    static func extraArgsValue(_ choiceContextData: Any?) throws -> Com_Daml_Ledger_Api_V2_Value {
        var entries: [(String, Com_Daml_Ledger_Api_V2_Value)] = []
        switch choiceContextData {
        case nil, is NSNull:
            break
        case let object as [String: Any]:
            let values = object["values"] as? [String: Any] ?? [:]
            for key in values.keys.sorted() {
                entries.append((key, try anyValueToValue(values[key]!)))
            }
        default:
            throw WalletUIError("choiceContextData must be an object")
        }
        return .record([
            "context": .record(["values": textMap(entries)]),
            "meta": metadata([:]),
        ])
    }

    /// One `AnyValue` variant from Daml JSON to its proto encoding.
    private static func anyValueToValue(_ json: Any) throws -> Com_Daml_Ledger_Api_V2_Value {
        guard let object = json as? [String: Any], let tag = object["tag"] as? String else {
            throw WalletUIError("AnyValue must be a tagged object")
        }
        let value = object["value"]
        let payload: Com_Daml_Ledger_Api_V2_Value
        switch tag {
        case "AV_Text":
            payload = .text(try string(value, tag))
        case "AV_Int":
            payload = .int64(try int64(value, tag))
        case "AV_Decimal":
            payload = .numeric(try string(value, tag))
        case "AV_Bool":
            guard let bool = value as? Bool else {
                throw WalletUIError("AV_Bool value must be a boolean")
            }
            payload = .bool(bool)
        case "AV_Date":
            payload = .date(daysSinceEpoch: try days(fromISODate: string(value, tag)))
        case "AV_Time":
            payload = .timestamp(try instant(fromISO: string(value, tag)))
        case "AV_RelTime":
            let micros = (value as? [String: Any])?["microseconds"] ?? value
            payload = .record(["microseconds": .int64(try int64(micros, tag))])
        case "AV_Party":
            payload = .party(try string(value, tag))
        case "AV_ContractId":
            payload = .contractId(try string(value, tag))
        case "AV_List":
            guard let array = value as? [Any] else {
                throw WalletUIError("AV_List value must be an array")
            }
            payload = .list(try array.map { try anyValueToValue($0) })
        case "AV_Map":
            guard let map = value as? [String: Any] else {
                throw WalletUIError("AV_Map value must be an object")
            }
            payload = textMap(try map.keys.sorted().map { ($0, try anyValueToValue(map[$0]!)) })
        default:
            throw WalletUIError("unknown AnyValue constructor \(tag)")
        }
        return .variant(constructor: tag, value: payload)
    }

    private static func metadata(_ meta: [String: String]) -> Com_Daml_Ledger_Api_V2_Value {
        .record(["values": textMap(meta.keys.sorted().map { ($0, .text(meta[$0]!)) })])
    }

    private static func textMap(
        _ entries: [(String, Com_Daml_Ledger_Api_V2_Value)]
    ) -> Com_Daml_Ledger_Api_V2_Value {
        var map = Com_Daml_Ledger_Api_V2_TextMap()
        map.entries = entries.map { key, value in
            var entry = Com_Daml_Ledger_Api_V2_TextMap.Entry()
            entry.key = key
            entry.value = value
            return entry
        }
        var result = Com_Daml_Ledger_Api_V2_Value()
        result.sum = .textMap(map)
        return result
    }

    private static func isoInstant(_ date: Date) -> String {
        let formatter = ISO8601DateFormatter()
        formatter.formatOptions = [.withInternetDateTime]
        return formatter.string(from: date)
    }

    private static func string(_ value: Any?, _ tag: String) throws -> String {
        guard let string = value as? String else {
            throw WalletUIError("\(tag) value must be a string")
        }
        return string
    }

    private static func int64(_ value: Any?, _ tag: String) throws -> Int64 {
        if let string = value as? String, let parsed = Int64(string) { return parsed }
        if let number = value as? NSNumber, !(value is Bool) { return number.int64Value }
        throw WalletUIError("\(tag) value must be an integer")
    }

    private static func days(fromISODate string: String) throws -> Int32 {
        let parts = string.split(separator: "-").compactMap { Int($0) }
        guard parts.count == 3 else {
            throw WalletUIError("AV_Date must be YYYY-MM-DD, was \(string)")
        }
        var components = DateComponents()
        (components.year, components.month, components.day) = (parts[0], parts[1], parts[2])
        var calendar = Calendar(identifier: .gregorian)
        calendar.timeZone = TimeZone(identifier: "UTC")!
        guard let date = calendar.date(from: components) else {
            throw WalletUIError("invalid AV_Date \(string)")
        }
        return Int32(date.timeIntervalSince1970 / 86_400)
    }

    private static func instant(fromISO string: String) throws -> Date {
        let fractional = ISO8601DateFormatter()
        fractional.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
        if let date = fractional.date(from: string) { return date }
        if let date = ISO8601DateFormatter().date(from: string) { return date }
        throw WalletUIError("invalid AV_Time \(string)")
    }
}
