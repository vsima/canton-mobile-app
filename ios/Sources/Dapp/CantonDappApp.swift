// Copyright (c) 2026 Victor Sima
// SPDX-License-Identifier: Apache-2.0

// The CIP-0103 dApp reference (iOS). It links only CantonDappKit — no
// CantonWalletKit, no signing drivers, no Ledger API stubs — which is the
// demonstration that the module split holds.
//
// This is the shell the ping and sign-in screens land in. The cross-app LAN
// transport is Kotlin-only today; its Swift port lands next, at which point
// this dials a wallet the way the Android dApp reference already can. Until
// then it shows what a dApp built on this SDK speaks.

import CantonDappKit
import SwiftUI

@main
struct CantonDappApp: App {
    var body: some Scene {
        WindowGroup {
            DappReferenceView()
        }
    }
}

struct DappReferenceView: View {
    // Pure CantonDappKit: the CIP-0103 method surface this dApp can drive.
    private let methods = DappMethod.allCases.filter { !$0.isEvent }

    var body: some View {
        NavigationStack {
            List {
                Section {
                    Text("A CIP-0103 dApp built on CantonDappKit. Connecting to a "
                        + "wallet over the LAN arrives with the Swift transport port; "
                        + "the ping and sign-in screens follow.")
                        .font(.callout)
                        .foregroundStyle(.secondary)
                }
                Section("Methods this dApp speaks") {
                    ForEach(methods, id: \.rawValue) { method in
                        Text(method.rawValue).font(.body.monospaced())
                    }
                }
            }
            .navigationTitle("Canton dApp")
        }
    }
}