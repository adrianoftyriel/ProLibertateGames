plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.serialization")
}

// Only the release workflow stamps a real version. It sets PLG_RELEASE, and its
// GITHUB_RUN_NUMBER is the same number as the release tag it publishes
// (v1.0.<run-number>), so the shipped APK knows its own version and the in-app
// updater can compare against the latest published release.
//
// Everything else — a local build, or the dev CI pipeline — stays at 1.0.0-dev.
// GITHUB_RUN_NUMBER alone is NOT enough to key off: it counts per workflow, so
// the dev pipeline has its own independent sequence. A debug APK installed from
// a CI artifact would otherwise carry that counter as its versionCode, could
// outrank a genuine release, and would leave the updater reporting "you're on
// the latest version" indefinitely.
val isReleaseBuild = System.getenv("PLG_RELEASE") == "1"
val releaseRunNumber = (System.getenv("GITHUB_RUN_NUMBER") ?: "").toIntOrNull() ?: 0
val stampReleaseVersion = isReleaseBuild && releaseRunNumber > 0

android {
    namespace = "org.prolibertate.games"
    compileSdk = 34

    defaultConfig {
        applicationId = "org.prolibertate.games"
        minSdk = 24
        targetSdk = 34
        // Dev builds sit at 1, below every release except the very first —
        // a v1.0.1 release ties with a dev build and so is not offered as an
        // update. Harmless, and preferable to versionCode 0, which Android
        // documents as out of range.
        versionCode = if (stampReleaseVersion) releaseRunNumber else 1
        versionName = if (stampReleaseVersion) "1.0.$releaseRunNumber" else "1.0.0-dev"
    }

    signingConfigs {
        // A fixed, checked-in debug keystore (standard "android" password) so
        // every build — local or CI — is signed with the same key. Without this,
        // each CI runner would generate a random debug key and OTA updates would
        // fail to install with a signature mismatch.
        getByName("debug") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    buildFeatures {
        compose = true
    }

    composeOptions {
        // Must stay in step with the Kotlin version declared in the root build file.
        kotlinCompilerExtensionVersion = "1.5.14"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.2")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.2")

    val composeBom = platform("androidx.compose:compose-bom:2024.06.00")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    debugImplementation("androidx.compose.ui:ui-tooling")

    // Settings persistence for sound / animation speed / update preferences.
    implementation("androidx.datastore:datastore-preferences:1.1.1")

    // Wire format for the LAN and Bluetooth game protocol.
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    testImplementation("junit:junit:4.13.2")
}
