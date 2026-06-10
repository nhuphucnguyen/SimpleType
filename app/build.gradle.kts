plugins {
    // AGP 9+ has built-in Kotlin support; no separate kotlin-android plugin needed.
    alias(libs.plugins.android.application)
}

android {
    namespace = "dev.phucngu.simpletype"
    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }

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
}

dependencies {
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.core.ktx)
    implementation(libs.material)
    implementation(libs.kotlinx.coroutines.android)
    // On-device streaming ASR (Apache-2.0). Ships prebuilt native libs in its AAR — no NDK.
    implementation(libs.vosk.android)
    implementation(libs.jna) { artifact { type = "aar" } }
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
}
