package com.tune.music

import android.content.SharedPreferences
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.tune.music.data.TagLib
import com.tune.music.support.FakeHttp
import com.tune.music.support.FakeHttp.Companion.Rel
import com.tune.music.support.TestMedia
import com.tune.music.support.TuneTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Merging albums: picking them, editing them as one, or finding the release that holds them all. */
@RunWith(AndroidJUnit4::class)
class MergeAlbumsTest : TuneTest() {

    override fun prefs(e: SharedPreferences.Editor) {
        e.putString("acoustid", "testkey")
    }

    private fun tagsOf(title: String) = TagLib.nativeRead(TestMedia.pathOf(ctx, uris.getValue(title))!!)!!

    private fun openMerge() {
        openCollection("albums")
        longPress(TestMedia.ALBUM)
        menuItem("merge with other albums")
        waitFor("merge screen") { screen is Screen.MergeAlbums }
        text("1 selected")
    }

    @Test fun editingTwoAlbumsAsOneWritesTheSameAlbumOnEverySong() {
        openMerge()
        tag("merge:${TestMedia.SINGLE}").performClick()
        text("2 selected")
        button("edit as one").performClick()
        waitFor("merge editor") { screen is Screen.EditAsAlbum }
        text("2 albums as one")
        // Starts from the album most songs are on.
        tag("field:album").assertTextEquals(TestMedia.ALBUM)
        tag("field:album artist").assertTextEquals(TestMedia.BAND)
        tag("field:album").performTextReplacement("Tune Test Merged")
        tag("appbar:more").performClick()
        menuItem("number tracks in order")
        button("save").performClick()
        allowSystemDialog()

        waitFor("all four songs on one album", 30_000) {
            vm.library.value.songs.count { it.album == "Tune Test Merged" && it.albumArtist == TestMedia.BAND } == 4
        }
        // Files are written, then moved; read them once they've settled.
        fun settled(title: String, field: Int, value: String) =
            waitFor("$title field $field = $value", 20_000) { runCatching { tagsOf(title)[field] }.getOrNull() == value }
        settled("Delta Tune", TagLib.ALBUM, "Tune Test Merged")
        settled("Delta Tune", TagLib.ALBUM_ARTIST, TestMedia.BAND)
        settled("Delta Tune", TagLib.TRACK, "4")
        settled("Alpha Song", TagLib.TRACK, "1")
        assertEquals(1, vm.library.value.albums.count { it.title == "Tune Test Merged" })
    }

    @Test fun oneAlbumIsNotEnoughToMerge() {
        openMerge()
        button("edit as one").performClick()
        text("pick at least two albums")
        assertTrue(screen is Screen.MergeAlbums)
        // Tapping a chosen album again unchooses it.
        tag("merge:${TestMedia.SINGLE}").performClick()
        text("2 selected")
        tag("merge:${TestMedia.SINGLE}").performClick()
        text("1 selected")
    }

    @Test fun findingInfoForMergedAlbumsPrefersTheReleaseWithMostSongs() {
        // Each song's own lookup ranks a release of its own first; only "complete" has all four.
        val complete = { track: Int, title: String -> Rel("rel-all", "rg-all", "Tune Test Complete", "Tune Test Complete Band", 2021, track, 4, trackTitle = title) }
        listOf("Alpha Song", "Beta Song", "Gamma Song", "Delta Tune").forEachIndexed { i, t ->
            answerLookup(t, FakeHttp.lookup(t, TestMedia.BAND, Rel("own-$i", "rg-own-$i", "Tune Test Own $i", TestMedia.BAND, 2000, 1, 1, trackTitle = t), complete(i + 1, t)))
        }
        openMerge()
        tag("merge:${TestMedia.SINGLE}").performClick()
        button("find online").performClick()
        waitFor("identify") { screen is Screen.Identify }
        text("find album info")
        text("matches 4 of 4 songs")
        assertTrue(boundsOf(text("Tune Test Complete")).top < boundsOf(text("Tune Test Own 0")).top)
        assertEquals(4, fake.requests.count { it.startsWith("POST") })
    }
}
