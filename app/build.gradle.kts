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

// The version series, from gradle.properties. Both workflows read the same
// property when they name a release tag, so the tag and the APK inside it
// cannot disagree about which series they belong to.
val versionSeries = (project.findProperty("plgVersionSeries") as String?) ?: "1.0"

// Anything that is not an explicit production build is a dev build: ci.yml
// stamps "dev", and a local build with nothing set counts as dev too.
//
// This one flag drives everything that makes a dev build a separate app rather
// than a replacement for the production one — its own applicationId, its own
// launcher name, and the hunting sett in place of the clan sett. They have to
// move together: a dev package wearing the production name and colours is
// exactly the confusion having both installed is meant to avoid.
val isDevBuild = buildChannel != "production"

android {
    namespace = "org.prolibertate.games"
    compileSdk = 34

    defaultConfig {
        // A dev build installs under its own package, so a dev copy and a
        // production copy can sit side by side on one device instead of one
        // replacing the other. The namespace above is deliberately left alone:
        // R and BuildConfig stay in org.prolibertate.games for both, so no
        // source has to care which package it ended up installed as.
        applicationId = if (isDevBuild) "org.prolibertate.games.dev" else "org.prolibertate.games"
        minSdk = 24
        targetSdk = 34
        // The -dev suffix in versionName is what the installed app reads to
        // know its own channel, so it must survive into the shipped APK. It
        // tracks isDevBuild rather than the stamp alone, so an unstamped local
        // build cannot end up claiming a channel its applicationId contradicts.
        // versionCode stays the run number and nothing else, so it keeps
        // climbing across a change of series: bumping 1.0 to 1.1 must not make
        // an update look like a downgrade to Android.
        versionCode = if (stampVersion) runNumber else 1
        versionName = when {
            stampVersion && buildChannel == "production" -> "$versionSeries.$runNumber"
            stampVersion -> "$versionSeries.$runNumber-dev"
            isDevBuild -> "$versionSeries.0-dev"
            else -> "$versionSeries.0"
        }

        // The launcher label. With both copies on the phone the app drawer shows
        // two entries, and this is what names them apart.
        resValue(
            "string",
            "app_name",
            if (isDevBuild) "Pro Libertate Games DEV" else "Pro Libertate Games",
        )

        // The one colour that separates the two setts: Wallace red for
        // production, Wallace Hunting green for dev. The icon vectors reference
        // this rather than a literal, and Tartan.kt picks the matching Compose
        // colour off DEV_BUILD below — the two have to stay in step or the
        // launcher icon and the splash it opens on would be different cloth.
        resValue("color", "tartan_field", if (isDevBuild) "#1E7A3C" else "#E01B24")
        // Shades of the same field, so a launch does not flash the wrong colour
        // before the tartan is drawn: splash_background is held behind the
        // window for that instant, ic_launcher_background is the icon's flat
        // fallback.
        resValue("color", "splash_background", if (isDevBuild) "#0D2E17" else "#4A0B10")
        resValue("color", "ic_launcher_background", if (isDevBuild) "#145C2D" else "#B3121B")

        buildConfigField("boolean", "DEV_BUILD", isDevBuild.toString())
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
        // For DEV_BUILD, which is how the Compose tartan picks its sett.
        buildConfig = true
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

    // Wire format for the LAN game protocol.
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    testImplementation("junit:junit:4.13.2")
}
