package com.music.tune

import android.content.SharedPreferences
import android.graphics.Color
import android.media.MediaMetadataRetriever
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.music.tune.data.AcoustId
import com.music.tune.data.TagLib
import com.music.tune.support.FakeHttp
import com.music.tune.support.FakeHttp.Companion.Rel
import com.music.tune.support.TestMedia
import com.music.tune.support.TuneTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Picard-style identification against a fake AcoustID / Cover Art Archive:
 * real fingerprints, canned answers.
 */
@RunWith(AndroidJUnit4::class)
class IdentifyTest : TuneTest() {

    override fun prefs(e: SharedPreferences.Editor) {
        e.putString("acoustid", "testkey")
    }

    private val release = "Tune Test Release"
    private val releaseArtist = "Tune Test Release Artist"
    private val front = TestMedia.jpeg(Color.GREEN)
    private val backCover = TestMedia.jpeg(Color.YELLOW)

    private fun full(track: Int, title: String) =
        Rel("rel-full", "rg-full", release, releaseArtist, 1999, track, 3, trackTitle = title)

    private fun serveAlbum() {
        answerLookup("Alpha Song", FakeHttp.lookup("Alpha Song", TestMedia.BAND, full(1, "Alpha (Remastered)"),
            Rel("rel-comp", "rg-comp", "Tune Test Hits", "Various Artists", 2005, 9, 20, type = "Compilation")))
        answerLookup("Beta Song", FakeHttp.lookup("Beta Song", TestMedia.BAND, full(2, "Beta (Remastered)")))
        answerLookup("Gamma Song", FakeHttp.lookup("Gamma Song", TestMedia.BAND, full(3, "Gamma (Remastered)")))
        fake.pages["https://coverartarchive.org/release/rel-full"] = FakeHttp.coverArchive(
            Triple("https://img.test/front.jpg", "Front", true),
            Triple("https://img.test/back.jpg", "Back", false),
        )
        fake.pages["https://img.test/front.jpg"] = front
        fake.pages["https://img.test/back.jpg"] = backCover
    }

    private fun findAlbumInfo() {
        openCollection("albums")
        tap(TestMedia.ALBUM)
        tag("appbar:more").performClick()
        menuItem("find album info online")
    }

    private fun embeddedPicture(title: String): ByteArray? {
        val r = MediaMetadataRetriever()
        return try {
            r.setDataSource(TestMedia.pathOf(ctx, uris.getValue(title)))
            r.embeddedPicture
        } finally {
            r.release()
        }
    }

    @Test fun sendsARealFingerprintToAcoustId() {
        serveAlbum()
        findAlbumInfo()
        text(release)
        val form = fake.formOf(0)
        assertEquals("testkey", form["client"])
        assertTrue("fingerprint looks like Chromaprint", form.getValue("fingerprint").startsWith("AQ"))
        // Whole seconds, truncated like fpcalc does (a 7 s AAC file is ~6.98 s).
        assertEquals((song("Alpha Song").durationMs / 1000).toString(), form["duration"])
        assertEquals(3, fake.requests.count { it.startsWith("POST ${AcoustId.LOOKUP_URL}") })
    }

    @Test fun releaseWithEveryTrackRanksFirst() {
        serveAlbum()
        findAlbumInfo()
        text("2 releases found. Pick the one you own.")
        text("matches 3 of 3 songs")
        text("matches 1 of 3 songs")
        assertTrue(boundsOf(text(release)).top < boundsOf(text("Tune Test Hits")).top)
    }

    @Test fun pickReleaseAndCoverThenApply() {
        serveAlbum()
        findAlbumInfo()
        tap(release)
        text("01  Alpha (Remastered)")
        text("was: Alpha Song — ${TestMedia.BAND}")
        // Covers: front is preselected; choose the back instead.
        text("front")
        tap("back")
        button("apply").performClick()
        allowSystemDialog()

        waitFor("tags applied", 20_000) {
            vm.library.value.songs.count { it.album == release && it.albumArtist == releaseArtist } == 3
        }
        // Tags are written first, then the files are moved.
        waitFor("files organized", 20_000) {
            listOf("Alpha Song", "Beta Song", "Gamma Song")
                .all { TestMedia.pathOf(ctx, uris.getValue(it))!!.contains("/Music/$releaseArtist/$release/") }
        }
        listOf("Alpha Song" to "Alpha (Remastered)", "Beta Song" to "Beta (Remastered)", "Gamma Song" to "Gamma (Remastered)")
            .forEachIndexed { i, (orig, new) ->
                val path = TestMedia.pathOf(ctx, uris.getValue(orig))!!
                assertTrue(path, path.endsWith("/Music/$releaseArtist/$release/0${i + 1}-$new.m4a"))
                val tags = TagLib.nativeRead(path)!!
                assertEquals(new, tags[TagLib.TITLE])
                assertEquals("1999", tags[TagLib.DATE])
                assertArrayEquals("$orig got the chosen cover", backCover, embeddedPicture(orig))
            }
    }

    @Test fun keepingTheCurrentCoverLeavesArtAlone() {
        serveAlbum()
        findAlbumInfo()
        tap(release)
        tap("keep current")
        button("apply").performClick()
        allowSystemDialog()
        waitFor("tags applied", 20_000) { vm.library.value.songs.count { it.album == release } == 3 }
        waitFor("files organized", 20_000) {
            TestMedia.pathOf(ctx, uris.getValue("Alpha Song"))!!.contains("/Music/$releaseArtist/$release/")
        }
        assertArrayEquals(TestMedia.jpeg(Color.RED), embeddedPicture("Alpha Song"))
    }

    @Test fun reportsAcoustIdErrors() {
        fake.lookupResponses += FakeHttp.error("invalid API key")
        findAlbumInfo()
        text("Couldn't identify: acoustid: invalid API key")
    }

    @Test fun reportsWhenNothingMatches() {
        fake.lookupResponses += """{"status":"ok","results":[]}"""
        openCollection("songs")
        longPress("Delta Tune")
        menuItem("find info online")
        text("No matches found on AcoustID.")
    }

    @Test fun backFromAReleaseReturnsToTheList() {
        serveAlbum()
        findAlbumInfo()
        tap(release)
        text("01  Alpha (Remastered)")
        back()
        text("2 releases found. Pick the one you own.")
    }
}

/** Without a key, identification explains how to get one. */
@RunWith(AndroidJUnit4::class)
class IdentifyWithoutKeyTest : TuneTest() {
    @Test fun explainsAndLinksToSettings() {
        openCollection("songs")
        longPress("Alpha Song")
        menuItem("find info online")
        text("acoustid.org/new-application", substring = true)
        tap("open settings")
        waitFor("settings") { screen == Screen.Settings }
        scrollTo(hasText("acoustid api key"))
        text("acoustid api key")
    }
}
