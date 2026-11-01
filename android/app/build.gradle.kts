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
