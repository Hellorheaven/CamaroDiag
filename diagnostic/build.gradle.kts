plugins { id("com.android.library"); id("org.jetbrains.kotlin.android") }
android { namespace = "com.camarodiag.diagnostic"; compileSdk = 36; defaultConfig { minSdk = 29 } }
dependencies { api(project(":core")) }
