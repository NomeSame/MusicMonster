plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

// The host and the separate private build consume these sources independently.
// Keep their generated outputs apart, including when both builds run concurrently.
layout.buildDirectory.set(rootProject.layout.projectDirectory.dir("build/plugin-api"))

android {
    namespace = "com.nomesame.musicmonster.plugin.api"
    compileSdk = 36
    defaultConfig { minSdk = 24 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlinOptions { jvmTarget = "11" }
}

dependencies {
    // Plain JVM unit tests only; the contract is framework-free by design.
    testImplementation(libs.junit)
}
