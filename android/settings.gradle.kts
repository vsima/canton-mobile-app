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
// the SDK modules from the sibling checkout, so the app always tracks the
// SDK working tree. CI reproduces the same layout.
includeBuild("../../canton-mobile-sdk/kotlin")

include(":app")
