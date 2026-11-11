// KAVACH design system (Midnight, Ivory and Gold): colours, Inter typeface, line icons and the
// programmatic components. Its own small module so the optional Network Watch app can use it
// without carrying the Llama runtime.
plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "kavach.ui"
    compileSdk = 34
    defaultConfig { minSdk = 26 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}
