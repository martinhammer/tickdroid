import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

android {
    namespace = "com.martinhammer.tickdroid"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.martinhammer.tickdroid"
        minSdk = 31
        targetSdk = 36
        versionCode = 6
        versionName = "1.0.5"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        create("release") {
            val ksPath = System.getenv("TICKDROID_KEYSTORE")
            if (ksPath != null) {
                storeFile = file(ksPath)
                storePassword = System.getenv("TICKDROID_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("TICKDROID_KEY_ALIAS") ?: "tickdroid"
                keyPassword = System.getenv("TICKDROID_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            // Only attach the signing config when the keystore env var is set, so F-Droid's
            // unsigned-build flow (which signs with their own key) still works.
            if (System.getenv("TICKDROID_KEYSTORE") != null) {
                signingConfig = signingConfigs.getByName("release")
            }
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    dependenciesInfo {
        // AGP embeds a Google-key-encrypted dependency-metadata block in the APK
        // signing block by default. F-Droid rejects it (opaque, non-reproducible), so
        // strip it from the APK. Kept in the AAB so Google Play still gets dependency
        // insights — the two outputs are independent (assembleRelease vs bundleRelease).
        includeInApk = false
        includeInBundle = true
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
    }
    packaging {
        resources {
            excludes += setOf(
                "META-INF/LICENSE.md",
                "META-INF/LICENSE-notice.md",
                "META-INF/AL2.0",
                "META-INF/LGPL2.1",
            )
        }
    }
}

configurations.configureEach {
    // io.opencensus:* is on F-Droid's tracker list. It is pulled in transitively only by
    // AGP's Unified Test Platform (gRPC's optional census integration) and never ships in
    // the APK, but it surfaces in the Gradle verification metadata that F-Droid scans and
    // gets flagged as a tracker. gRPC works fine without it (no-op census).
    exclude(group = "io.opencensus")
}

kotlin {
    // Kotlin 2.2 removed the android.kotlinOptions DSL (jvmTarget as String is now an error);
    // configure the compiler via the top-level compilerOptions DSL instead.
    compilerOptions {
        jvmTarget = JvmTarget.JVM_11
    }
}

ksp {
    // Room: write the schema JSON for each version into VCS so future migrations can diff
    // against it and MigrationTestHelper can replay it.
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)

    // Coroutines
    implementation(libs.kotlinx.coroutines.android)

    // Hilt
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.hilt.navigation.compose)
    implementation(libs.hilt.work)
    ksp(libs.androidx.hilt.compiler)
    // Dagger 2.60 generates code annotated with @CanIgnoreReturnValue but doesn't put the
    // errorprone annotation on the compile classpath; provide it (compile-time only).
    compileOnly(libs.errorprone.annotations)

    // Room
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    // Networking
    implementation(libs.retrofit)
    implementation(libs.retrofit.kotlinx.serialization)
    implementation(libs.okhttp)
    implementation(libs.okhttp.logging)
    implementation(libs.kotlinx.serialization.json)

    // WorkManager
    implementation(libs.androidx.work.runtime.ktx)

    // Navigation
    implementation(libs.androidx.navigation.compose)

    // Security
    implementation(libs.androidx.security.crypto)

    // Test
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.turbine)
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.androidx.room.testing)
    testImplementation(libs.mockk)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.room.testing)
    androidTestImplementation(libs.androidx.work.testing)
    androidTestImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(libs.turbine)
    androidTestImplementation(libs.okhttp.mockwebserver)
    androidTestImplementation(libs.mockk.android)
    androidTestImplementation(libs.hilt.android.testing)
    kspAndroidTest(libs.hilt.compiler)
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}
