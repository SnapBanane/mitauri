// Posts made-up notifications so the island can be tested on a real phone without real calls. Never shipped.
plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "de.mitauri.islandtest"
    compileSdk = 35
    defaultConfig {
        applicationId = "de.mitauri.islandtest"
        minSdk = 30
        targetSdk = 32
        versionCode = 1
        versionName = "1"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}
