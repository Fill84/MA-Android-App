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
        versionCode = 9
        versionName = "5.0.1"
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

    testOptions {
        // Let android.* stubs (Log, BitmapFactory, ...) return defaults instead of
        // throwing "not mocked" in plain JVM unit tests. Robolectric tests run the real
        // framework on the JVM (no device) to verify what a MediaController/Bluetooth reads.
        unitTests.isReturnDefaultValues = true
        unitTests.isIncludeAndroidResources = true
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

    testImplementation(libs.junit4)
    testImplementation(libs.mockk)
    testImplementation(libs.coroutines.test)
    testImplementation(libs.turbine)
    testImplementation(libs.robolectric)
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
}
