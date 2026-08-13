plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("com.google.devtools.ksp")
}

// versionCode auto-increments with each CI build (GitHub run number); the
// in-app updater compares this to the latest published release.
val ciRun = (System.getenv("GITHUB_RUN_NUMBER") ?: "1").toInt()

// Release signing comes from CI secrets (never committed). When absent (local
// builds / PRs) the release APK is simply unsigned.
val keystoreFile: String? = System.getenv("KEYSTORE_FILE")

android {
    namespace = "com.restaurant.iptv"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.restaurant.iptv"
        minSdk = 24
        targetSdk = 34
        versionCode = ciRun
        versionName = "1.0.$ciRun"
    }

    signingConfigs {
        create("release") {
            // Populated from CI secrets; same key every build so updates install
            // over each other. Nothing sensitive lives in the repo.
            if (keystoreFile != null) {
                storeFile = file(keystoreFile)
                storePassword = System.getenv("KEYSTORE_PASSWORD")
                keyAlias = System.getenv("KEY_ALIAS")
                keyPassword = System.getenv("KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            signingConfig = if (keystoreFile != null) signingConfigs.getByName("release") else null
            // Debuggable release for easy sideloading onto TVs during bring-up.
            // Flip isMinifyEnabled to true once you are ready to ship and have
            // validated the ProGuard rules below.
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
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
        viewBinding = true
        buildConfig = true
    }

    packaging {
        resources.excludes += setOf(
            "META-INF/INDEX.LIST",
            "META-INF/io.netty.versions.properties",
            "META-INF/DEPENDENCIES",
            "META-INF/*.kotlin_module"
        )
    }
}

dependencies {
    val media3 = "1.4.1"
    val ktor = "2.3.12"
    val room = "2.6.1"

    // --- Playback (Apache-2.0, clean license) ---
    implementation("androidx.media3:media3-exoplayer:$media3")
    implementation("androidx.media3:media3-exoplayer-hls:$media3")
    implementation("androidx.media3:media3-datasource:$media3")
    implementation("androidx.media3:media3-ui:$media3")
    implementation("androidx.media3:media3-session:$media3")
    implementation("androidx.media3:media3-common:$media3")

    // --- Embedded web-control server + HTTP client (Apache-2.0) ---
    implementation("io.ktor:ktor-server-core:$ktor")
    implementation("io.ktor:ktor-server-cio:$ktor")
    implementation("io.ktor:ktor-server-status-pages:$ktor")
    implementation("io.ktor:ktor-server-cors:$ktor")
    implementation("io.ktor:ktor-server-content-negotiation:$ktor")
    implementation("io.ktor:ktor-serialization-kotlinx-json:$ktor")
    implementation("io.ktor:ktor-client-core:$ktor")
    implementation("io.ktor:ktor-client-cio:$ktor")

    // --- Persistence (Apache-2.0) ---
    implementation("androidx.room:room-runtime:$room")
    implementation("androidx.room:room-ktx:$room")
    ksp("androidx.room:room-compiler:$room")
    implementation("androidx.datastore:datastore-preferences:1.1.1")

    // --- Kotlin / AndroidX ---
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.activity:activity-ktx:1.9.2")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.lifecycle:lifecycle-service:2.8.6")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.6")
}
