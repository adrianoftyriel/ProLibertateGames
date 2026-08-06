plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.serialization")
}

// Which update channel this build belongs to, set by whichever workflow built
// it: release.yml stamps "production" from main, ci.yml stamps "dev" from the
// dev branch. Unset for a local build, which counts as dev.
//
// GITHUB_RUN_NUMBER alone is not enough to key off, because it counts per
// workflow — the two pipelines have entirely independent sequences. That is
// also why versionCode is only ever compared *within* a channel; see
// UpdateChannel for how switching between them is handled.
val buildChannel = System.getenv("PLG_CHANNEL").orEmpty()
val runNumber = (System.getenv("GITHUB_RUN_NUMBER") ?: "").toIntOrNull() ?: 0
val stampVersion = buildChannel.isNotEmpty() && runNumber > 0

android {
    namespace = "org.prolibertate.games"
    compileSdk = 34

    defaultConfig {
        applicationId = "org.prolibertate.games"
        minSdk = 24
        targetSdk = 34
        // The -dev suffix in versionName is what the installed app reads to
        // know its own channel, so it must survive into the shipped APK.
        versionCode = if (stampVersion) runNumber else 1
        versionName = when {
            stampVersion && buildChannel == "production" -> "1.0.$runNumber"
            stampVersion -> "1.0.$runNumber-dev"
            else -> "1.0.0-dev"
        }
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
