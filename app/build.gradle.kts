import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android") 
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
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


android {
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
            outputFileName = apkName
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
}

dependencies {
    implementation(project(":core"))
    implementation(project(":diagnostic"))
    implementation(project(":communication"))
    implementation(project(":storage"))
    implementation(project(":control"))
    implementation(project(":mileageaudit"))
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.4")
    implementation("androidx.activity:activity-compose:1.9.1")
    implementation("androidx.activity:activity-ktx:1.9.1")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.8.4")
    
    // <-- AJOUTEZ CETTE LIGNE POUR RÉSOUDRE LE THÈME MATERIAL 3 -->
    implementation("com.google.android.material:material:1.12.0")

    implementation(platform("androidx.compose:compose-bom:2024.06.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")


    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.6.1")
    androidTestImplementation(platform("androidx.compose:compose-bom:2024.06.00"))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
