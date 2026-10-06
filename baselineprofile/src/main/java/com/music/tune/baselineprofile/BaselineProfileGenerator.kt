package com.music.tune.baselineprofile

import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Records which code Tune runs at startup and while browsing, into
 * app/src/main/generated/baselineProfiles: `./gradlew :app:generateBaselineProfile`
 * on the emulator, with music on it.
 */
@RunWith(AndroidJUnit4::class)
class BaselineProfileGenerator {
    @get:Rule val rule = BaselineProfileRule()

    @Test fun generate() = rule.collect(PACKAGE, includeInStartupProfile = true) {
        grantPermissions()
        pressHome()
        startActivityAndWait()
        waitForHub()
        browse()
    }
}
