plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.yangkim.logcamera"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.yangkim.logcamera"
        minSdk = 31
        targetSdk = 35
        versionCode = 16
        versionName = "1.6"
    }

    // 빌드마다 같은 키로 서명해야 폰에서 덮어쓰기 업데이트가 됩니다.
    signingConfigs {
        create("shared") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        getByName("debug") {
            signingConfig = signingConfigs.getByName("shared")
        }
        getByName("release") {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("shared")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}
