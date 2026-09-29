import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

/**
 * App configuration. Every value is optional; read from (first match wins):
 *   1. -P gradle property      e.g. ./gradlew assembleDebug -Psoslive.streamRtmpUrl=rtmp://192.168.1.10:1935/live
 *   2. local.properties        e.g. soslive.streamRtmpUrl=rtmp://192.168.1.10:1935/live
 * There is no SOSlive backend: data goes to the user's Google Drive, video to the stream server.
 */
val localProperties = Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}

fun appConfig(key: String, default: String = ""): String =
    (providers.gradleProperty(key).orNull ?: localProperties.getProperty(key) ?: default).trim()

fun String.asBuildConfigString() = "\"" + replace("\\", "\\\\").replace("\"", "\\\"") + "\""

// Web app that shows events: links are <webappUrl>/e/{fileId}
val webappUrl = appConfig("soslive.webappUrl", "https://soslive.example").trimEnd('/')
// RTMP ingest (stream key is appended) and HLS playback template ({key} is replaced)
val streamRtmpUrl = appConfig("soslive.streamRtmpUrl", "rtmp://10.0.2.2:1935/live").trimEnd('/')
val streamHlsTemplate = appConfig("soslive.streamHlsTemplate", "http://10.0.2.2:8888/live/{key}/index.m3u8")
// Google Cloud *Web* OAuth client id (same project as the web app). Empty = simulated Drive.
val googleWebClientId = appConfig("soslive.googleWebClientId")

android {
    namespace = "info.soslive.stream"
    compileSdk = 35

    defaultConfig {
        applicationId = "info.soslive.stream"
        minSdk = 26
        targetSdk = 35
        versionCode = 2
        versionName = "2.0.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        buildConfigField("String", "WEBAPP_URL", webappUrl.asBuildConfigString())
        buildConfigField("String", "STREAM_RTMP_URL", streamRtmpUrl.asBuildConfigString())
        buildConfigField("String", "STREAM_HLS_TEMPLATE", streamHlsTemplate.asBuildConfigString())
        buildConfigField("String", "GOOGLE_WEB_CLIENT_ID", googleWebClientId.asBuildConfigString())
    }

    buildTypes {
        debug {
            // No applicationIdSuffix: the Android OAuth client is bound to the package name.
            versionNameSuffix = "-debug"
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // No release keystore in the repo: sign with the debug key so `assembleRelease` works locally.
            // Replace with a real signingConfig before publishing.
            signingConfig = signingConfigs.getByName("debug")
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
    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
    lint {
        abortOnError = true
        warningsAsErrors = false
        disable += listOf("GradleDependency", "NewerVersionAvailable", "AndroidGradlePluginVersion")
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.datastore.preferences)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.extended)
    debugImplementation(libs.compose.ui.tooling)

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.hilt.navigation.compose)

    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.coroutines.play.services)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.okhttp)
    implementation(libs.okhttp.logging)

    implementation(libs.play.services.location)
    implementation(libs.androidx.credentials)
    implementation(libs.androidx.credentials.play.services)
    implementation(libs.googleid)
    implementation(libs.play.services.auth)

    implementation(libs.rootencoder.library)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.okhttp.mockwebserver)
}
