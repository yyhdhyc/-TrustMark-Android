import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

val keystoreProperties = Properties()
val keystorePropertiesFile = rootProject.file("keystore.properties")
if (keystorePropertiesFile.isFile) {
    keystorePropertiesFile.inputStream().use(keystoreProperties::load)
}

fun signingValue(property: String, environment: String): String? =
    keystoreProperties.getProperty(property) ?: System.getenv(environment)

val releaseStorePath = signingValue("storeFile", "TRUSTMARK_KEYSTORE")
val releaseStorePassword = signingValue("storePassword", "TRUSTMARK_KEYSTORE_PASSWORD")
val releaseKeyAlias = signingValue("keyAlias", "TRUSTMARK_KEY_ALIAS")
val releaseKeyPassword = signingValue("keyPassword", "TRUSTMARK_KEY_PASSWORD")
val hasReleaseSigning = !releaseStorePath.isNullOrBlank() &&
    !releaseStorePassword.isNullOrBlank() &&
    !releaseKeyAlias.isNullOrBlank() &&
    !releaseKeyPassword.isNullOrBlank()

android {
    namespace = "com.trustmarktool"
    compileSdk = 35
    ndkVersion = "27.0.12077973"

    defaultConfig {
        applicationId = "com.trustmarktool"
        minSdk = 26
        targetSdk = 35
        versionCode = 2
        versionName = "1.1.0"

        // The Rust wrapper is only built for 64-bit ABIs. ONNX Runtime's AAR
        // supplies libonnxruntime.so for the same ABIs.
        ndk {
            abiFilters += listOf("arm64-v8a", "x86_64")
        }
    }

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = rootProject.file(requireNotNull(releaseStorePath))
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            if (hasReleaseSigning) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    androidResources {
        // When models are optionally bundled under assets/models, keep them
        // uncompressed so the first-launch copy is fast and memory-friendly.
        noCompress += "onnx"
    }

    packaging {
        jniLibs {
            // Keep nativeLibraryDir populated so the Rust bridge can pass an
            // absolute libonnxruntime.so path to ort::init_from().
            useLegacyPackaging = true
            // Both the ORT AAR and the NDK may contribute this runtime.
            pickFirsts += "**/libc++_shared.so"
        }
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

// Optional one-command native build:
//   ./gradlew :app:assembleDebug -PbuildNative=true
// Requires Rust 1.88+, cargo-ndk, and ANDROID_NDK_HOME (or a local.properties ndk.dir).
val buildNative = providers.gradleProperty("buildNative").orNull?.toBoolean() == true
if (buildNative) {
    val cargoNdk = tasks.register<Exec>("cargoNdk") {
        group = "build"
        description = "Cross-compiles the TrustMark Rust JNI library for Android"
        workingDir = rootProject.file("native")
        environment("ANDROID_NDK_HOME", android.ndkDirectory.absolutePath)
        commandLine(
            "cargo", "ndk",
            "-t", "arm64-v8a",
            "-t", "x86_64",
            "-P", "26",
            "-o", rootProject.file("app/src/main/jniLibs").absolutePath,
            "build", "--release",
        )
    }
    tasks.named("preBuild").configure { dependsOn(cargoNdk) }
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.work:work-runtime-ktx:2.10.0")

    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    debugImplementation("androidx.compose.ui:ui-tooling")

    implementation("io.coil-kt:coil-compose:2.7.0")
    implementation("androidx.exifinterface:exifinterface:1.3.7")

    // ONNX Runtime 1.22.0 == ORT C API version 22, which is exactly what
    // ort 2.0.0-rc.10 (pinned by TrustMark 0.4.0) speaks. The Rust library
    // loads libonnxruntime.so from this AAR at runtime (ort's load-dynamic).
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.22.0")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit:2.0.21")
}
