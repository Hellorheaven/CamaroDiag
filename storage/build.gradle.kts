
plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    id("com.google.devtools.ksp")
}

android {
    namespace = "com.camarodiag.storage"
    compileSdk = 36

    defaultConfig {
        minSdk = 29
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    lint {
        // Room stays pinned through the v1-to-v2 migration validation; upgrade
        // separately after the migration tests pass on Android 10/13/16.
        disable += "GradleDependency"
    }
}

dependencies {
    api(project(":core"))

    // Exposé aux modules qui utilisent DiagnosticDatabase
    api("androidx.room:room-runtime:2.6.1")

    implementation("androidx.room:room-ktx:2.6.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    ksp("androidx.room:room-compiler:2.6.1")
}
