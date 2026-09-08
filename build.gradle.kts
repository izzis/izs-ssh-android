// AGP 9+ ships built-in Kotlin: the org.jetbrains.kotlin.android plugin must NOT
// be applied. The KGP version bundled with AGP is upgraded to the latest stable
// via the buildscript classpath below (documented upgrade path for built-in Kotlin).
buildscript {
    dependencies {
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:2.4.20")
    }
}

plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.compose.compiler) apply false
}

// Version 1: desktop parity for Config Sync + SSH profiles.
// The Raw layer (raw YAML) is preserved losslessly so download -> upload
// never loses config; defaults are only applied transiently in the Domain view.
