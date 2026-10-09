import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

// Signing secrets live in keystore.properties (gitignored), never in the repo.
// If absent (CI/other machines), release stays unsigned instead of failing.
val keystorePropertiesFile = rootProject.file("keystore.properties")
val keystoreProperties = Properties().apply {
    if (keystorePropertiesFile.exists()) {
        keystorePropertiesFile.inputStream().use { load(it) }
    }
}

android {
    namespace = "com.nomesame.musicmonster"
    compileSdk {
        version = release(36)
    }

    defaultConfig {
        applicationId = "com.nomesame.musicmonster"
        minSdk = 24
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        // Set false to hide the language-settings entry in every build; the implementation stays.
        buildConfigField("boolean", "SHOW_LANGUAGE_SETTINGS", "true")
    }

    signingConfigs {
        create("release") {
            if (keystorePropertiesFile.exists()) {
                storeFile = rootProject.file(keystoreProperties.getProperty("storeFile"))
                storePassword = keystoreProperties.getProperty("storePassword")
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            signingConfig =
                if (keystorePropertiesFile.exists()) signingConfigs.getByName("release") else null
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlinOptions {
        jvmTarget = "11"
    }
    buildFeatures {
        buildConfig = true
        compose = true
    }
    bundle {
        // Offline language switching needs every translation installed, including Play bundles.
        language {
            enableSplit = false
        }
    }
    testOptions {
        // Pure-JVM tests exercise MusicLogic and Song; let Android framework
        // stubs (e.g. Uri.parse) return default values instead of throwing
        // "not mocked", so fixtures can construct Songs on the JVM.
        unitTests.isReturnDefaultValues = true
        // Service notification regression tests need the real bundled strings/layouts.
        unitTests.isIncludeAndroidResources = true
    }
}

dependencies {

    implementation("androidx.compose.material:material-icons-extended")
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    // Media3 (successor of the deprecated ExoPlayer 2.x) for media playback
    implementation("androidx.media3:media3-exoplayer:1.4.1")
    implementation("androidx.media3:media3-common:1.4.1")
    // MediaSessionCompat for lock‑screen controls and background playback
    implementation("androidx.media:media:1.7.1")
    implementation("androidx.documentfile:documentfile:1.0.1")
    // Deliberate, branded splash (backwards-compatible to API 24)
    implementation("androidx.core:core-splashscreen:1.0.1")
    // Custom-background personalization: palette extraction + viewModelScope
    implementation("androidx.palette:palette-ktx:1.0.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.10.0")
    testImplementation(libs.junit)
    // Robolectric runs Android framework code (Uri, SharedPreferences,
    // ContentResolver, ...) on the JVM, so unit tests can exercise repository
    // logic and construct real Song fixtures without a device/emulator.
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}
