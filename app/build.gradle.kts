plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "io.musicassistant.companion"
    compileSdk = 35

    defaultConfig {
        applicationId = "io.musicassistant.companion"
        minSdk = 26
        targetSdk = 35
        versionCode = 6
        versionName = "3.1.0"
    }

    signingConfigs {
        getByName("debug") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.service)
    implementation(libs.androidx.activity.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons.extended)

    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.datastore.preferences)

    // Media3 for MediaSession, notification, lock screen controls (no ExoPlayer)
    implementation(libs.androidx.media3.session)
    implementation(libs.androidx.media3.common)

    // Networking & serialization
    implementation(libs.okhttp)
    implementation(libs.kotlinx.serialization.json)

    // Image loading with caching
    implementation(libs.coil.compose)

    // Opus audio decoder (pure Java, no JNI)
    implementation(libs.concentus)

    debugImplementation(libs.androidx.ui.tooling)
}
