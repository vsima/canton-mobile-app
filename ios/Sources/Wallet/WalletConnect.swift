// Copyright (c) 2026 Victor Sima
// SPDX-License-Identifier: Apache-2.0

import Combine
import Foundation
import CantonDappKit
import CantonDappWCKit
// RPCID / RPCResult / JSONRPCError are not Sendable-audited either; treat them
// as pre-concurrency so an `RPCID` can be handed to a nonisolated responder.
@preconcurrency import JSONRPC
// Reown is not Sendable-audited for Swift 6, so its singleton `WalletKit.instance`
// trips complete-concurrency checking. @preconcurrency relaxes those diagnostics
// for Reown symbols only — the rest of the app stays under Swift 6. Reown's
// client is internally synchronized and only touched here on the main actor.
@preconcurrency import ReownWalletKit
import WalletConnectNetworking

/// A live WalletConnect session, for display on the Connect screen.
struct WcSessionInfo: Identifiable, Equatable {
    var id: String { topic }
    let topic: String
    let name: String
    let url: String
}

/// The Reown WalletKit binding: the relay/pairing/session client that carries
/// CIP-0103 frames to and from the SDK's ``CantonWalletConnect`` adapter.
///
/// This is the one place that depends on Reown. It owns no protocol logic —
/// a session proposal is approved with the adapter's namespaces, a session
/// request is routed into the adapter's `handle`, and the adapter drives the
/// engine (`DappSession`) which does the approvals and signing. The wallet's
/// identity and the approval UI stay in ``WalletModel``; nothing here touches a
/// key. It is the iOS twin of Android's `WalletConnectController`.
@MainActor
final class WalletConnectController {
    static let shared = WalletConnectController()
    private init() {}

    private var adapter: CantonWalletConnect?
    private var accountsProvider: (@Sendable () async -> [DappWallet])?
    private var cancellables = Set<AnyCancellable>()
    private var configured = false

    /// Set by ``WalletModel`` to surface status lines on the Connect screen.
    var onStatus: ((String) -> Void)?
    /// Set by ``WalletModel`` to surface the active sessions on the Connect screen.
    var onSessions: (([WcSessionInfo]) -> Void)?

    /// Initialises Reown once for the process and subscribes to its publishers.
    /// Must run before any pairing; safe to call repeatedly.
    func configure(projectId: String) {
        guard !configured else { return }
        configured = true
        do {
            let metadata = AppMetadata(
                name: "Canton Wallet",
                description: "Canton reference wallet",
                url: "https://github.com/vsima/canton-mobile-app",
                icons: [],
                redirect: try AppMetadata.Redirect(native: "canton-wallet://", universal: nil)
            )
            Networking.configure(
                groupIdentifier: "group.io.github.vsima.canton.app",
                projectId: projectId,
                socketFactory: URLSessionWebSocketFactory()
            )
            WalletKit.configure(metadata: metadata, crypto: CantonCryptoProvider())
            subscribe()
        } catch {
            print("WALLET: WC configure failed: \(error)")
        }
    }

    private func subscribe() {
        WalletKit.instance.sessionProposalPublisher
            .receive(on: DispatchQueue.main)
            .sink { [weak self] output in self?.handleProposal(output.proposal) }
            .store(in: &cancellables)
        WalletKit.instance.sessionRequestPublisher
            .receive(on: DispatchQueue.main)
            .sink { [weak self] output in self?.handleRequest(output.request) }
            .store(in: &cancellables)
        WalletKit.instance.sessionsPublisher
            .receive(on: DispatchQueue.main)
            .sink { [weak self] sessions in self?.publish(sessions) }
            .store(in: &cancellables)
    }

    /// Registers the wallet's adapter + the accounts it may share.
    func register(adapter: CantonWalletConnect, accounts: @escaping @Sendable () async -> [DappWallet]) {
        self.adapter = adapter
        self.accountsProvider = accounts
        refreshSessions()
    }

    /// Hands a scanned/pasted `wc:` pairing URI to the relay.
    func pair(_ uri: String) {
        let trimmed = uri.trimmingCharacters(in: .whitespacesAndNewlines)
        guard let wcURI = try? WalletConnectURI(uriString: trimmed) else {
            onStatus?("That is not a WalletConnect link")
            return
        }
        Task {
            do { try await WalletKit.instance.pair(uri: wcURI) }
            catch { onStatus?("Pairing failed: \(error.localizedDescription)") }
        }
    }

    /// Reads WalletKit's active sessions and pushes them to the UI.
    func refreshSessions() { publish(WalletKit.instance.getSessions()) }

    /// Disconnects a session by topic.
    func disconnect(topic: String) {
        Task {
            do {
                try await WalletKit.instance.disconnect(topic: topic)
                refreshSessions()
            } catch { onStatus?("Disconnect failed: \(error.localizedDescription)") }
        }
    }

    // MARK: - Reown callbacks

    private func handleProposal(_ proposal: Session.Proposal) {
        guard let adapter, let accountsProvider else {
            reject(proposal, reason: "Wallet not ready")
            return
        }
        let proposalId = proposal.id
        let name = proposal.proposer.name.isEmpty ? "dApp" : proposal.proposer.name
        Task {
            let ns = adapter.sessionNamespaces(accounts: await accountsProvider())
            await approveSession(proposalId: proposalId, name: name, namespaces: ns)
        }
    }

    /// Builds Reown's typed namespaces and approves. `nonisolated` so the
    /// non-Sendable `SessionNamespace` is created and consumed in one isolation
    /// domain, never sent across the actor boundary. Its input
    /// (`WcSessionNamespaces`) and outputs are Sendable.
    private nonisolated func approveSession(proposalId: String, name: String, namespaces ns: WcSessionNamespaces) async {
        guard let namespaces = Self.sessionNamespaces(ns) else {
            await setStatus("Could not build session namespaces")
            return
        }
        do {
            _ = try await WalletKit.instance.approve(proposalId: proposalId, namespaces: namespaces)
            await onApproved(name)
        } catch {
            await setStatus("Approve failed: \(error.localizedDescription)")
        }
    }

    private func handleRequest(_ request: Request) {
        guard let adapter else { return }
        let topic = request.topic
        let requestId = request.id
        let wc = WcRequest(
            topic: request.topic,
            requestId: Self.requestId(request.id),
            chainId: request.chainId.absoluteString,
            method: request.method,
            params: try? Self.jsonValue(from: request.params)
        )
        Task {
            let response = await adapter.handle(wc)
            await sendResponse(topic: topic, requestId: requestId, response: response)
        }
    }

    /// Encodes the CIP-0103 result into Reown's `RPCResult` and responds.
    /// `nonisolated` so the non-Sendable `RPCResult` never crosses isolation.
    private nonisolated func sendResponse(topic: String, requestId: RPCID, response: WcResponse) async {
        do {
            let result: RPCResult
            switch response {
            case .success(let value):
                result = .response(try Self.anyCodable(from: value))
            case .error(let code, let message):
                result = .error(JSONRPCError(code: code, message: message))
            }
            try await WalletKit.instance.respond(topic: topic, requestId: requestId, response: result)
        } catch {
            await setStatus("Respond failed: \(error.localizedDescription)")
        }
    }

    private func onApproved(_ name: String) {
        onStatus?("Connected to \(name)")
        refreshSessions()
    }

    private func setStatus(_ line: String) { onStatus?(line) }

    private func reject(_ proposal: Session.Proposal, reason: String) {
        Task {
            try? await WalletKit.instance.rejectSession(proposalId: proposal.id, reason: .userRejected)
            onStatus?(reason)
        }
    }

    private func publish(_ sessions: [Session]) {
        onSessions?(sessions.map {
            WcSessionInfo(topic: $0.topic, name: $0.peer.name.isEmpty ? "dApp" : $0.peer.name, url: $0.peer.url)
        })
    }

    // MARK: - Mapping helpers

    /// Projects the adapter's ``WcSessionNamespaces`` into Reown's typed CAIP
    /// namespaces. Our CAIP-10 addresses are percent-encoded party ids, which
    /// Reown's `Account` accepts (its address charset includes `%`).
    private nonisolated static func sessionNamespaces(_ ns: WcSessionNamespaces) -> [String: SessionNamespace]? {
        let accounts = ns.accounts.compactMap { Account($0) }
        guard !accounts.isEmpty else { return nil }
        let namespace = SessionNamespace(
            chains: ns.chains.compactMap { Blockchain($0) },
            accounts: accounts,
            methods: Set(ns.methods),
            events: Set(ns.events)
        )
        return [Caip.cantonNamespace: namespace]
    }

    private nonisolated static func requestId(_ id: RPCID) -> Int64 {
        switch id {
        case .right(let value): return value
        case .left(let text): return Int64(text) ?? 0
        }
    }

    /// Reown's `AnyCodable` → the SDK's `JSONValue`, via a JSON round-trip so
    /// neither type's internals leak into the other.
    private nonisolated static func jsonValue(from anyCodable: AnyCodable) throws -> JSONValue {
        try JSONValue.parse(try JSONEncoder().encode(anyCodable))
    }

    private nonisolated static func anyCodable(from value: JSONValue) throws -> AnyCodable {
        try JSONDecoder().decode(AnyCodable.self, from: try value.serialized())
    }
}

/// A `WebSocketConnecting` over `URLSessionWebSocketTask`, so the wallet needs no
/// third-party WebSocket (Starscream) — the relay only needs text frames.
final class URLSessionWebSocket: NSObject, WebSocketConnecting, URLSessionWebSocketDelegate, @unchecked Sendable {
    var request: URLRequest
    var isConnected: Bool = false
    var onConnect: (() -> Void)?
    var onDisconnect: ((Error?) -> Void)?
    var onText: ((String) -> Void)?

    private var task: URLSessionWebSocketTask?
    private lazy var session = URLSession(configuration: .default, delegate: self, delegateQueue: nil)

    init(request: URLRequest) {
        self.request = request
        super.init()
    }

    func connect() {
        let task = session.webSocketTask(with: request)
        self.task = task
        task.resume()
        receive()
    }

    func disconnect() {
        task?.cancel(with: .goingAway, reason: nil)
        task = nil
        if isConnected {
            isConnected = false
            onDisconnect?(nil)
        }
    }

    func write(string: String, completion: (() -> Void)?) {
        task?.send(.string(string)) { _ in completion?() }
    }

    private func receive() {
        task?.receive { [weak self] result in
            guard let self else { return }
            switch result {
            case .success(let message):
                switch message {
                case .string(let text): self.onText?(text)
                case .data(let data): String(data: data, encoding: .utf8).map { self.onText?($0) }
                @unknown default: break
                }
                self.receive()
            case .failure(let error):
                self.isConnected = false
                self.onDisconnect?(error)
            }
        }
    }

    func urlSession(_ session: URLSession, webSocketTask: URLSessionWebSocketTask, didOpenWithProtocol protocol: String?) {
        isConnected = true
        onConnect?()
    }

    func urlSession(
        _ session: URLSession,
        webSocketTask: URLSessionWebSocketTask,
        didCloseWith closeCode: URLSessionWebSocketTask.CloseCode,
        reason: Data?
    ) {
        isConnected = false
        onDisconnect?(nil)
    }
}

struct URLSessionWebSocketFactory: WebSocketFactory {
    func create(with url: URL) -> WebSocketConnecting {
        URLSessionWebSocket(request: URLRequest(url: url))
    }
}

/// Reown requires a `CryptoProvider`, but its only use is EVM message recovery
/// (EIP-191 / SIWE `onSessionAuthenticate`), which Canton never uses — the
/// wallet only answers `signMessage` and `prepareExecute`. So this is a stub;
/// implementing it would pull in an EVM crypto stack for no reachable code path.
struct CantonCryptoProvider: CryptoProvider {
    struct Unsupported: Error {}

    func recoverPubKey(signature: EthereumSignature, message: Data) throws -> Data {
        throw Unsupported()
    }

    func keccak256(_ data: Data) -> Data { Data() }
}
