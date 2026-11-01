// Android library shared by both apps: assets, runtime loader, Llama (ExecuTorch), TTS, device link, perf stats.
plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "kavach.shared"
    compileSdk = 34
    defaultConfig { minSdk = 26 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    // The repo-level assets/ folder (lexicon, playbook, domains, linear models) ships inside the APKs.
    sourceSets["main"].assets.srcDirs(rootProject.projectDir.resolve("../assets"))
    androidResources { ignoreAssetsPattern = "!parity:!*.md" }
}

dependencies {
    api(project(":core"))
    api("org.pytorch:executorch-android:${property("executorchVersion")}")
}
