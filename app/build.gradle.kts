plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "app.hermes.channels"
    compileSdk = 36

    defaultConfig {
        applicationId = "app.hermes.channels"
        minSdk = 24
        targetSdk = 34
        versionCode = 25
        versionName = "0.25-beta"
    }
    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("debug")
        }
    }
    buildFeatures { buildConfig = true }
    dependenciesInfo { includeInApk = false; includeInBundle = false }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation("androidx.media3:media3-exoplayer:1.11.0-beta01")
    implementation("androidx.media3:media3-exoplayer-hls:1.11.0-beta01")
    // channels that are sent as DASH (.mpd), Smooth Streaming or RTSP: without these the player has no reader for them
    implementation("androidx.media3:media3-exoplayer-dash:1.11.0-beta01")
    implementation("androidx.media3:media3-exoplayer-smoothstreaming:1.11.0-beta01")
    implementation("androidx.media3:media3-exoplayer-rtsp:1.11.0-beta01")
    testImplementation("junit:junit:4.13.2")
}
