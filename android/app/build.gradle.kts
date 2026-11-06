// KAVACH Shield: the user's own phone (Message Shield, Link Check, kill-chain, stop card).
plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "kavach.app"
    compileSdk = 34
    defaultConfig {
        applicationId = "io.kavach.shield"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "0.1-phase1"
        ndk { abiFilters += listOf("arm64-v8a") }
    }
    // one shared demo key for every build (GitHub or a laptop): Shield, Guardian and Network Watch
    // only talk to each other when signed with the same key. Debug-only key, not for the Play Store.
    signingConfigs {
        getByName("debug") {
            storeFile = rootProject.file("kavach-shared-debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation(project(":shared"))
}
