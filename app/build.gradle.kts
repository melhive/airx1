import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.net.URI
import java.security.MessageDigest

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

// Release signing comes from environment variables (set by CI from GitHub Secrets).
// If they are missing, only the debug build is produced, so CI never breaks.
val releaseKeystorePath: String? = System.getenv("AIRX1_KEYSTORE_PATH")
val hasReleaseSigning = releaseKeystorePath != null && file(releaseKeystorePath).exists()

android {
    namespace = "com.melhive.airx1"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.melhive.airx1"
        minSdk = 26
        targetSdk = 35
        // Release CI sets these from the release workflow's run number, so every
        // release has a higher versionCode and installs as an update.
        versionCode = System.getenv("AIRX1_VERSION_CODE")?.toIntOrNull() ?: 1
        versionName = System.getenv("AIRX1_VERSION_NAME") ?: "0.1.0-dev"
        ndk {
            // Real phones only; keeps the APK small.
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
        }
    }

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = file(releaseKeystorePath!!)
                storePassword = System.getenv("AIRX1_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("AIRX1_KEY_ALIAS") ?: "airx1"
                keyPassword = System.getenv("AIRX1_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        debug {
            // Debug builds install as a separate app, so they never clash with the signed release.
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
        release {
            isMinifyEnabled = false
            if (hasReleaseSigning) signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    androidResources {
        noCompress += "task"
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

// ---- Hand landmarker model: downloaded at build time, never committed ----
val modelUrl =
    "https://storage.googleapis.com/mediapipe-models/hand_landmarker/hand_landmarker/float16/1/hand_landmarker.task"
val modelFile = layout.projectDirectory.file("src/main/assets/hand_landmarker.task").asFile
val modelSha = rootProject.file("model.sha256").readText().trim()

val downloadHandModel by tasks.registering {
    description = "Downloads the MediaPipe hand landmarker model into assets if missing."
    outputs.file(modelFile)
    doLast {
        fun sha256(f: File): String =
            MessageDigest.getInstance("SHA-256").digest(f.readBytes()).joinToString("") { "%02x".format(it) }
        if (!modelFile.exists() || sha256(modelFile) != modelSha) {
            modelFile.parentFile.mkdirs()
            logger.lifecycle("Downloading hand_landmarker.task …")
            URI(modelUrl).toURL().openStream().use { input -> modelFile.outputStream().use { input.copyTo(it) } }
        }
        val actual = sha256(modelFile)
        check(actual == modelSha) {
            "hand_landmarker.task checksum mismatch: expected $modelSha, got $actual. " +
                "If you intentionally changed the model, update model.sha256."
        }
    }
}
tasks.named("preBuild") { dependsOn(downloadHandModel) }

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.service)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.core)
    implementation(libs.androidx.camera.core)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.mediapipe.tasks.vision)
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(libs.junit)
}
