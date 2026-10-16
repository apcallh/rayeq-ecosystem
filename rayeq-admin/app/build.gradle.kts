import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

val localProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

fun propOrEnv(key: String, defaultVal: String = ""): String =
    (project.findProperty(key) as String?)
        ?: localProps.getProperty(key)
        ?: System.getenv(key)
        ?: defaultVal

val serverUrl = propOrEnv("RAYEQ_SERVER_URL", "<YOUR_WORKER_URL>")
val versionNameArg = propOrEnv("RAYEQ_VERSION_NAME", "1.0.0")
val versionCodeArg = propOrEnv("RAYEQ_VERSION_CODE", "1").toIntOrNull() ?: 1

android {
    namespace = "app.rayeq.admin"
    compileSdk = 36

    defaultConfig {
        applicationId = "app.rayeq.admin"
        minSdk = 26
        targetSdk = 36
        versionCode = versionCodeArg
        versionName = versionNameArg
        buildConfigField("String", "SERVER_URL", "\"$serverUrl\"")
    }

    signingConfigs {
        create("release") {
            val ksPath = propOrEnv("RAYEQ_ADMIN_KEYSTORE_PATH", propOrEnv("KEYSTORE_PATH", ""))
            if (ksPath.isNotBlank()) {
                storeFile = file(ksPath)
                storePassword = propOrEnv("RAYEQ_STORE_PASSWORD", "")
                keyAlias = propOrEnv("RAYEQ_KEY_ALIAS", "rayeq-admin")
                keyPassword = propOrEnv("RAYEQ_KEY_PASSWORD", "")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            isShrinkResources = false
            val ks = signingConfigs.getByName("release")
            if (ks.storeFile != null) {
                signingConfig = ks
            }
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
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.icons)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.coil.compose)
    implementation(libs.coil.network.okhttp)
}
