import com.android.build.api.artifact.SingleArtifact
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

plugins {
    // AGP 9+ has built-in Kotlin support; no separate kotlin-android plugin needed.
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

// Prebuilt sherpa-onnx AAR version. Keep in sync with scripts/fetch-sherpa-onnx-aar.sh.
// Pinned to 1.10.46 (onnxruntime 1.17.1). Newer sherpa-onnx (1.11+) bundles onnxruntime built
// with KleidiAI SME2 matmul kernels that SIGILL on Snapdragon 8 Elite Gen 5 (SM8850): the CPU
// advertises SME but the SME2 `ptrue pn.b` path raises an illegal instruction. 1.10.46's
// onnxruntime predates KleidiAI and uses the NEON path. See SHERPA_ONNX.md.
val sherpaOnnxVersion = "1.10.46"

android {
    namespace = "dev.phucngu.simpletype"
    compileSdk = 37

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
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
    buildFeatures {
        compose = true
    }
    testOptions {
        unitTests.isIncludeAndroidResources = true
    }
}

androidComponents {
    onVariants(selector().all()) { variant ->
        variant.outputs.forEach { output ->
            output.outputFileName.set("SimpleType-${variant.name}.apk")
        }

        // Timestamp at execution time so the configuration cache can't freeze a stale value.
        val name = variant.name
        val apkDir = variant.artifacts.get(SingleArtifact.APK)
        val cap = name.replaceFirstChar { it.uppercase() }
        val stamp = tasks.register("stamp${cap}Apk") {
            inputs.dir(apkDir)
            doLast {
                val ts = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS"))
                val dir = apkDir.get().asFile
                val src = dir.resolve("SimpleType-$name.apk")
                if (src.exists()) {
                    val stale = Regex("SimpleType-$name-\\d{8}-\\d{6}-\\d{3}\\.apk")
                    dir.listFiles { f -> stale.matches(f.name) }?.forEach { it.delete() }
                    src.copyTo(dir.resolve("SimpleType-$name-$ts.apk"), overwrite = true)
                }
            }
        }
        afterEvaluate {
            tasks.named("assemble$cap").configure { finalizedBy(stamp) }
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

    // sherpa-onnx (Apache-2.0): on-device ASR via onnxruntime. The prebuilt .aar bundles the
    // com.k2fsa.sherpa.onnx Kotlin API and native libs (sherpa-onnx-jni, onnxruntime) for
    // arm64-v8a + x86_64. Drop the .aar into app/libs/ via scripts/fetch-sherpa-onnx-aar.sh.
    // Not on Maven Central, hence the file dependency. Used for Vietnamese voice typing.
    implementation(files("libs/sherpa-onnx-$sherpaOnnxVersion.aar"))

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
