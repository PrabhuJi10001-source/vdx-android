plugins {
    id("com.android.application")
    id("com.google.devtools.ksp") version "2.3.11"
}

android {
    namespace = "com.vdx"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.vdx.alpha"
        minSdk = 28
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0-alpha"

        // Export Room schemas so MigrationTestHelper can validate migration paths
        // (instrumented device tests) against versioned schema JSON. This is a
        // KSP project (not annotationProcessor), so the Room args go under ksp.
        javaCompileOptions {
            annotationProcessorOptions {
                arguments += mapOf("room.schemaLocation" to "$projectDir/schemas")
            }
        }
    }

    // KSP passes Room args (Room 2.8 KSP; schema export for MigrationTestHelper).
    ksp {
        arg("room.schemaLocation", "$projectDir/schemas")
        arg("room.incremental", "false")
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
        }
    }

    packaging {
        resources {
            excludes += setOf(
                "META-INF/INDEX.LIST",
                "META-INF/DEPENDENCIES",
                "META-INF/LICENSE",
                "META-INF/LICENSE.txt",
                "META-INF/NOTICE",
                "META-INF/NOTICE.txt"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    // AGP 9 built-in Kotlin: use the compilerOptions DSL (kotlinOptions is removed).
    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")

    // EncryptedSharedPreferences — Android Keystore-backed encrypted storage for
    // BYOK AI API keys (EncryptedFile + EncryptedSharedPreferences).
    implementation("androidx.security:security-crypto:1.1.0")

    // WorkManager — network-constrained background upload for telemetry batches
    // (essential, DPDP-clean events only). One worker, exponential backoff.
    implementation("androidx.work:work-runtime-ktx:2.9.1")

    // Kotlin Coroutines — structured concurrency for audio capture & service lifecycle
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    // Google Gen AI SDK — unified Gemini API (Developer API + Vertex AI)
    // 1.65.0+ supports the Gemini 3.x model family (Gemini 2.5 is EOL Oct 2026).
    implementation("com.google.genai:google-genai:1.66.0")

    // WebRTC VAD (GMM DSP filter, BSD-3 / MIT permissive) — voice-activity detection
    // for mic-session endpointing. ~140KB AAR, pure signal processing (no model download).
    // Cloudflare's Maven-Central build of gkonovalov/android-vad (package com.konovalov.vad.webrtc).
    implementation("com.cloudflare.realtimekit.android-vad:webrtc:2.0.9-cf.3")

    // Room — on-device persistent storage for user memory
    // 2.8.4 = latest 2.x (Room 3.0 is a major modernization; not adopted here).
    implementation("androidx.room:room-runtime:2.8.4")
    implementation("androidx.room:room-ktx:2.8.4")
    ksp("androidx.room:room-compiler:2.8.4")

    // Sentry — crash reporting + performance monitoring. 8.53.0 = latest.
    implementation("io.sentry:sentry-android:8.53.0")

    // Unit tests (Robolectric + JUnit + AndroidX Test)
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.14.1")
    testImplementation("androidx.test:core:1.6.1")
    testImplementation("androidx.test.ext:junit:1.2.1")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
    testImplementation("androidx.room:room-testing:2.6.1")
}
