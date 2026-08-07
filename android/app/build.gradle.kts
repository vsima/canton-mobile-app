plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "io.github.vsima.canton.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "io.github.vsima.canton.app"
        minSdk = 21
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
    implementation("io.github.vsima.canton:canton-sdk:0.4.0-SNAPSHOT")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")
}
