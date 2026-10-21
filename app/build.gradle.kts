plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.fergusonconsultancy.foldtaskbar"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.fergusonconsultancy.foldtaskbar"
        minSdk = 30
        targetSdk = 35
        // Each GitHub build gets a higher number so it installs as an update
        versionCode = (System.getenv("GITHUB_RUN_NUMBER") ?: "1").toInt()
        versionName = "1.0." + (System.getenv("GITHUB_RUN_NUMBER") ?: "0")
    }

    // One fixed key for every build, so new APKs install over the old one.
    // Debug-only key for personal sideloading. Never use it for a store release.
    signingConfigs {
        getByName("debug") {
            storeFile = rootProject.file("signing/foldtaskbar-debug.jks")
            storePassword = "foldtaskbar"
            keyAlias = "foldtaskbar"
            keyPassword = "foldtaskbar"
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("debug")
        }
        release {
            isMinifyEnabled = false
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

dependencies {
    // Reaches the hidden "launch as a pop-up window" option for the experimental pop-up action
    implementation("org.lsposed.hiddenapibypass:hiddenapibypass:4.3")
}
