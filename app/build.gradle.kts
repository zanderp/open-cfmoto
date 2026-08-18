plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose.compiler)
}

android {
    namespace = "dev.zanderp.opencfmoto"
    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }

    // Slim is the default ship shape: arm64-only + R8. Opt out with -PslimApk=false (fat debug/CI).
    val slimApk = (project.findProperty("slimApk") as String?)?.equals("false", ignoreCase = true) != true

    defaultConfig {
        applicationId = "dev.zanderp.opencfmoto"
        minSdk = 29
        targetSdk = 36
        // Optional build number (-PbuildNumber) bumps versionCode/versionName so a newer local build
        // installs over an older one. Blank → the upstream base version (2.0.13-pre / 68 when synced
        // with upstream/main).
        val buildNumber = (project.findProperty("buildNumber") as String?)?.takeIf { it.isNotBlank() }
        versionCode = buildNumber?.toIntOrNull() ?: 68
        versionName = "2.0.13-pre" + (buildNumber?.let { ".$it" } ?: "")

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // Default OpenRouteService key used when the rider hasn't entered their own. Supply it via
        // `-PorsApiKey=...`, an `orsApiKey` in gradle.properties, or the ORS_API_KEY env var so the
        // key isn't hardcoded in source. Empty → routing falls back to the OSRM demo, then beeline.
        val orsDefaultKey = (project.findProperty("orsApiKey") as String?)
            ?: System.getenv("ORS_API_KEY")
            ?: ""
        buildConfigField("String", "ORS_API_KEY", "\"$orsDefaultKey\"")

        // Anonymous telemetry Worker base URL (no trailing slash). Empty disables uploads.
        // CFMOTO-first fork: OFF by default (explicit consent). Opt in with -PtelemetryUrl=… .
        val telemetryUrl = (project.findProperty("telemetryUrl") as String?)
            ?: System.getenv("TELEMETRY_URL")
            ?: ""
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

        if (slimApk) {
            ndk {
                abiFilters += listOf("arm64-v8a")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = slimApk
            isShrinkResources = slimApk
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
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
    // BRouter (MIT on-device offline routing) now ships INSIDE the Overtake library (:overtake-maps
    // depends on the lib's own :brouter); the app pulls it transitively from the composite build. The
    // fork's identical copy + this direct dependency were retired in the Router-extraction stage.
    // Compile-time OkHttp for MapLibre cellular pin (MapLibre brings it as runtime only).
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // Extracted Overtake map/routing/search library, consumed via the composite build declared in
    // settings.gradle.kts (Gradle substitutes this for the included build's :overtake-maps). Provides
    // the pure-Kotlin MODEL classes (MapPlace/PoiChip/RouteOptions/RouteMode via typealias), the
    // place-SEARCH stack (Stage 1) and the ROUTING engine (Stage 2 — OfflineMaps.create().router, with
    // the vendored BRouter offline engine). osmdroid/maplibre/okhttp transitives match the versions
    // above and dedupe by coordinates. The BRouter `exclude` is gone: the app no longer ships its own
    // :brouter, so the lib's copy is the single btools.* provider (no dex-merge duplicate).
    implementation("dev.overtake:overtake-maps:0.1.0-dev")

    // Jetpack Compose UI stack (cockpit redesign). BOM keeps the family in sync.
    implementation(platform(libs.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.datastore.preferences)
    debugImplementation(libs.compose.ui.tooling)

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
}