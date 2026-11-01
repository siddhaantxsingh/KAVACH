// Pure Kotlin/JVM: all detection logic, unit-testable on a laptop (./gradlew :core:test).
plugins { id("org.jetbrains.kotlin.jvm") }

java { toolchain { languageVersion.set(JavaLanguageVersion.of(17)) } }

dependencies {
    testImplementation("junit:junit:4.13.2")
}

tasks.test {
    workingDir = rootProject.projectDir.resolve("core")
}
