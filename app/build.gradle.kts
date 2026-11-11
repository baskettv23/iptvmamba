import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

// Optional developer API keys from "secrets.properties" (ignored by Git) or environment variables.
// Normal users enter their own keys in the app (Settings > API keys); without keys the build contains none.
val secrets = Properties().apply {
    val f = rootProject.file("secrets.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
fun secret(name: String): String =
    (secrets.getProperty(name) ?: System.getenv(name) ?: "").trim().filter { it.isLetterOrDigit() }
// Google sign-in for the optional Google Drive backup (see docs/GOOGLE_DRIVE.md); client IDs contain '-' and '.'
fun secretId(name: String): String =
    (secrets.getProperty(name) ?: System.getenv(name) ?: "").trim().filter { it.isLetterOrDigit() || it == '-' || it == '.' || it == '_' }

// Release signing from "keystore.properties" (ignored by Git, see docs/RELEASE.md). Every release MUST be signed with the
// same key: only then does Android install an update over the old version and keep history, favorites and settings.
val keystore = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
val hasKeystore = listOf("storeFile", "storePassword", "keyAlias", "keyPassword").all { !keystore.getProperty(it).isNullOrBlank() }

android {
    namespace = "com.iptvmamba.player"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.iptvmamba.player"
        minSdk = 24
        targetSdk = 37
        // Raise both for every release; the GitHub release tag must match versionName (e.g. tag "v1.1").
        versionCode = 3
        versionName = "1.2"
        // Name unter dem Symbol; der Debug-Build überschreibt ihn (siehe buildTypes)
        manifestPlaceholders["appLabel"] = "iptvmamba"

        buildConfigField("String", "TMDB_KEY", "\"${secret("TMDB_KEY")}\"")
        buildConfigField("String", "OMDB_KEY", "\"${secret("OMDB_KEY")}\"")
        buildConfigField("String", "GOOGLE_CLIENT_ID", "\"${secretId("GOOGLE_CLIENT_ID")}\"")
        buildConfigField("String", "GOOGLE_CLIENT_SECRET", "\"${secretId("GOOGLE_CLIENT_SECRET")}\"")
    }

    signingConfigs {
        if (hasKeystore) create("release") {
            storeFile = rootProject.file(keystore.getProperty("storeFile"))
            storePassword = keystore.getProperty("storePassword")
            keyAlias = keystore.getProperty("keyAlias")
            keyPassword = keystore.getProperty("keyPassword")
            // All signature schemes for maximum compatibility with TV boxes / older installers.
            enableV1Signing = true
            enableV2Signing = true
            enableV3Signing = true
        }
    }

    buildTypes {
        // Test-Build (Run in Android Studio / Debug-APK): eigene App-ID, damit die private iptvmamba-App auf dem Fernseher
        // nicht überschrieben wird. Beide Apps lassen sich parallel installieren (eigene Daten, eigener Name).
        debug {
            applicationIdSuffix = ".test"
            manifestPlaceholders["appLabel"] = "iptvmamba"
        }
        release {
            if (hasKeystore) signingConfig = signingConfigs.getByName("release")
            optimization {
                // Off: the shrunk release build (12.7 MB) did not start on the device. Turn back on only together with
                // keep rules in src/main/keepRules and a test on a real device.
                enable = false
                packageScope = setOf("androidx.**", "kotlin.**", "kotlinx.**")
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }

    lint {
        disable += "UnsafeOptInUsageError"
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2026.09.00"))
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")

    implementation("androidx.media3:media3-exoplayer:1.11.1")
    implementation("androidx.media3:media3-exoplayer-hls:1.11.1")
    // MPEG-DASH (.mpd) streams; without it Media3 throws IllegalStateException when such a stream is opened
    implementation("androidx.media3:media3-exoplayer-dash:1.11.1")
    implementation("androidx.media3:media3-ui:1.11.1")
    // Software audio decoder (AC3/E-AC3/DTS ...) for headphones/Bluetooth, see SoftAudio.kt. GPL-3.0 – the reason the app is GPL-3.0.
    implementation("org.jellyfin.media3:media3-ffmpeg-decoder:1.9.0+1")

    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("io.coil-kt:coil-compose:2.7.0")

    implementation("androidx.work:work-runtime-ktx:2.10.0")

    testImplementation("junit:junit:4.13.2")
}
