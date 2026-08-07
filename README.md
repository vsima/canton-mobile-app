# canton-mobile-app

iOS and Android apps for the Canton Network, built on
[canton-mobile-sdk](https://github.com/vsima/canton-mobile-sdk).

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
