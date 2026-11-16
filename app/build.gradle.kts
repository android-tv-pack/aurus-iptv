plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val plus = (findProperty("plus") as String?) == "true" && file("../../aurus-plus/src").exists()

android {
    namespace = "app.hermes.channels"
    compileSdk = 36

    defaultConfig {
        applicationId = "app.hermes.channels"
        minSdk = 24
        targetSdk = 34
        versionCode = 33
        versionName = "0.33-beta"
        if (plus) ndk { abiFilters += listOf("armeabi-v7a", "arm64-v8a") }
    }
    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("debug")
        }
    }
    buildFeatures { buildConfig = true }
    // Aurus Plus, the extras that are not part of the open app, are kept in a folder of their own beside this one.
    // Built with -Pplus=true and that folder present, their file stands in place of the empty one in src/free.
    sourceSets["main"].java.srcDir(if (plus) "../../aurus-plus/src" else "src/free/java")
    dependenciesInfo { includeInApk = false; includeInBundle = false }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    // (Aurus Plus only) the second engine, for channels the app's own does not play
    if (plus) implementation("org.videolan.android:libvlc-all:3.7.7")
    implementation("androidx.media3:media3-exoplayer:1.11.0-beta01")
    implementation("androidx.media3:media3-exoplayer-hls:1.11.0-beta01")
    // channels that are sent as DASH (.mpd), Smooth Streaming or RTSP: without these the player has no reader for them
    implementation("androidx.media3:media3-exoplayer-dash:1.11.0-beta01")
    implementation("androidx.media3:media3-exoplayer-smoothstreaming:1.11.0-beta01")
    implementation("androidx.media3:media3-exoplayer-rtsp:1.11.0-beta01")
    testImplementation("junit:junit:4.13.2")
}
