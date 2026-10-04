package com.tune.music

import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextReplacement
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.tune.music.data.TagLib
import com.tune.music.support.TestMedia
import com.tune.music.support.TuneTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Editing tags inside the files, organizing them, and deleting. */
@RunWith(AndroidJUnit4::class)
class MetadataTest : TuneTest() {

    private val music get() = TestMedia.musicDir()
    private fun pathOf(title: String) = TestMedia.pathOf(ctx, uris.getValue(title))!!
    private fun tagsOf(title: String) = TagLib.nativeRead(pathOf(title))!!

    private fun openSongEditor(title: String) {
        openCollection("songs")
        longPress(title)
        menuItem("edit info")
        waitFor("editor loaded") { isShown("title") && runCatching { tag("field:title").assertTextEquals(title) }.isSuccess }
    }

    private fun saveAndConsent() {
        button("save").performClick()
        allowSystemDialog()
    }

    @Test fun songEditorShowsTheTagsFromTheFile() {
        openSongEditor("Delta Tune")
        tag("field:artist").assertTextEquals(TestMedia.SOLO)
        tag("field:album").assertTextEquals(TestMedia.SINGLE)
        tag("field:genre").assertTextEquals("Ambient")
        tag("field:year").assertTextEquals("2020")
        tag("field:track").assertTextEquals("1")
        scrollTo(hasText("will be moved to"))
        text("Music/${TestMedia.SOLO}/${TestMedia.SINGLE}/01-Delta Tune.m4a")
    }

    @Test fun savingWritesTagsIntoTheFileAndOrganizesIt() {
        openSongEditor("Alpha Song")
        tag("field:title").performTextReplacement("Alpha Edited")
        tag("field:genre").performTextReplacement("Chillout")
        // The destination preview follows the typing.
        scrollTo(hasText("Music/${TestMedia.BAND}/${TestMedia.ALBUM}/01-Alpha Edited.m4a"))
        text("Music/${TestMedia.BAND}/${TestMedia.ALBUM}/01-Alpha Edited.m4a")
        saveAndConsent()

        waitFor("library updated") { vm.library.value.songs.any { it.title == "Alpha Edited" } }
        waitFor("file moved") { pathOf("Alpha Song").endsWith("01-Alpha Edited.m4a") }
        val path = pathOf("Alpha Song")
        assertTrue(path, path.endsWith("/Music/${TestMedia.BAND}/${TestMedia.ALBUM}/01-Alpha Edited.m4a"))
        val tags = TagLib.nativeRead(path)!!
        assertEquals("Alpha Edited", tags[TagLib.TITLE])
        assertEquals("Chillout", tags[TagLib.GENRE])
        assertEquals(TestMedia.BAND, tags[TagLib.ARTIST])
    }

    @Test fun cancellingTheEditorChangesNothing() {
        openSongEditor("Beta Song")
        tag("field:title").performTextReplacement("Nope")
        button("cancel").performClick()
        compose.waitForIdle()
        assertEquals("Beta Song", tagsOf("Beta Song")[TagLib.TITLE])
        assertTrue(pathOf("Beta Song").contains("/${TestMedia.IN_DIR}/"))
    }

    @Test fun albumEditorUpdatesEveryTrackAndRemovesTheEmptiedFolder() {
        openCollection("albums")
        tap(TestMedia.ALBUM)
        tag("appbar:more").performClick()
        menuItem("edit album info")
        replaceText("field:album", "Tune Renamed")
        replaceText("rowtitle:Beta Song", "Beta (Live)")
        replaceText("rowtrack:Gamma Song", "7")
        saveAndConsent()

        waitFor("all tracks renamed") {
            vm.library.value.songs.filter { it.albumArtist == TestMedia.BAND }.let { s -> s.size == 3 && s.all { it.album == "Tune Renamed" } }
        }
        // Tags are written first, then the files move; wait for the move to finish.
        val dir = File(music, "${TestMedia.BAND}/Tune Renamed")
        val expected = setOf("01-Alpha Song.m4a", "02-Beta (Live).m4a", "07-Gamma Song.m4a")
        waitFor("files moved") { dir.list()?.toSet() == expected && pathOf("Gamma Song").startsWith(dir.path) }
        assertEquals("Beta (Live)", tagsOf("Beta Song")[TagLib.TITLE])
        assertEquals("7", tagsOf("Gamma Song")[TagLib.TRACK])
        // The album's old folder is gone; its parent still holds the other album.
        assertFalse(File(music, "${TestMedia.IN_DIR}/${TestMedia.ALBUM}").exists())
        assertTrue(File(music, "${TestMedia.IN_DIR}/${TestMedia.SINGLE}").exists())
    }

    @Test fun turningOrganizingOffLeavesFilesWhereTheyAre() {
        tap("settings")
        compose.onNode(hasScrollAction()).performScrollToNode(hasText("move files after editing info"))
        tap("move files after editing info")
        waitFor("organizing off") { !vm.autoOrganize.value }
        text("Off")

        openSongEditor("Gamma Song")
        tag("field:genre").performTextReplacement("Drone")
        saveAndConsent()
        // The file is rewritten in place; reads in the middle of that may fail.
        waitFor("saved") { runCatching { tagsOf("Gamma Song")[TagLib.GENRE] }.getOrNull() == "Drone" }
        assertTrue(pathOf("Gamma Song").endsWith("/${TestMedia.IN_DIR}/${TestMedia.ALBUM}/Gamma Song.m4a"))
    }

    @Test fun organizeWholeCollectionMovesEverythingAndCleansUp() {
        tap("settings")
        compose.onNode(hasScrollAction()).performScrollToNode(hasText("organize whole collection"))
        tap("organize whole collection")
        text("organize files?")
        tap("move")
        allowSystemDialog()
        waitFor("4 files moved") { uris.keys.all { !pathOf(it).contains("/${TestMedia.IN_DIR}/") } }

        assertTrue(pathOf("Alpha Song").endsWith("/Music/${TestMedia.BAND}/${TestMedia.ALBUM}/01-Alpha Song.m4a"))
        assertTrue(pathOf("Delta Tune").endsWith("/Music/${TestMedia.SOLO}/${TestMedia.SINGLE}/01-Delta Tune.m4a"))
        assertFalse("input folder tree removed", File(music, TestMedia.IN_DIR).exists())

        // Running it again finds nothing to do (once the app has reloaded its library).
        waitFor("library reloaded") { vm.songsToOrganize(vm.library.value.songs.filter { it.title in uris.keys }).isEmpty() }
        tap("organize whole collection")
        text("files are already organized")
    }

    @Test fun deletingASongRemovesTheFileAndItsEmptyFolder() {
        val path = pathOf("Delta Tune")
        openCollection("songs")
        longPress("Delta Tune")
        menuItem("delete")
        text("\"Delta Tune\" will be deleted from your phone.")
        tap("delete")
        assertTrue("system asked for consent", allowSystemDialog())
        waitFor("song gone") { vm.library.value.songs.none { it.title == "Delta Tune" } }
        text("song deleted")
        assertFalse(File(path).exists())
        waitFor("empty folder removed") { !File(music, "${TestMedia.IN_DIR}/${TestMedia.SINGLE}").exists() }
        assertTrue("other folders untouched", File(music, "${TestMedia.IN_DIR}/${TestMedia.ALBUM}").exists())
    }

    @Test fun cancellingADeleteKeepsTheSong() {
        openCollection("songs")
        longPress("Gamma Song")
        menuItem("delete")
        tap("cancel")
        longPress("Gamma Song")
        menuItem("delete")
        back() // the back key also dismisses the message box
        compose.waitForIdle()
        assertTrue(File(pathOf("Gamma Song")).exists())
        text("Gamma Song")
    }
}
