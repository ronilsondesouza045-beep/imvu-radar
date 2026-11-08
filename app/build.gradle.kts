plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "br.com.imvuradar.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "br.com.imvuradar.app"
        minSdk = 24
        targetSdk = 35
        versionCode = 33
        versionName = "1.0.33"
        resourceConfigurations += listOf("pt", "en")
    }

    signingConfigs {
        create("radar") {
            storeFile = file("../keystore/radar.jks")
            storePassword = "radar2026"
            keyAlias = "radar"
            keyPassword = "radar2026"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            isShrinkResources = false
            signingConfig = signingConfigs.getByName("radar")
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
        debug {
            signingConfig = signingConfigs.getByName("radar")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    packaging {
        resources.excludes += setOf("META-INF/*.kotlin_module")
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.webkit:webkit:1.11.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
}
