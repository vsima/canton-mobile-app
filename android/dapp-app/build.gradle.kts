plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "io.github.vsima.canton.dapp.app"
    compileSdk = 36

    buildFeatures {
        compose = true
    }

    defaultConfig {
        // A distinct id from the wallet: this installs as its own app, so the
        // two references can run side by side on one device (and, later, the
        // dApp on one device talking to the wallet on another).
        applicationId = "io.github.vsima.canton.dapp.app"
        // Matches the SDK's floor (java.time / java.util.Base64 are API 26).
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
    // The whole point of the split: a dApp links the dApp API and a transport,
    // and NOTHING from the wallet stack. It has no way to reach a SigningDriver
    // or the Ledger API stubs — the R8 release build proves that stays true.
    implementation("io.github.vsima.canton:canton-dapp:0.6.0-SNAPSHOT")
    implementation("io.github.vsima.canton:canton-dapp-lan:0.6.0-SNAPSHOT")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")

    val composeBom = platform("androidx.compose:compose-bom:2025.01.00")
    implementation(composeBom)
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui")
}
