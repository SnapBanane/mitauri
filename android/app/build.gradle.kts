import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("app.cash.paparazzi")
}

android {
    namespace = "de.mitauri.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "de.mitauri.app"
        minSdk = 30
        targetSdk = 35
        versionCode = 18
        versionName = "0.19"
    }

    // The key stays outside the repo. See scripts/make-signing-key.sh.
    val signing = file("${System.getProperty("user.home")}/.local/share/mitauri/signing/signing.properties")
    if (signing.exists()) {
        val properties = Properties().apply { signing.inputStream().use { load(it) } }
        signingConfigs {
            create("release") {
                storeFile = file(properties.getProperty("storeFile"))
                storePassword = properties.getProperty("storePassword")
                keyAlias = properties.getProperty("keyAlias")
                keyPassword = properties.getProperty("keyPassword")
            }
        }
        buildTypes {
            getByName("release") { signingConfig = signingConfigs.getByName("release") }
        }
    }

    buildFeatures {
        compose = true
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
    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.biometric:biometric:1.1.0")
    implementation("androidx.fragment:fragment-ktx:1.8.5")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.savedstate:savedstate-ktx:1.2.1")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    testImplementation("junit:junit:4.13.2")
}
