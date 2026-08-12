pluginManagement {
    repositories {
        google()
        gradlePluginPortal()
        mavenCentral()
    }
}

plugins {
    // Auto-provisions the JDK requested by jvmToolchain().
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "canton-mobile-app"

// Composite build: substitutes io.github.vsima.canton:* dependencies with
// the SDK modules from the sibling checkout, so the apps always track the
// SDK working tree. CI reproduces the same layout.
includeBuild("../../canton-mobile-sdk/kotlin")

// Two reference apps, one build:
//   :wallet-app — the wallet, links the wallet SDK stack.
//   :dapp-app   — a CIP-0103 dApp, links ONLY canton-dapp (+ the LAN
//                 transport). It cannot see the signing drivers or the ledger
//                 stubs, which is what makes the module layering visible.
include(":wallet-app")
include(":dapp-app")
