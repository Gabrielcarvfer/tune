package com.tune.music

import android.media.MediaScannerConnection
import android.os.Environment
import android.provider.MediaStore
import androidx.compose.ui.test.hasText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.tune.music.data.LibraryFolder
import com.tune.music.data.Organizer
import com.tune.music.support.TestMedia
import com.tune.music.support.TuneTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeFalse
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * A library in a synced folder outside Music/ (e.g. Resilio Sync): with All
 * files access, organizing renames files in place inside it; nothing leaves it.
 *
 * Android kills the app when All files access changes, so it can't be toggled
 * from a test: each test runs only in the matching state. To run the in-place
 * test, grant it first:
 *   adb shell appops set com.tune.music MANAGE_EXTERNAL_STORAGE allow
 */
@RunWith(AndroidJUnit4::class)
class SyncedFolderTest : TuneTest() {

    // Songs are made by the test itself, outside Music/ (MediaStore can't put them there).
    override val songs = emptyList<TestMedia.Spec>()

    private val synced get() = File(TestMedia.musicDir().parentFile, "TuneTestSynced")
    private val dots = TestMedia.Spec("Dots Song", "Zz Dots Band", "... Dots Album", 1, 6, 392.0)

    private val allFiles get() = Environment.isExternalStorageManager()

    private fun mediaIdOf(path: String): Long? = ctx.contentResolver.query(
        MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, arrayOf(MediaStore.Audio.Media._ID),
        "${MediaStore.Audio.Media.DATA} = ?", arrayOf(path), null,
    )?.use { if (it.moveToFirst()) it.getLong(0) else null }

    @After fun removeSyncedFolder() {
        if (allFiles) runCatching { synced.deleteRecursively() }
    }

    @Test fun withoutAllFilesAccessFilesInASyncedFolderAreNeverMoved() {
        assumeFalse("needs All files access off", allFiles)
        onVm { setLibraryFolder(LibraryFolder.fromPath(synced.path, Organizer.primaryRoot)) }
        assertEquals(null, vm.organizeRoot)
        tap("settings")
        scrollTo(hasText("allow all files access"))
        text("allow all files access")
        scrollTo(hasText("organize whole collection"))
        text("Unavailable for this music folder: files stay where they are.")
    }

    @Test fun organizingRenamesInPlaceInsideTheSyncedFolder() {
        assumeTrue("needs All files access (see class comment)", allFiles)
        assertTrue(vm.allFilesAccess.value)
        // A song somewhere inside the synced library, as a sync tool would leave it.
        val src = File(synced, "incoming/Dots Song.m4a").apply { parentFile!!.mkdirs() }
        TestMedia.encodeTagged(ctx, dots, src)
        MediaScannerConnection.scanFile(ctx, arrayOf(src.path), null, null)
        waitFor("scanned") { mediaIdOf(src.path) != null }
        val id = mediaIdOf(src.path)!!

        onVm { setLibraryFolder(LibraryFolder.fromPath(synced.path, Organizer.primaryRoot)) }
        waitFor("in the library") { vm.library.value.songs.any { it.id == id } }
        assertEquals("TuneTestSynced", vm.organizeRoot)

        tap("settings")
        scrollTo(hasText("All files access: allowed"))
        scrollTo(hasText("organize whole collection"))
        tap("organize whole collection")
        text("TuneTestSynced/<album artist>/<album>/<number>-<title>", substring = true)
        tap("move")

        // Renamed inside the synced folder; the leading dots don't hide the album folder.
        val dst = File(synced, "Zz Dots Band/_.. Dots Album/01-Dots Song.m4a")
        waitFor("moved in place") { dst.exists() && !src.exists() }
        assertEquals("same MediaStore entry (playlists keep working)", id, mediaIdOf(dst.path))
        waitFor("still in the library at the new path") {
            vm.library.value.songs.any { it.id == id && it.path == dst.path }
        }
        waitFor("emptied folder removed") { !File(synced, "incoming").exists() }
        assertTrue("library folder kept", synced.isDirectory)
    }
}
