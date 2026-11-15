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
        versionCode = 3
        versionName = "1.0.2"
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

    debugImplementation("org.mozilla.geckoview:geckoview-x86_64:160.0.20261104181552")
    releaseImplementation("org.mozilla.geckoview:geckoview-arm64-v8a:160.0.20261104181552")

    testImplementation("junit:junit:4.13.2")
}
