plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "dev.ferry.android"
    compileSdk = 37
    buildToolsVersion = "37.0.0"
    // Matches scripts/build-android.sh; AGP uses its llvm-strip on libferry_ffi.so.
    ndkVersion = "30.0.16248370"

    defaultConfig {
        // Same id in both flavors and no debug suffix: the Mac opens deep links on this id.
        applicationId = "dev.ferry.android"
        minSdk = 29
        // shortcut: targetSdk 37 needs ACCESS_LOCAL_NETWORK onboarding for LAN traffic; do it in a later phase.
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
        // Only ABIs we built libferry_ffi.so for (JNA ships more); x86_64 = `build-android.sh --x86_64`.
        ndk {
            abiFilters += "arm64-v8a"
            if (file("src/main/jniLibs/x86_64").exists()) abiFilters += "x86_64"
        }
    }

    flavorDimensions += "dist"
    productFlavors {
        // `full` adds MANAGE_EXTERNAL_STORAGE (src/full/AndroidManifest.xml) for the Files grant; `play` can't browse.
        create("play") { dimension = "dist" }
        create("full") { dimension = "dist" }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    buildFeatures { compose = true }
}

kotlin { jvmToolchain(21) }

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2026.09.00")
    implementation(composeBom)
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui")
    debugImplementation("androidx.compose.ui:ui-tooling")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.11.0")
    implementation("androidx.core:core-ktx:1.19.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")
    // UniFFI's Kotlin bindings call into libferry_ffi.so through JNA.
    implementation("net.java.dev.jna:jna:5.19.1@aar")

    val camerax = "1.6.2"
    implementation("androidx.camera:camera-camera2:$camerax")
    implementation("androidx.camera:camera-lifecycle:$camerax")
    implementation("androidx.camera:camera-view:$camerax")
    // QR decoding without Play services, so the full build stays Google-free.
    implementation("com.google.zxing:core:3.5.4")

    testImplementation("junit:junit:4.13.2")
}
