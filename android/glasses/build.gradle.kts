plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.pri4l.glasses"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.pri4l.glasses"
        // INMO Air3 runs Android 14. Targeting 34 lets us consume air3_core.aar
        // (which declares minSdk 34) directly, with no tools:overrideLibrary hack.
        minSdk = 34
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0"

        // INMO is arm64 only — restrict native libs (air3_core, OpenCV) to one ABI so the
        // OpenCV native blob doesn't bloat the APK across ABIs we'll never run on.
        ndk { abiFilters += "arm64-v8a" }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }

    kotlinOptions {
        jvmTarget = "1.8"
    }
}

dependencies {
    // INMO Air3 native fusion (decision 011). Local .aar dropped into glasses/libs/.
    // Provides com.inmo.air3_core.* + libinmoair3.so. See glasses/libs/README.md.
    implementation(fileTree(mapOf("dir" to "libs", "include" to listOf("*.aar"))))

    // CameraX — world-camera streaming to the hub
    val cameraVersion = "1.3.1"
    implementation("androidx.camera:camera-core:$cameraVersion")
    implementation("androidx.camera:camera-camera2:$cameraVersion")
    implementation("androidx.camera:camera-lifecycle:$cameraVersion")

    // WebSocket to the hub (rosbridge)
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // OpenCV (objdetect includes ArucoDetector) for on-device fiducial alignment (decision 010).
    // Official Maven AAR with bundled Android native libs since 4.9.0.
    implementation("org.opencv:opencv:4.11.0")

    // Core — ComponentActivity is our LifecycleOwner for CameraX (no Compose)
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.activity:activity-ktx:1.8.2")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")
}
