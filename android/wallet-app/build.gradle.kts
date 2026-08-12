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
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
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
