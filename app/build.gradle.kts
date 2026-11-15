plugins {
    id("com.android.application")
}

android {
    buildFeatures {
        buildConfig = true
    }

    namespace = "com.search.browser"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.search.browser"
        minSdk = 29
        targetSdk = 36
        versionCode = 2
        versionName = "1.0.1"
        // The app has no bundled Chromium engine or third-party native binaries.
        // Any future app-native code is restricted to the 64-bit ARM target.
        ndk { abiFilters += listOf("arm64-v8a") }
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildTypes {
        debug { }
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    testOptions { unitTests.isReturnDefaultValues = true }
}

dependencies {
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.mediarouter:mediarouter:1.7.0")
    implementation("androidx.core:core:1.16.0")
    implementation("androidx.browser:browser:1.8.0")
    implementation("androidx.webkit:webkit:1.18.0")
    testImplementation("junit:junit:4.13.2")
}
