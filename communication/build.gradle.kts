plugins { id("com.android.library"); id("org.jetbrains.kotlin.android") }
android {
    namespace = "com.camarodiag.communication"
    compileSdk = 36
    // Compile portable Connectors USB clients (Kotlin and transitional Java) from one source tree.
    sourceSets.getByName("main").java.srcDir(rootProject.file("connectors/host/android/src/main/java"))

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
}
dependencies {
    api(project(":core"))
    implementation(project(":diagnostic"))
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("com.github.mik3y:usb-serial-for-android:3.8.1")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
}
