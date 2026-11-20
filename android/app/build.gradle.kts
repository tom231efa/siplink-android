import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.androidx.baselineprofile)
}

// Optional release signing: create keystore.properties (git-ignored) next to settings.gradle.kts
// with storeFile / storePassword / keyAlias / keyPassword. Without it, release builds stay unsigned.
val keystorePropertiesFile = rootProject.file("keystore.properties")
val keystoreProperties = Properties().apply {
    if (keystorePropertiesFile.exists()) {
        keystorePropertiesFile.inputStream().use { load(it) }
    }
}

// The native SIP stack is by far the largest part of the APK, and it is packaged once per ABI.
// The default is arm64-v8a only, which is what every Android 16 phone runs. Other ABIs on request:
//   -Psiplink.abi=x86_64                  for the emulator of the integration tests
//   -Psiplink.abi=arm64-v8a,armeabi-v7a   to also cover old 32 bit phones
val abis = (providers.gradleProperty("siplink.abi").orNull ?: "arm64-v8a").split(',')

android {
    namespace = "com.sky.siplink"
    // Compiled against API 37 because current AndroidX releases require it; the app still
    // targets Android 16 (targetSdk 36), which is what decides runtime behaviour.
    compileSdk = 37

    defaultConfig {
        applicationId = "com.sky.siplink"
        minSdk = 28 // Android 9
        targetSdk = 36 // Android 16
        versionCode = 6
        versionName = "0.2.2"

        ndk {
            // Linphone SDK native libraries (16 KB page aligned).
            abiFilters += abis
        }

        // Integration tests (app/src/androidTest) need the test environment in testenv/.
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        if (keystorePropertiesFile.exists()) {
            create("release") {
                storeFile = rootProject.file(keystoreProperties.getProperty("storeFile"))
                storePassword = keystoreProperties.getProperty("storePassword")
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            signingConfig = signingConfigs.findByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
        // The native libraries are stored compressed and unpacked on installation: the APK that
        // GitHub Releases and the in-app update download shrinks from about 45 MB to about 19 MB.
        jniLibs {
            useLegacyPackaging = true
        }
    }

    androidResources {
        // The libraries bring their texts in many languages: only those of the app's languages are
        // kept (core/model/Language.kt). A filter without a region keeps no regional folder, and the
        // libraries keep their Chinese by region: zh-rCN Simplified, zh-rTW and zh-rHK Traditional.
        localeFilters += listOf("en", "zh", "zh-rCN", "zh-rTW", "zh-rHK", "ja", "ar", "fr", "ru", "es")
        // Media the Linphone SDK ships but this app has no use for: its own ring tones (the device
        // ringtone is used), hold music (the PBX plays its own) and demo recordings. About 9 MB.
        // The first part is the default pattern of the build tools, which this setting replaces.
        ignoreAssetsPattern = "!.svn:!.git:!.ds_store:!*.scc:.*:<dir>_*:!CVS:!thumbs.db:!picasa.ini:!*~" +
            ":!*.mkv:!toy-mono.wav:!hello8000.wav:!hello16000.wav:!oldphone-mono.wav"
    }

    // The language is chosen in the app, at any time: an app bundle keeps every language in the
    // base APK instead of installing only those of the system.
    bundle {
        language {
            enableSplit = false
        }
    }

    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

// The baseline profile is made on a phone by :baselineprofile (docs/testing.md) and kept in
// src/release/generated/baselineProfiles; a build only takes what is there and needs no phone.
baselineProfile {
    automaticGenerationDuringBuild = false
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.media)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)

    implementation(libs.linphone)

    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)
    implementation(libs.zxing.core)
    implementation(libs.androidx.profileinstaller)
    baselineProfile(project(":baselineprofile"))

    testImplementation(libs.junit)

    androidTestImplementation(libs.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.androidx.test.ext.junit)
}
