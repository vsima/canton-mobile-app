plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "io.github.vsima.canton.app"
    compileSdk = 36

    buildFeatures {
        compose = true
    }

    defaultConfig {
        applicationId = "io.github.vsima.canton.app"
        // java.time — used here and by the SDK's WalletRecord — is native
        // from API 26. Below that it needs core library desugaring, and a
        // wallet is not the place to carry a backport for Android 6/7.
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    // Substituted with the sibling SDK checkout via includeBuild; the
    // version only matters for a standalone build against Maven Central.
    implementation("io.github.vsima.canton:canton-wallet-sdk:0.6.0-SNAPSHOT")
    implementation("io.github.vsima.canton:canton-wallet-android:0.6.0-SNAPSHOT")
    // The CIP-0103 provider engine, and the WalletConnect transport adapter
    // (which brings canton-dapp transitively) — the wallet answers a dApp over
    // a WalletConnect session through these.
    implementation("io.github.vsima.canton:canton-dapp-wallet:0.6.0-SNAPSHOT")
    implementation("io.github.vsima.canton:canton-dapp-wc:0.6.0-SNAPSHOT")
    // Reown WalletKit — the WalletConnect client (relay, pairing, sessions).
    // Pinned to 1.6.13 (the last of the 1.6 line): walletkit 1.7.0 pulls
    // androidx.core 1.19.0, which requires compileSdk 37 + AGP 9.1; 1.6.13
    // stays within this project's compileSdk 36 / AGP 8.13.
    implementation("com.reown:android-core:1.6.13")
    implementation("com.reown:walletkit:1.6.13")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")
    // The app builds its own channel + vhost-aware HTTP client.
    implementation("io.grpc:grpc-okhttp:1.83.1")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")

    val composeBom = platform("androidx.compose:compose-bom:2025.01.00")
    implementation(composeBom)
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.material3:material3-adaptive-navigation-suite")
    implementation("androidx.compose.ui:ui")

    // De-facto standard QR bitmap generation.
    implementation("com.google.zxing:core:3.5.3")
    // Stock Google scanner activity for reading recipient QR codes.
    implementation("com.google.android.gms:play-services-code-scanner:16.1.0")
}
