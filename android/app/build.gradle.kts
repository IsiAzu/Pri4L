plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.pri4l.hub"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.pri4l.hub"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0"
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

    buildFeatures {
        compose = true
    }

    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.8"
    }
}

dependencies {
    // INMO Air3 native fusion (decision 011). Local .aar dropped into app/libs/.
    // Absent by default — the app builds and falls back to Game Rotation Vector until
    // the .aar is present. See app/libs/README.md. fileTree is used so this bypasses
    // the FAIL_ON_PROJECT_REPOS restriction in settings.gradle.
    implementation(fileTree(mapOf("dir" to "libs", "include" to listOf("*.aar"))))

    // Compose
    val composeBom = platform("androidx.compose:compose-bom:2024.01.00")
    implementation(composeBom)
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.activity:activity-compose:1.8.2")
    debugImplementation("androidx.compose.ui:ui-tooling")

    // CameraX
    val cameraVersion = "1.3.1"
    implementation("androidx.camera:camera-core:$cameraVersion")
    implementation("androidx.camera:camera-camera2:$cameraVersion")
    implementation("androidx.camera:camera-lifecycle:$cameraVersion")

    // WebSocket
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // ARCore
    implementation("com.google.ar:core:1.41.0")

    // Core
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")
}
