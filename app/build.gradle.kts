plugins {
    // AGP 9+ has built-in Kotlin support; no separate kotlin-android plugin needed.
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "dev.phucngu.simpletype"
    compileSdk = 36 // Let's simplify this compilation syntax if needed, keeping standard 36

    defaultConfig {
        applicationId = "dev.phucngu.simpletype"
        minSdk = 28
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // Vosk ships prebuilt native libs for 4 ABIs; keep only modern phones (arm64-v8a)
        // and the common emulator ABI (x86_64) to stay near the APK size target.
        ndk {
            abiFilters += listOf("arm64-v8a", "x86_64")
        }
    }

    buildTypes {
        release {
            optimization {
                enable = false
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
    }
    testOptions {
        unitTests.isIncludeAndroidResources = true
    }

    // Native whisper.cpp build (PhoWhisper for Vietnamese voice typing). Opt-in: it requires the
    // Android NDK + CMake and the whisper.cpp submodule, so it is only wired in when assembling
    // with -PwithWhisper. The default build stays NDK-free; WhisperAsrEngine reports itself
    // unavailable when libwhisper_jni.so is absent and the IME falls back to Vosk.
    if (project.hasProperty("withWhisper")) {
        // Override on the command line if a different NDK is installed: -PndkVersion=...
        ndkVersion = (project.findProperty("ndkVersion") as String?) ?: "27.0.12077973"
        externalNativeBuild {
            cmake {
                path = file("src/main/cpp/CMakeLists.txt")
                version = "3.22.1"
            }
        }
    }
}

dependencies {
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.core.ktx)
    implementation(libs.material)
    implementation(libs.kotlinx.coroutines.android)
    // On-device streaming ASR (Apache-2.0). Ships prebuilt native libs in its AAR — no NDK.
    implementation(libs.vosk.android)
    implementation(libs.jna) { artifact { type = "aar" } }

    // Jetpack Compose
    val composeBom = platform(libs.androidx.compose.bom)
    implementation(composeBom)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    debugImplementation(libs.androidx.compose.ui.tooling)

    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
}
