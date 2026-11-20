import java.util.Properties
import java.util.Base64
import com.android.build.gradle.internal.api.BaseVariantOutputImpl

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android") 
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
    id("com.google.gms.google-services")
}

val appVersionProperties = Properties().apply {
    rootProject.file("version.properties").inputStream().use { load(it) }
}
val appVersionPrefix = appVersionProperties.getProperty("VERSION_PREFIX")
    ?: error("VERSION_PREFIX is missing from version.properties")
val appVersionCodeBase = appVersionProperties.getProperty("VERSION_CODE_BASE")?.toIntOrNull()
    ?: error("VERSION_CODE_BASE is missing or invalid in version.properties")
val releasePatch = providers.gradleProperty("releasePatch").orElse("0").get().toIntOrNull()
    ?: error("releasePatch must be a non-negative integer")
require(releasePatch >= 0) { "releasePatch must be non-negative" }

// The generated artwork is stored as base64 so the repository remains text-only;
// Gradle materializes the original WebP into the Android resource tree at build time.
val artworkSource = layout.projectDirectory.file("src/main/artwork/camarodiag_car.webp.b64")
val generatedArtworkRes = layout.buildDirectory.dir("generated/res/camarodiagArtwork")
val decodeCamaroArtwork by tasks.registering {
    inputs.file(artworkSource)
    outputs.file(generatedArtworkRes.map { it.file("drawable-nodpi/camarodiag_car.webp") })
    doLast {
        val encoded = artworkSource.asFile.readText().trim()
        val output = generatedArtworkRes.get().file("drawable-nodpi/camarodiag_car.webp").asFile
        output.parentFile.mkdirs()
        output.writeBytes(Base64.getDecoder().decode(encoded))
    }
}


android {
    sourceSets.getByName("main").res.srcDir(generatedArtworkRes)

    namespace = "com.camarodiag.gen5"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.camarodiag.gen5"
        minSdk = 29
        targetSdk = 36
        versionCode = appVersionCodeBase + releasePatch
        versionName = "$appVersionPrefix.$releasePatch"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        create("release") {
            storeFile = file("release.jks")
            storePassword = System.getenv("SIGNING_STORE_PASSWORD")
            keyAlias = System.getenv("SIGNING_KEY_ALIAS")
            keyPassword = System.getenv("SIGNING_KEY_PASSWORD")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs.getByName("release")
        }
        debug {
            applicationIdSuffix = ".debug"
            isDebuggable = true
            if (file("release.jks").exists() && !System.getenv("SIGNING_STORE_PASSWORD").isNullOrBlank()) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    // Same product name as the Windows bridge installer; channels remain distinct.
    applicationVariants.all {
        val apkName = "CamaroDiag-${name}.apk"
        outputs.all {
            (this as BaseVariantOutputImpl).outputFileName = apkName
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    // The app has an in-app language selector and bundles all 14 translations locally.
    // Keep every locale in the generated App Bundle instead of splitting languages.
    bundle {
        language {
            enableSplit = false
        }
    }
}

tasks.named("preBuild").configure {
    dependsOn(decodeCamaroArtwork)
}

dependencies {
    implementation(project(":core"))
    implementation(project(":diagnostic"))
    implementation(project(":communication"))
    implementation(project(":storage"))
    implementation(project(":control"))
    implementation(project(":mileageaudit"))
    implementation("androidx.core:core-ktx:1.16.0")
    implementation(platform("com.google.firebase:firebase-bom:33.16.0"))
    implementation("com.google.firebase:firebase-auth")
    implementation("androidx.credentials:credentials:1.5.0")
    implementation("androidx.credentials:credentials-play-services-auth:1.5.0")
    implementation("com.google.android.libraries.identity.googleid:googleid:1.1.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.9.2")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.2")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.activity:activity-ktx:1.10.1")
    implementation("com.google.mlkit:text-recognition:16.0.1")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.9.2")
    
    // Material Components remains compatible with the project Android Gradle Plugin baseline.
    implementation("com.google.android.material:material:1.14.0")

    implementation(platform("androidx.compose:compose-bom:2025.05.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")


    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.7.0")
    androidTestImplementation(platform("androidx.compose:compose-bom:2025.05.01"))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
