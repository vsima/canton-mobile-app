// Copyright (c) 2026 Victor Sima
// SPDX-License-Identifier: Apache-2.0

import CantonKit
import CantonWalletKit
import CryptoKit
import Foundation

/// Which network the wallet talks to, and how. Only LocalNet exists today;
/// DevNet/MainNet arrive with real auth flows (OAuth against a validator)
/// instead of the dev-only unsafe JWT below.
struct WalletEnvironment: Sendable {
    let name: String
    let ledgerHost: String
    let ledgerPort: Int
    let registryURL: String
    let scanURL: String
    let userId: String
    /// Dev-only: mints the LocalNet `unsafe-jwt-hmac-256` token in-app.
    /// Anything beyond LocalNet must inject a real token provider instead.
    let unsafeJWTSecret: String?
    let jwtAudience: String

    /// Splice LocalNet as `integration/run-localnet.sh` boots it. The
    /// simulator shares the host's loopback, so 127.0.0.1 reaches the
    /// app-user participant; a physical device needs the Mac's LAN IP via
    /// the env overrides.
    static var localNet: WalletEnvironment {
        let env = ProcessInfo.processInfo.environment
        let host = env["CANTON_HOST"] ?? "127.0.0.1"
        return WalletEnvironment(
            name: "LocalNet",
            ledgerHost: host,
            ledgerPort: env["CANTON_PORT"].flatMap(Int.init) ?? 2901,
            registryURL: env["CANTON_REGISTRY_URL"] ?? "http://scan.localhost:4000",
            scanURL: env["CANTON_SCAN_URL"] ?? "http://scan.localhost:4000/api/scan",
            userId: env["CANTON_USER"] ?? "ledger-api-user",
            unsafeJWTSecret: "unsafe",
            jwtAudience: "https://canton.network.global"
        )
    }

    func makeClient() -> CantonClient {
        CantonClient(
            configuration: .init(
                host: ledgerHost,
                port: ledgerPort,
                useTLS: false,
                accessTokenProvider: unsafeJWTSecret.map { secret in
                    let sub = userId
                    let audience = jwtAudience
                    return { @Sendable in Self.unsafeJWT(sub: sub, audience: audience, secret: secret) }
                }
            )
        )
    }

    static func unsafeJWT(sub: String, audience: String, secret: String) -> String {
        func b64(_ data: Data) -> String {
            data.base64EncodedString()
                .replacingOccurrences(of: "+", with: "-")
                .replacingOccurrences(of: "/", with: "_")
                .replacingOccurrences(of: "=", with: "")
        }
        let header = b64(Data(#"{"alg":"HS256","typ":"JWT"}"#.utf8))
        let payload = b64(Data(#"{"sub":"\#(sub)","aud":"\#(audience)"}"#.utf8))
        let mac = HMAC<SHA256>.authenticationCode(
            for: Data("\(header).\(payload)".utf8),
            using: SymmetricKey(data: Data(secret.utf8))
        )
        return "\(header).\(payload).\(b64(Data(mac)))"
    }
}

/// Picks the signer for this hardware: enclave-resident keys on physical
/// devices, software P-256 in the simulator (which has no enclave). The
/// wallet's security story is the enclave path; the software path exists so
/// development in the simulator stays honest about what it is.
enum SignerFactory {
    static func make(restoring handle: Data?) throws -> (driver: any SigningDriver, handle: Data?, label: String) {
        if SecureEnclaveSigningDriver.isAvailable {
            if let handle {
                let driver = try SecureEnclaveSigningDriver(dataRepresentation: handle)
                return (driver, handle, "Secure Enclave")
            }
            let driver = try SecureEnclaveSigningDriver()
            return (driver, driver.dataRepresentation, "Secure Enclave")
        }
        // Simulator: software key, regenerated per install (dev only).
        return (SoftwareSigningDriver.generate(.ecP256), nil, "software P-256 (simulator)")
    }
}
