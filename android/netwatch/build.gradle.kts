// KAVACH Network Watch (OPTIONAL, separate app). A local DNS-only VpnService that judges the
// domains each app looks up (machine-generated malware domains, bank look-alikes) and tells
// KAVACH Shield. Unlike the main apps it needs the INTERNET permission, because it must
// forward the DNS lookups it inspects; that is why it is a separate, opt-in app.
// Nothing is sent anywhere except the lookups themselves, to the phone's normal DNS server.
plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "kavach.netwatch"
    compileSdk = 34
    defaultConfig {
        applicationId = "io.kavach.netwatch"
        minSdk = 29 // getConnectionOwnerUid (which app made the lookup)
        targetSdk = 34
        versionCode = 1
        versionName = "0.1-phase1"
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
    // only the models this app needs: domain model, link-checker model, genuine domains
    sourceSets["main"].assets.srcDirs(layout.buildDirectory.dir("nwassets"))
}

val copyAssets by tasks.registering(Copy::class) {
    from(rootProject.projectDir.resolve("../assets")) {
        include("genuine_domains.json", "models/domain_model.*", "models/url_model.*")
    }
    into(layout.buildDirectory.dir("nwassets"))
}
tasks.named("preBuild") { dependsOn(copyAssets) }

dependencies {
    implementation(project(":core"))
}
