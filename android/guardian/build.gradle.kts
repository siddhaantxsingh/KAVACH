// KAVACH Guardian: the spare phone that listens to the speaker-phone call.
plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "kavach.guardian"
    compileSdk = 34
    defaultConfig {
        applicationId = "io.kavach.guardian"
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
    packaging { jniLibs { useLegacyPackaging = true } }
}

dependencies {
    implementation(project(":shared"))
    // sherpa-onnx (streaming ASR + Silero VAD); AAR from the k2-fsa GitHub release
    implementation(files("libs/sherpa-onnx-${property("sherpaOnnxVersion")}.aar"))
}
