package com.music.tune.baselineprofile

import androidx.benchmark.macro.BaselineProfileMode
import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.FrameTimingMetric
import androidx.benchmark.macro.StartupMode
import androidx.benchmark.macro.StartupTimingMetric
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Cold startup and scrolling, without the profile (as an APK runs before the
 * phone gets round to compiling it) and with it:
 * `./gradlew :baselineprofile:connectedBenchmarkReleaseAndroidTest`.
 * Emulator timings are only good for comparing the two.
 */
@RunWith(AndroidJUnit4::class)
class Benchmarks {
    @get:Rule val rule = MacrobenchmarkRule()

    private val none = CompilationMode.None()
    private val profiled = CompilationMode.Partial(BaselineProfileMode.Require)

    @Test fun startupWithoutProfile() = startup(none)
    @Test fun startupWithProfile() = startup(profiled)
    @Test fun scrollingWithoutProfile() = scrolling(none)
    @Test fun scrollingWithProfile() = scrolling(profiled)

    private fun startup(mode: CompilationMode) = rule.measureRepeated(
        packageName = PACKAGE,
        metrics = listOf(StartupTimingMetric()),
        compilationMode = mode,
        startupMode = StartupMode.COLD,
        iterations = 10,
        setupBlock = { grantPermissions(); pressHome() },
    ) {
        startActivityAndWait()
        waitForHub()
    }

    private fun scrolling(mode: CompilationMode) = rule.measureRepeated(
        packageName = PACKAGE,
        metrics = listOf(FrameTimingMetric()),
        compilationMode = mode,
        // No startup mode: one would kill the app that setupBlock opened.
        iterations = 5,
        setupBlock = {
            grantPermissions()
            killProcess() // else it comes back where the last run left it
            pressHome()
            startActivityAndWait()
            waitForHub()
        },
    ) {
        open("songs")
        fling()
        backToHub()
        open("albums")
        fling()
    }
}
