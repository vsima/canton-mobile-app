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
        // The keystore driver (canton-wallet-android) needs 23.
        minSdk = 23
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

    val composeBom = platform("androidx.compose:compose-bom:2025.01.00")
    implementation(composeBom)
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui")

    // De-facto standard QR bitmap generation.
    implementation("com.google.zxing:core:3.5.3")
}
