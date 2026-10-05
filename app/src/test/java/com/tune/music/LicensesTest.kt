package com.tune.music

import com.tune.music.ui.screens.COMPONENTS
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** The licences page lists only texts that ship with the app, and the repo carries Tune's own licence. */
class LicensesTest {
    private val assets = File("src/main/assets/licenses")

    @Test fun everyComponentsLicenceTextShips() {
        COMPONENTS.flatMap { it.files }.distinct().forEach { f ->
            assertTrue("missing asset licenses/$f", File(assets, f).isFile)
        }
    }

    @Test fun everyShippedTextIsUsed() {
        val used = COMPONENTS.flatMap { it.files }.toSet()
        assets.list()!!.forEach { assertTrue("unused licence text $it", it in used) }
    }

    @Test fun theRepositoryIsGpl3() {
        val license = File("../LICENSE").readText()
        assertTrue(license.contains("GNU GENERAL PUBLIC LICENSE") && license.contains("Version 3, 29 June 2007"))
    }
}
