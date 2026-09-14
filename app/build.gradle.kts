import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// No Kotlin plugin: AGP 9 has built-in Kotlin support and rejects org.jetbrains.kotlin.android.
plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "de.codevoid.camdl"
    compileSdk = 37

    defaultConfig {
        applicationId = "de.codevoid.camdl"

        // 33 rather than 30 so there is exactly one code path: BLUETOOTH_SCAN/CONNECT (31+),
        // NEARBY_WIFI_DEVICES (33+) and the non-deprecated GATT write and notify overloads
        // (33+) all exist. Supporting 30 would mean three compatibility branches through the
        // most delicate code in the app.
        minSdk = 33

        // Deliberately behind compileSdk: targeting 37 makes ACCESS_LOCAL_NETWORK
        // mandatory, and it is not documented whether an app-requested local-only
        // Wi-Fi network is exempt. That variable gets introduced on its own once the
        // camera protocol work is done, so a failure there is unambiguous.
        targetSdk = 36

        versionCode = (project.findProperty("appVersionCode") as String?)?.toIntOrNull() ?: 1
        versionName = (project.findProperty("appVersionName") as String?).takeIf { !it.isNullOrEmpty() } ?: "dev-local"
    }

    val keystorePath = System.getenv("SIGNING_KEYSTORE_PATH")
    val keystorePassword = System.getenv("SIGNING_KEYSTORE_PASSWORD")
    val signingKeyAlias = System.getenv("SIGNING_KEY_ALIAS")
    val signingKeyPassword = System.getenv("SIGNING_KEY_PASSWORD")
    val hasSigningConfig = !keystorePath.isNullOrEmpty() &&
        !keystorePassword.isNullOrEmpty() &&
        !signingKeyAlias.isNullOrEmpty() &&
        !signingKeyPassword.isNullOrEmpty()

    if (hasSigningConfig) {
        signingConfigs {
            create("release") {
                storeFile = file(keystorePath!!)
                storePassword = keystorePassword
                keyAlias = signingKeyAlias
                keyPassword = signingKeyPassword
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (hasSigningConfig) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        buildConfig = true
        viewBinding = true
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_17
    }
}

dependencies {
    implementation(project(":protocol"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.activity.ktx)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.recyclerview)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    implementation(libs.google.material)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.okhttp)
}
