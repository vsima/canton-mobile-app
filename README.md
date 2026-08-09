# canton-mobile-app

[![android](https://github.com/vsima/canton-mobile-app/actions/workflows/android.yml/badge.svg)](https://github.com/vsima/canton-mobile-app/actions/workflows/android.yml)
[![ios](https://github.com/vsima/canton-mobile-app/actions/workflows/ios.yml/badge.svg)](https://github.com/vsima/canton-mobile-app/actions/workflows/ios.yml)

Reference wallets for the Canton Network — iOS (SwiftUI) and Android
(Jetpack Compose / Material 3) — built on
[canton-mobile-sdk](https://github.com/vsima/canton-mobile-sdk). They exist
to prove the native wallet stack end-to-end with stock platform components:
everything the apps do goes through the SDK's public API.

## What they demonstrate

- **Self-custody on device hardware.** External-party onboarding with keys
  generated in the Secure Enclave (iOS) or Android Keystore (StrongBox with
  TEE fallback), never leaving the device. The signer sheet reports the
  achieved security level honestly — including "software" in simulators.
- **Verify before signing.** Every externally-signed transaction goes
  through the SDK's client-side prepared-transaction hash verification: the
  hardware key only signs hashes the device has independently recomputed
  from the transaction itself.
- **CIP-0056 token standard.** Portfolio (holdings rolled up per
  instrument), the propose→accept inbox with on-device signed
  accept/reject, transfers with memos via registry choice contexts, and
  holdings history with transaction detail.
- **Transfer preapprovals as a product feature.** "Instant receiving" is a
  switch: on requests a receiver-signed preapproval (the validator
  automation accepts and pays); off exercises `TransferPreapproval_Cancel`,
  also signed on-device.
- **Cross-device payments.** The Send screen's QR scanner (VisionKit on
  iOS, Google code scanner on Android) reads another wallet's Receive code.
- **Adaptive layouts from stock components.** NavigationSplitView sidebar
  on iPad; `NavigationSuiteScaffold` bar→rail on Android phones, tablets,
  and foldables.

## Layout

Mirrors the SDK monorepo:

- `ios/` — SwiftUI app. The Xcode project is generated from `project.yml`
  with [XcodeGen](https://github.com/yonaskolb/XcodeGen) and not committed.
- `android/` — Kotlin app (Gradle).

## SDK dependency

Both apps build against a **sibling checkout** of the SDK working tree, not
a published release:

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

## Running against a live network

The apps expect a Splice LocalNet (the SDK's `integration/run-localnet.sh`
boots one). Simulators and emulators reach it directly; physical devices
use adb reverse tunnels:

```sh
adb reverse tcp:2901 tcp:2901   # ledger gRPC
adb reverse tcp:4000 tcp:4000   # scan / registry
adb reverse tcp:2000 tcp:2000   # validator API
```

then launch with the host override:
`adb shell am start -n io.github.vsima.canton.app/.MainActivity --es host 127.0.0.1`.
On Android emulators the app defaults to the `10.0.2.2` host bridge. The
SDK repo's faucet tool (`LocalNetFaucetTool`) seeds test balances and
pending offers.

## License

Apache-2.0 — see [LICENSE](LICENSE) and [NOTICE](NOTICE).
