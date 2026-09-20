plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

// ─────────────────────────────────────────────────────────────────────────────
//  Versions
// ─────────────────────────────────────────────────────────────────────────────

// NewPipeExtractor is resolved from JitPack. If a build ever fails to resolve, pin an
// explicit commit hash instead of a tag (JitPack rebuilds on demand):
//   ./gradlew :app:assembleRelease -PnewpipeExtractorVersion=<40-char-sha>
val newPipeExtractorVersion: String =
    providers.gradleProperty("newpipeExtractorVersion").getOrElse("v0.26.5")

// Optional release signing. When no keystore is supplied the release variant falls back
// to the debug keystore so that `assembleRelease` still produces an installable APK.
val releaseStoreFile: String? =
    (System.getenv("RELEASE_KEYSTORE_PATH") ?: providers.gradleProperty("RELEASE_KEYSTORE_PATH").getOrNull())
val releaseStorePassword: String? =
    (System.getenv("RELEASE_KEYSTORE_PASSWORD") ?: providers.gradleProperty("RELEASE_KEYSTORE_PASSWORD").getOrNull())
val releaseKeyAlias: String? =
    (System.getenv("RELEASE_KEY_ALIAS") ?: providers.gradleProperty("RELEASE_KEY_ALIAS").getOrNull()
        ?: "youtubelite")
val releaseKeyPassword: String? =
    (System.getenv("RELEASE_KEY_PASSWORD") ?: providers.gradleProperty("RELEASE_KEY_PASSWORD").getOrNull())

val hasReleaseKeystore: Boolean = !releaseStoreFile.isNullOrBlank() && file(releaseStoreFile!!).exists()

// ─────────────────────────────────────────────────────────────────────────────
//  Android configuration
// ─────────────────────────────────────────────────────────────────────────────

android {
    namespace = "com.app.youtube.lite"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.app.youtube.lite"
        minSdk = 26          // adaptive icons, HARDWARE bitmaps, EncryptedSharedPreferences
        targetSdk = 35       // Android 15 — edge-to-edge + FGS mediaPlayback semantics
        versionCode = 1
        versionName = "1.0.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables.useSupportLibrary = false

        buildConfigField("String", "NEWPIPE_EXTRACTOR_VERSION", "\"$newPipeExtractorVersion\"")
    }

    signingConfigs {
        if (hasReleaseKeystore) {
            create("release") {
                storeFile = file(releaseStoreFile!!)
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
                enableV1Signing = true
                enableV2Signing = true
                enableV3Signing = true
            }
        }
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
            isShrinkResources = false
            isDebuggable = true
            // Same applicationId as release so that behaviour (WebView login, cookies,
            // EncryptedSharedPreferences file names) is identical across variants.
        }

        release {
            isMinifyEnabled = true          // R8 full mode (see gradle.properties)
            isShrinkResources = true        // remove unreachable resources
            isDebuggable = false
            isJniDebuggable = false
            isPseudoLocalesEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            signingConfig = if (hasReleaseKeystore) {
                signingConfigs.getByName("release")
            } else {
                signingConfigs.getByName("debug")
            }
        }
    }

    // No dependency metadata block inside the APK (~1 KB plus a signature check at install).
    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }

    // Keep the shipped APK lean: media3/coil/compose ship ~80 locales of strings we never
    // display (the app ships an English-only UI).
    androidResources {
        localeFilters += listOf("en")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
        isCoreLibraryDesugaringEnabled = true
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources {
            excludes += setOf(
                "/META-INF/{AL2.0,LGPL2.1}",
                "/META-INF/DEPENDENCIES",
                "/META-INF/LICENSE",
                "/META-INF/LICENSE.txt",
                "/META-INF/LICENSE.md",
                "/META-INF/NOTICE",
                "/META-INF/NOTICE.txt",
                "/META-INF/NOTICE.md",
                "/META-INF/ASL2.0",
                "/META-INF/INDEX.LIST",
                "/META-INF/*.kotlin_builtins",
                "DebugProbesKt.bin",
                "kotlin-tooling-metadata.json",
                "**/*.proto",
            )
        }
        dex {
            // Deterministic dex layout: better install-time profile matching.
            useLegacyPackaging = false
        }
    }

    // The APK is a single universal artifact: splitting would fragment the downloaded file.
    splits { abi { isEnable = false } }

    lint {
        abortOnError = false
        checkReleaseBuilds = false
        warningsAsErrors = false
        disable += setOf("GradleDependency", "OldTargetApi", "AndroidGradlePluginVersion")
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

kotlin {
    jvmToolchain(17)
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11)
        freeCompilerArgs.addAll("-opt-in=kotlin.RequiresOptIn")
    }
}

androidComponents {
    beforeVariants(selector().all()) { variant ->
        // Never assemble a test-only variant in CI.
        variant.enableAndroidTest = false
    }
}

// ─────────────────────────────────────────────────────────────────────────────
//  Dependencies
// ─────────────────────────────────────────────────────────────────────────────

dependencies {
    // Coroutines + serialization
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.1")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.8.1")

    // AndroidX platform
    implementation("androidx.core:core-ktx:1.16.0")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.9.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.0")
    implementation("androidx.lifecycle:lifecycle-process:2.9.0")
    implementation("androidx.profileinstaller:profileinstaller:1.4.1")

    // Credentials (cookies + visitor id) & preferences
    implementation("androidx.security:security-crypto:1.1.0-alpha06")
    implementation("androidx.datastore:datastore-preferences:1.1.4")

    // Compose (BOM-managed)
    val composeBom = platform("androidx.compose:compose-bom:2025.05.01")
    implementation(composeBom)
    implementation("androidx.compose.runtime:runtime")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-text")
    implementation("androidx.compose.animation:animation")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")

    // Media3 / ExoPlayer
    implementation("androidx.media3:media3-exoplayer:1.5.1")
    implementation("androidx.media3:media3-exoplayer-dash:1.5.1")
    implementation("androidx.media3:media3-exoplayer-hls:1.5.1")
    implementation("androidx.media3:media3-datasource-okhttp:1.5.1")
    implementation("androidx.media3:media3-extractor:1.5.1")
    implementation("androidx.media3:media3-ui:1.5.1")
    implementation("androidx.media3:media3-session:1.5.1")

    // Networking + image pipeline
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("io.coil-kt:coil:2.7.0")
    implementation("io.coil-kt:coil-compose:2.7.0")

    // Stream extraction (signature deciphering, throttling params, player JS)
    implementation("com.github.TeamNewPipe:NewPipeExtractor:$newPipeExtractorVersion")

    // Backports java.util.{stream,Optional,List.of,Map.of,…} used by NewPipeExtractor
    // down to minSdk 26.
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.4")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.1")
}
