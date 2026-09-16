import java.io.FileInputStream
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.compose.compiler)
}

// Release signing inputs, resolved once at configuration time (file scope —
// NOT inside android{}: the Android DSL receiver shadows plain `java.*`
// references and breaks script compilation).
// keystore.properties is generated in CI from GitHub Secrets and never
// committed; local builds without the file still work (release APK is then
// left unsigned instead of failing the build).
val keystoreProps = Properties()
val keystorePropsFile = rootProject.file("keystore.properties")
if (keystorePropsFile.exists()) {
    FileInputStream(keystorePropsFile).use { stream -> keystoreProps.load(stream) }
}
// Optional overrides from CI: -PversionNameOverride=1.2.3 (tag v1.2.3
// stripped of the leading 'v'), -PversionCodeOverride=5.
val versionNameOverride = (findProperty("versionNameOverride") as String?)
    ?.takeIf { it.isNotBlank() }
val versionCodeOverride = (findProperty("versionCodeOverride") as String?)
    ?.toIntOrNull()

android {
    namespace = "id.web.izs.sshclient"
    // compileSdk 37 is required by okhttp 5.x; targetSdk stays 36 for stable runtime behavior.
    compileSdk = 37

    defaultConfig {
        applicationId = "id.web.izs.sshclient"
        minSdk = 26
        targetSdk = 36
        versionCode = versionCodeOverride ?: 1
        versionName = versionNameOverride ?: "1.2.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        create("release") {
            if (keystoreProps.containsKey("storeFile")) {
                storeFile = rootProject.file(keystoreProps["storeFile"] as String)
                storePassword = keystoreProps["storePassword"] as String
                keyAlias = keystoreProps["keyAlias"] as String
                keyPassword = keystoreProps["keyPassword"] as String
            }
        }
    }

    buildTypes {
        release {
            // Signed in CI (keystore.properties present). Locally without the
            // file the config has no credentials — Gradle leaves the APK
            // unsigned instead of failing the build.
            if (keystoreProps.containsKey("storeFile")) {
                signingConfig = signingConfigs.getByName("release")
            }
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    // Built-in Kotlin (AGP 9+): android.kotlinOptions is replaced by this DSL.
    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }
    buildFeatures {
        buildConfig = true
        compose = true
    }
    packaging {
        resources {
            excludes += setOf(
                "META-INF/LICENSE.md",
                "META-INF/LICENSE",
                "META-INF/NOTICE",
                "META-INF/DEPENDENCIES",
            )
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.lifecycle.runtime.ktx)
    implementation(libs.lifecycle.runtime.compose)
    implementation(libs.lifecycle.viewmodel)
    implementation(libs.lifecycle.viewmodel.ktx)
    implementation(libs.activity.compose)

    val composeBom = libs.compose.bom
    implementation(platform(composeBom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons)
    implementation(libs.navigation.compose)

    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.okhttp)
    implementation(libs.snakeyaml)
    implementation(libs.security.crypto)

    // SSH: sshj is actively maintained (ed25519, modern KEX). Do NOT use the original JSch (abandoned).
    implementation(libs.sshj)
    implementation(libs.slf4j.api)
    implementation(libs.slf4j.nop)
    implementation(libs.bcprov)
    implementation(libs.bcpkix)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.mockwebserver3)
    // Test-only: local SSH server for the multiplex regression tests
    // (SshMultiplexTest). Never ships in the APK (testImplementation).
    testImplementation(libs.mina.sshd)
    // Test-only: SFTP subsystem for the transfer tests (SftpTransferTest,
    // SftpTransferManagerTest). Never ships in the APK (testImplementation).
    testImplementation(libs.mina.sshd.sftp)
}
