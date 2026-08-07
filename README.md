# canton-mobile-app

iOS and Android apps for the Canton Network, built on
[canton-mobile-sdk](https://github.com/vsima/canton-mobile-sdk).

This is the **reference wallet** for the SDK: it proves the native wallet
stack end-to-end, targets featured-app status on the Global Synchronizer, and
leads with what no other Canton wallet offers — an open, validator-agnostic
client with self-custody keys held in the device enclave (P-256 external
parties are live-verified in the SDK's integration suite).

## Product direction

Strategy and research live in [`docs/`](docs/):

- [Product strategy & ecosystem review](docs/product-strategy-and-ecosystem-review.md)
  — what matters for a Canton app, the competitive wallet field, and the
  work-package parity plan against Digital Asset's TypeScript wallet SDK
- [Canton ecosystem strategy brief](docs/canton-ecosystem-strategy-brief.md)
  — ecosystem health, monetization, and go-to-market phasing (maintained
  edition; the [original PDF](docs/Canton-Ecosystem-Strategy-Brief.pdf) is
  kept as a snapshot)

Roadmap (sequenced in the strategy doc, §6):

1. **Portfolio & sync** — connection, auth, ACS-backed portfolio with
   offset-resumable streams *(SDK: shipped in v0.4.0)*
2. **Transfers & inbox** — CIP-0056 transfers with the propose→accept
   pending-actions inbox and push notifications *(SDK: shipped in v0.4.0,
   live-verified against a real Amulet registry — incl. one-step
   preapproved transfers; the app's UX on top is this phase's work)*
3. **Self-custody** — external-party onboarding with Secure Enclave /
   StrongBox keys *(SDK: shipped in v0.4.0; enclave verified on physical
   hardware — StrongBox module pending)*
4. **Corporate layer** — approvals, audit export, custody integrations
   *(SDK custody hook + wallet store shipped in v0.4.0)*, and a macOS
   treasury console from the same Swift package

## Layout

Mirrors the SDK monorepo:

- `ios/` — SwiftUI app. The Xcode project is generated from `project.yml`
  with [XcodeGen](https://github.com/yonaskolb/XcodeGen) and not committed.
- `android/` — Kotlin app (Gradle).

## SDK dependency

Both apps build against a **sibling checkout** of the SDK working tree, not a
published release:

```
<parent>/
├── canton-mobile-app/   (this repo)
└── canton-mobile-sdk/
```

- iOS: `ios/project.yml` references the SDK as a local Swift package at
  `../../canton-mobile-sdk`.
- Android: `android/settings.gradle.kts` uses a Gradle composite build
  (`includeBuild("../../canton-mobile-sdk/kotlin")`) that substitutes the
  `io.github.vsima.canton:*` coordinates with SDK source.

CI checks out both repos into the same sibling layout.

## Building

```sh
make ios       # xcodegen generate + simulator build
make android   # assembleDebug + assembleRelease (R8)
```

To exercise the ledger connection screen, run a local Canton node with the
SDK's `integration/run-canton.sh`. The iOS simulator reaches it on
`127.0.0.1:6865`, the Android emulator on `10.0.2.2:6865`.
