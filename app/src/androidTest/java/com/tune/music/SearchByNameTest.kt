package com.tune.music

import android.content.SharedPreferences
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.tune.music.data.MusicBrainz
import com.tune.music.data.TagLib
import com.tune.music.support.TestMedia
import com.tune.music.support.TuneTest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Finding a release on MusicBrainz by name and applying it (fake MusicBrainz). */
@RunWith(AndroidJUnit4::class)
class SearchByNameTest : TuneTest() {

    override fun prefs(e: SharedPreferences.Editor) {
        e.putString("acoustid", "testkey")
    }

    private val release = "Tune Test Deluxe Edition"
    private val releaseArtist = "Tune Test Deluxe Band"

    private fun credit(name: String) = JSONArray().put(JSONObject().put("name", name))

    /** One release found for the album's name, with three tracks (titles tweaked) and a bonus one. */
    private fun serve(album: String = TestMedia.ALBUM, artist: String = TestMedia.BAND) {
        val info = JSONObject()
            .put("id", "rel-mb").put("title", release).put("date", "2022-03-04").put("country", "XW")
            .put("artist-credit", credit(releaseArtist))
            .put("release-group", JSONObject().put("id", "rg-mb").put("primary-type", "Album"))
        fake.pages[MusicBrainz.searchUrl(album, artist)] = JSONObject()
            .put("releases", JSONArray().put(JSONObject(info.toString()).put("track-count", 4)
                .put("media", JSONArray().put(JSONObject().put("format", "Digital Media").put("track-count", 4)))))
            .toString().toByteArray()
        val tracks = JSONArray()
        listOf("Alpha Song (Deluxe)", "Beta Song", "Gamma Song", "Bonus Track").forEachIndexed { i, t ->
            tracks.put(JSONObject().put("position", i + 1).put("title", t).put("length", 7_000)
                .put("recording", JSONObject().put("id", "rec-$i")))
        }
        fake.pages[MusicBrainz.releaseUrl("rel-mb")] = JSONObject(info.toString())
            .put("media", JSONArray().put(JSONObject().put("position", 1).put("format", "Digital Media").put("track-count", 4).put("tracks", tracks)))
            .toString().toByteArray()
    }

    private fun findAlbumInfo() {
        openCollection("albums")
        tap(TestMedia.ALBUM)
        tag("appbar:more").performClick()
        menuItem("find album info online")
    }

    @Test fun searchByNameAndApplyTheRelease() {
        fake.lookupResponses += """{"status":"ok","results":[]}"""
        serve()
        findAlbumInfo()
        text("No matches found on AcoustID.")
        tap("search by name")
        // Prefilled from the album.
        tag("field:album").assertTextEquals(TestMedia.ALBUM)
        tag("field:artist").assertTextEquals(TestMedia.BAND)
        tap("search")
        text("1 releases found. Pick the one you own.", substring = true)
        tap(release)
        // Songs paired with tracks by title; the bonus track has no song.
        text("01  Alpha Song (Deluxe)")
        text("02  Beta Song")
        text("03  Gamma Song")
        button("apply").performClick()
        allowSystemDialog()

        waitFor("tags applied", 30_000) {
            vm.library.value.songs.count { it.album == release && it.albumArtist == releaseArtist } == 3
        }
        // Files are written, then moved; read them once they've settled.
        fun settled(title: String, field: Int, value: String) = waitFor("$title field $field = $value", 20_000) {
            runCatching { TagLib.nativeRead(TestMedia.pathOf(ctx, uris.getValue(title))!!)!![field] }.getOrNull() == value
        }
        settled("Alpha Song", TagLib.TITLE, "Alpha Song (Deluxe)")
        settled("Beta Song", TagLib.DATE, "2022")
        settled("Beta Song", TagLib.TRACK, "2")
        assertTrue(fake.requests.any { it.startsWith("GET https://musicbrainz.org/ws/2/release?query=") })
    }

    @Test fun editedNamesAreSearched() {
        fake.lookupResponses += """{"status":"ok","results":[]}"""
        serve(album = "Something Else", artist = "")
        findAlbumInfo()
        tap("search by name")
        tag("field:album").performTextReplacement("Something Else")
        tag("field:artist").performTextReplacement("")
        tap("search")
        text(release)
    }

    @Test fun searchErrorsAreShown() {
        fake.lookupResponses += """{"status":"ok","results":[]}"""
        findAlbumInfo()
        tap("search by name")
        tap("search")
        text("Couldn't search:", substring = true)
    }

    @Test fun nothingFound() {
        fake.lookupResponses += """{"status":"ok","results":[]}"""
        fake.pages[MusicBrainz.searchUrl(TestMedia.ALBUM, TestMedia.BAND)] = """{"releases":[]}""".toByteArray()
        findAlbumInfo()
        tap("search by name")
        tap("search")
        text("No releases found on MusicBrainz.")
    }

    @Test fun listeningAgainFromTheSearch() {
        fake.lookupResponses += """{"status":"ok","results":[]}"""
        findAlbumInfo()
        text("No matches found on AcoustID.")
        tap("search by name")
        tap("listen instead")
        text("No matches found on AcoustID.")
    }
}

/** Without an AcoustID key, searching by name still works. */
@RunWith(AndroidJUnit4::class)
class SearchByNameWithoutKeyTest : TuneTest() {
    @Test fun searchIsOfferedWithoutAKey() {
        fake.pages[MusicBrainz.searchUrl("Delta Tune", TestMedia.SOLO)] =
            """{"releases":[{"id":"rel-x","title":"Tune Test Found","artist-credit":[{"name":"Someone"}]}]}""".toByteArray()
        openCollection("songs")
        longPress("Delta Tune")
        menuItem("find info online")
        text("acoustid.org/new-application", substring = true)
        text("Or search MusicBrainz by name:")
        // A single song: its album name is the starting point; search for the song's title instead.
        tag("field:album").performTextReplacement("Delta Tune")
        tap("search")
        // (The keyboard may cover the results.)
        scrollTo(hasText("Tune Test Found"))
        text("Tune Test Found")
    }
}
