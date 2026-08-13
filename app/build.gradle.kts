plugins {
    id("com.android.application")
}

android {
    namespace = "com.example.sonyliveview"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.example.sonyliveview"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}
