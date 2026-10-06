package com.music.tune

import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import com.music.tune.data.LibraryFolder
import com.music.tune.data.Organizer
import com.music.tune.support.TestMedia
import com.music.tune.support.TuneTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.regex.Pattern

/** Choosing the folder the library is read from (and organized into). */
@RunWith(AndroidJUnit4::class)
class LibraryFolderTest : TuneTest() {

    private fun folder(rel: String) = LibraryFolder.fromPath(File(TestMedia.musicDir().parentFile, rel).path, Organizer.primaryRoot)!!
    private val albumFolder get() = folder("Music/${TestMedia.IN_DIR}/${TestMedia.ALBUM}")
    private fun titles() = vm.library.value.songs.map { it.title }.toSet()

    @Test fun onlySongsInTheChosenFolderAreInTheCollection() {
        onVm { setLibraryFolder(albumFolder) }
        waitFor("library narrowed") { titles() == setOf("Alpha Song", "Beta Song", "Gamma Song") }
        openCollection("songs")
        text("Alpha Song")
        assertFalse(isShown("Delta Tune"))

        goHome()
        openSettings("collection")
        scrollTo(hasText("music folder"))
        tag("library:folder").assertTextEquals("Music/${TestMedia.IN_DIR}/${TestMedia.ALBUM}")
        tap("whole phone")
        waitFor("whole phone again") { "Delta Tune" in titles() }
        tag("library:folder").assertTextEquals("whole phone")
    }

    @Test fun theChosenFolderIsRemembered() {
        onVm { setLibraryFolder(albumFolder) }
        waitFor("library narrowed") { "Delta Tune" !in titles() && "Alpha Song" in titles() }
        scenario.close()
        scenario = androidx.test.core.app.ActivityScenario.launch(MainActivity::class.java)
        waitFor("reloaded with the same folder") { vm.library.value.loaded && titles() == setOf("Alpha Song", "Beta Song", "Gamma Song") }
        assertEquals(albumFolder, vm.libraryFolder.value)
    }

    @Test fun organizingMovesFilesInsideTheChosenFolder() {
        val lib = folder("Music/${TestMedia.IN_DIR}")
        onVm { setLibraryFolder(lib) }
        waitFor("library loaded") { titles().size == 4 }
        assertEquals("Music/${TestMedia.IN_DIR}", vm.organizeRoot)

        openSettings("collection")
        scrollTo(hasText("organize whole collection"))
        tap("organize whole collection")
        tap("move")
        allowSystemDialog()
        val alpha = "${lib.absolutePath}/${TestMedia.BAND}/${TestMedia.ALBUM}/01-Alpha Song.m4a"
        waitFor("moved inside the library folder") { TestMedia.pathOf(ctx, uris.getValue("Alpha Song")) == alpha }
        waitFor("Delta moved too") {
            TestMedia.pathOf(ctx, uris.getValue("Delta Tune")) == "${lib.absolutePath}/${TestMedia.SOLO}/${TestMedia.SINGLE}/01-Delta Tune.m4a"
        }
        // The old per-album input folders were emptied and removed; the library folder stays.
        waitFor("old album folder removed") { !File(lib.absolutePath, TestMedia.ALBUM).exists() }
        assertTrue(File(lib.absolutePath).isDirectory)
    }

    @Test fun foldersOutsideMusicAreNeverOrganizedWithoutAllFilesAccess() {
        org.junit.Assume.assumeFalse(android.os.Environment.isExternalStorageManager())
        onVm { setLibraryFolder(LibraryFolder(Organizer.primaryRoot, "MyTunes")) }
        // Moving files to Music/ would take them out of the library (and a synced folder).
        assertEquals(null, vm.organizeRoot)
    }

    @Test fun pickingAFolderWithTheSystemPicker() {
        // Start in the album folder, then confirm it in Android's own picker.
        onVm { setLibraryFolder(albumFolder) }
        waitFor("narrowed") { "Delta Tune" !in titles() }
        openSettings("collection")
        scrollTo(hasText("choose folder"))
        tap("choose folder")
        val use = device.wait(Until.findObject(By.text(Pattern.compile("(?i)use this folder"))), 10_000)
            ?: throw AssertionError("system folder picker didn't open")
        use.click()
        device.wait(Until.findObject(By.text(Pattern.compile("(?i)allow"))), 5_000)?.click()
        waitFor("picker result applied") { vm.libraryFolder.value == albumFolder && titles().size == 3 }
    }
}
