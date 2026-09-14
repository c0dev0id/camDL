// AGP 9 compiles Kotlin itself and pins the Kotlin Gradle plugin it bundles (2.2.10 for the
// 9.4 line). :protocol is a plain JVM module on a newer Kotlin, and an Android module cannot
// read metadata produced by a compiler newer than its own - so lift AGP's built-in Kotlin to
// the same version rather than letting the two drift apart.
// https://developer.android.com/build/releases/agp-9-0-0-release-notes
buildscript {
    dependencies {
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:${libs.versions.kotlin.get()}")
    }
}

plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.jvm) apply false
}
