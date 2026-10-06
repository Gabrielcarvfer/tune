// Generates Tune's baseline profile and measures startup and scrolling, on the
// emulator: see "Performance" in the README.
plugins {
    id("com.android.test")
    id("org.jetbrains.kotlin.android")
    id("androidx.baselineprofile")
}

android {
    namespace = "com.music.tune.baselineprofile"
    compileSdk = 36

    defaultConfig {
        minSdk = 28
        targetSdk = 36
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        // Benchmarks refuse to run on an emulator, whose timings aren't real;
        // here they're only compared with each other.
        testInstrumentationRunnerArguments["androidx.benchmark.suppressErrors"] = "EMULATOR"
    }

    targetProjectPath = ":app"

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

baselineProfile {
    // The emulator that's already running (ANDROID_SERIAL), not a managed one.
    useConnectedDevices = true
}

dependencies {
    implementation("androidx.test.ext:junit:1.2.1")
    implementation("androidx.test.uiautomator:uiautomator:2.3.0")
    implementation("androidx.benchmark:benchmark-macro-junit4:1.4.1")
}

// Only the release-like variants make sense here; without a debug one, the
// usual `./gradlew connectedDebugAndroidTest` doesn't run these too.
androidComponents {
    beforeVariants(selector().withBuildType("debug")) { it.enable = false }
}
