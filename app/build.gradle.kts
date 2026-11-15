plugins {
    id("com.android.application")
    id("com.cefrium") version "0.20.0"
}

android {
    buildFeatures { buildConfig = true }
    namespace = "com.search.browser"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.search.browser"
        minSdk = 29
        targetSdk = 36
        versionCode = 7
        versionName = "1.0.6"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    flavorDimensions += "target"
    productFlavors {
        create("emulator") {
            dimension = "target"
            ndk { abiFilters += listOf("x86_64") }
        }
        create("phone") {
            dimension = "target"
            ndk { abiFilters += listOf("arm64-v8a") }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging { jniLibs { useLegacyPackaging = true } }

    signingConfigs {
        create("release") {
            storeFile = file("release-key.jks")
            storePassword = System.getenv("ANDROID_KEYSTORE_PASSWORD")
            keyAlias = System.getenv("ANDROID_KEY_ALIAS")
            keyPassword = System.getenv("ANDROID_KEY_PASSWORD")
        }
    }

    buildTypes {
        debug { }
        release {
            signingConfig = signingConfigs.getByName("release")
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    testOptions { unitTests.isReturnDefaultValues = true }
}

dependencies {
    implementation("com.cefrium:cefrium-sdk:0.20.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.mediarouter:mediarouter:1.7.0")
    implementation("androidx.core:core:1.16.0")
    implementation("androidx.swiperefreshlayout:swiperefreshlayout:1.1.0")
    implementation("androidx.browser:browser:1.8.0")
    testImplementation("junit:junit:4.13.2")
}
