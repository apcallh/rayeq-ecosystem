import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
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
val authEnabled = propOrEnv("RAYEQ_AUTH_ENABLED", "true").toBoolean()
val googleWebClientId = propOrEnv("RAYEQ_GOOGLE_WEB_CLIENT_ID", "<GOOGLE_WEB_CLIENT_ID>")
val versionNameArg = propOrEnv("RAYEQ_VERSION_NAME", "1.0.0")
val versionCodeArg = propOrEnv("RAYEQ_VERSION_CODE", "1").toIntOrNull() ?: 1

android {
    namespace = "app.rayeq"
    compileSdk = 36

    defaultConfig {
        applicationId = "app.rayeq.watch"
        minSdk = 26
        targetSdk = 36
        versionCode = versionCodeArg
        versionName = versionNameArg

        buildConfigField("String", "SERVER_URL", "\"$serverUrl\"")
        buildConfigField("boolean", "AUTH_ENABLED", authEnabled.toString())
        buildConfigField("String", "GOOGLE_WEB_CLIENT_ID", "\"$googleWebClientId\"")

        ndk {
            abiFilters.addAll(listOf("arm64-v8a", "armeabi-v7a"))
        }
    }

    signingConfigs {
        create("release") {
            val ksPath = propOrEnv("KEYSTORE_PATH", "")
            if (ksPath.isNotBlank()) {
                storeFile = file(ksPath)
                storePassword = propOrEnv("RAYEQ_STORE_PASSWORD", "")
                keyAlias = propOrEnv("RAYEQ_KEY_ALIAS", "rayeq")
                keyPassword = propOrEnv("RAYEQ_KEY_PASSWORD", "")
            }
        }
    }

    buildTypes {
        release {
            // القاعدة الذهبية رقم 6: R8/Minify معطّل دائماً حتى لا تنهار إضافات .cs3 وقت التشغيل
            isMinifyEnabled = false
            isShrinkResources = false
            val ks = signingConfigs.getByName("release")
            if (ks.storeFile != null) {
                signingConfig = ks
            }
        }
    }

    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        isCoreLibraryDesugaringEnabled = true
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {
    implementation(project(":library"))
    coreLibraryDesugaring(libs.desugar.jdk.libs)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.icons)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.lifecycle.runtime)
    implementation(libs.androidx.lifecycle.viewmodel)

    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.datetime)

    implementation(libs.media3.exoplayer)
    implementation(libs.media3.hls)
    implementation(libs.media3.dash)
    implementation(libs.media3.okhttp)
    implementation(libs.media3.ui)
    implementation(libs.media3.session)
    implementation(libs.nextlib.media3ext)

    implementation(libs.coil.compose)
    implementation(libs.coil.network.okhttp)

    implementation(libs.androidx.credentials)
    implementation(libs.androidx.credentials.auth)
    implementation(libs.googleid)
    implementation(libs.androidx.security.crypto)
    implementation(libs.androidx.work.runtime)

    implementation(libs.fuzzywuzzy)
    implementation(libs.okhttp)
    implementation(libs.jsoup)
    implementation(libs.ksoup)
    implementation(libs.rhino)
    implementation(libs.jackson.kotlin)
    implementation(libs.nicehttp)
    implementation(libs.conscrypt)
    implementation(libs.newpipe)
    implementation(libs.tmdb.java)
    implementation(libs.ktor.http)
    implementation(libs.atomicfu)
    implementation(libs.zipline)

    testImplementation(libs.junit)
}
