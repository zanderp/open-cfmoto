plugins {
    alias(libs.plugins.android.application)
    id("org.jetbrains.kotlin.plugin.compose") version "2.0.0"
}

android {
    namespace = "dev.zanderp.opencfmoto"
    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }

    // Slim is the default ship shape: one ABI + R8. Opt out with -PslimApk=false (fat debug/CI).
    // -Pabi=armeabi-v7a ships the 32-bit ARM APK for phones whose Android is still 32-bit.
    val slimApk = (project.findProperty("slimApk") as String?)?.equals("false", ignoreCase = true) != true
    val abiFilter = (project.findProperty("abi") as String?)?.trim().orEmpty()

    defaultConfig {
        applicationId = "dev.zanderp.opencfmoto"
        minSdk = 29
        targetSdk = 36
        versionCode = 77
        versionName = "2.0.18"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // Default OpenRouteService key used when the rider hasn't entered their own. Supply it via
        // `-PorsApiKey=...`, an `orsApiKey` in gradle.properties, or the ORS_API_KEY env var so the
        // key isn't hardcoded in source. Empty → routing falls back to the OSRM demo, then beeline.
        val orsDefaultKey = (project.findProperty("orsApiKey") as String?)
            ?: System.getenv("ORS_API_KEY")
            ?: ""
        buildConfigField("String", "ORS_API_KEY", "\"$orsDefaultKey\"")

        // Anonymous telemetry Worker base URL (no trailing slash). Empty disables uploads.
        // Override: -PtelemetryUrl=https://….workers.dev  or TELEMETRY_URL env / gradle.properties
        val telemetryUrl = (project.findProperty("telemetryUrl") as String?)
            ?: System.getenv("TELEMETRY_URL")
            ?: "https://opencfmoto-telemetry.hello-3d9.workers.dev"
        buildConfigField("String", "TELEMETRY_URL", "\"$telemetryUrl\"")

        // Short git hash for Share Logs triage (configuration-cache safe).
        val gitHash = providers.exec {
            commandLine("git", "rev-parse", "--short", "HEAD")
            workingDir(rootProject.projectDir)
            isIgnoreExitValue = true
        }.standardOutput.asText.map { text ->
            val t = text.trim()
            if (t.matches(Regex("[0-9a-f]{4,40}"))) t else "unknown"
        }.orElse("unknown")
        buildConfigField("String", "GIT_HASH", "\"${gitHash.get()}\"")

        if (slimApk || abiFilter.isNotEmpty()) {
            ndk {
                abiFilters += listOf(abiFilter.ifEmpty { "arm64-v8a" })
            }
        }
    }

    signingConfigs {
        create("debugRelease") {
            storeFile = file("${System.getProperty("user.home")}/.android/debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = slimApk
            isShrinkResources = slimApk
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("debugRelease")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    buildFeatures {
        buildConfig = true
        compose = true
    }

    // Wireless Android Auto needs the packaged aa_privkey (same as prior releases).
    lint {
        disable += "PackagedPrivateKey"
        checkReleaseBuilds = true
    }
}

dependencies {
    implementation(libs.androidx.activity.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.core.ktx)
    implementation(libs.material)
    implementation(libs.mlkit.barcodescanner)
    implementation(libs.androidx.camera.core)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)
    implementation(libs.jmdns)
    implementation(libs.protobuf.java)
    implementation(libs.conscrypt.android)
    implementation(libs.osmdroid)
    implementation(libs.maplibre)
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)

    // Compose libraries
    val composeBom = platform("androidx.compose:compose-bom:2024.02.00")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.runtime:runtime")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.8.2")
}