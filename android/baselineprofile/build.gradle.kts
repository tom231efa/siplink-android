// Makes the baseline profile of the app (BaselineProfileGenerator) and measures how fast the app
// starts (StartupBenchmark), both on a connected phone: see docs/testing.md. A normal build of the
// app needs neither this module's tests nor a phone.
plugins {
    alias(libs.plugins.android.test)
    alias(libs.plugins.androidx.baselineprofile)
}

android {
    namespace = "com.sky.siplink.baselineprofile"
    compileSdk = 37

    defaultConfig {
        minSdk = 28
        targetSdk = 36
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    targetProjectPath = ":app"
}

// The connected phone, not a Gradle-managed device: the app is built only for the ABIs in
// siplink.abi (arm64-v8a unless set), and startup is measured on a real phone.
baselineProfile {
    useConnectedDevices = true
}

dependencies {
    implementation(libs.androidx.test.ext.junit)
    implementation(libs.androidx.test.runner)
    implementation(libs.androidx.test.uiautomator)
    implementation(libs.androidx.benchmark.macro.junit4)
}
