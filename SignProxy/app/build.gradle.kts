plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "com.android.system.helper"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.android.system.helper"
        minSdk = 24
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
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

dependencies {
    compileOnly("de.robv.android.xposed:api:82")
}
