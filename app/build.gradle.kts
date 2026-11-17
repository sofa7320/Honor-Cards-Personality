// 卡面工坊 (Card Personality) app module.
// - LSPosed module: legacy Xposed API (assets/xposed_init + xposedmodule meta-data),
//   API compat layer (de.robv shim) is packaged; modern libxposed API 102 is compileOnly.
// - UI: Miuix 0.9.0 / Compose AndroidX 1.10.5.
// Build ONLY through scripts/build.ps1 (exclusive lock).
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.cardpersonality.honor"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.cardpersonality.honor"
        minSdk = 28
        targetSdk = 36
        // VERSION POLICY (do not change):
        //  - versionCode is permanently pinned to 1 (never auto-incremented; no script bumps it).
        //  - versionName is semantic and user-visible (shown in-app) — bump per release.
        versionCode = 1
        versionName = "0.2.19"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    // ---------------------------------------------------------------- signing
    // Release signing is configured from environment variables (CI secrets or local env):
    //   RELEASE_STORE_FILE, RELEASE_STORE_PASSWORD, RELEASE_KEY_ALIAS, RELEASE_KEY_PASSWORD
    // When they are absent (e.g. contributor PRs), the release build falls back to debug
    // signing so CI keeps working; official releases provide all four values.
    val releaseStoreFile = System.getenv("RELEASE_STORE_FILE")
    val releaseStorePassword = System.getenv("RELEASE_STORE_PASSWORD")
    val releaseKeyAlias = System.getenv("RELEASE_KEY_ALIAS")
    val releaseKeyPassword = System.getenv("RELEASE_KEY_PASSWORD")
    val hasReleaseSigning = !releaseStoreFile.isNullOrBlank() &&
        file(releaseStoreFile).exists() &&
        !releaseStorePassword.isNullOrBlank() &&
        !releaseKeyAlias.isNullOrBlank()

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = file(releaseStoreFile)
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
        }
        release {
            isMinifyEnabled = false
            isShrinkResources = false
            signingConfig = if (hasReleaseSigning) {
                signingConfigs.getByName("release")
            } else {
                signingConfigs.getByName("debug")
            }
        }
    }

    packaging {
        resources.excludes += setOf(
            "META-INF/AL2.0",
            "META-INF/LGPL2.1",
            "META-INF/LICENSE*",
            "META-INF/NOTICE*",
            "META-INF/*.kotlin_module",
            "META-INF/*.version",
        )
    }

    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
        freeCompilerArgs.add("-Xjvm-default=all")
    }
}

dependencies {
    // Legacy Xposed API: compile-only local stubs, never packaged into the APK.
    compileOnly(files("libs/libxposed-api-102.jar"))

    implementation("androidx.core:core-ktx:1.16.0")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.4")

    implementation("androidx.compose.ui:ui:1.10.5")
    implementation("androidx.compose.foundation:foundation:1.10.5")
    implementation("androidx.compose.runtime:runtime:1.10.5")
    implementation("androidx.compose.material3:material3:1.3.0")
    implementation("androidx.compose.ui:ui-tooling-preview:1.10.5")
    debugImplementation("androidx.compose.ui:ui-tooling:1.10.5")

    implementation("top.yukonga.miuix.kmp:miuix-ui:0.9.0")
    implementation("top.yukonga.miuix.kmp:miuix-icons:0.9.0")
    implementation("top.yukonga.miuix.kmp:miuix-preference:0.9.0")
}
