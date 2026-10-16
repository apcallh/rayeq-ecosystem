plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.lagradost.api"
    compileSdk = 37

    defaultConfig {
        minSdk = 26
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        isCoreLibraryDesugaringEnabled = true
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    coreLibraryDesugaring(libs.desugar.jdk.libs)
    api(libs.kotlinx.coroutines.android)
    api(libs.okhttp)
    api(libs.jsoup)
    api(libs.ksoup)
    api(libs.rhino)
    api(libs.jackson.kotlin)
    api(libs.nicehttp)
}
