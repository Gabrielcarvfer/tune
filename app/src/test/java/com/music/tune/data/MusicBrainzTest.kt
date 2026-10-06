package com.music.tune.data

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.URLDecoder

class MusicBrainzTest {

    /** Trimmed MusicBrainz /ws/2/release search response. */
    private val search = JSONObject(
        """
        {"created": "2026-10-05T00:00:00Z", "count": 2, "offset": 0, "releases": [
          {"id": "rel-25", "score": 100, "title": "Red Alert (25th Anniversary)", "status": "Official",
           "date": "2021-06-01", "country": "XW", "track-count": 40,
           "artist-credit": [{"name": "Frank Klepacki", "artist": {"id": "fk", "name": "Frank Klepacki"}}],
           "release-group": {"id": "rg-25", "primary-type": "Album"},
           "media": [{"format": "Digital Media", "track-count": 20}, {"format": "Digital Media", "track-count": 20}]},
          {"id": "rel-ost", "score": 90, "title": "Red Alert", "date": "1996",
           "artist-credit": [{"name": "Frank Klepacki", "joinphrase": " & "}, {"name": "Someone"}],
           "release-group": {"id": "rg-ost", "primary-type": "Soundtrack"},
           "media": [{"format": "CD", "track-count": 15}]}
        ]}
        """.trimIndent(),
    )

    /** Trimmed /ws/2/release/<id>?inc=recordings+artist-credits+release-groups. */
    private val release = JSONObject(
        """
        {"id": "rel-25", "title": "Red Alert (25th Anniversary)", "date": "2021-06-01", "country": "XW",
         "artist-credit": [{"name": "Frank Klepacki"}],
         "release-group": {"id": "rg-25", "primary-type": "Album"},
         "media": [
           {"position": 1, "format": "Digital Media", "track-count": 2, "tracks": [
             {"position": 1, "number": "1", "title": "Hell March", "length": 200000,
              "recording": {"id": "r1", "title": "Hell March", "length": 200000}},
             {"position": 2, "number": "2", "title": "Grinder", "length": 230000,
              "recording": {"id": "r2", "title": "Grinder"}}]},
           {"position": 2, "format": "Digital Media", "track-count": 2, "tracks": [
             {"position": 1, "number": "1", "title": "Hell March 2", "length": 300000,
              "artist-credit": [{"name": "Frank Klepacki", "joinphrase": " feat. "}, {"name": "Guest"}],
              "recording": {"id": "r3", "title": "Hell March 2"}},
             {"position": 2, "number": "2", "title": "Bigfoot", "length": 0,
              "recording": {"id": "r4", "title": "Bigfoot", "length": 250000}}]}
         ]}
        """.trimIndent(),
    )

    @Test fun searchUrlQuotesAndEscapesTheNames() {
        val url = MusicBrainz.searchUrl("Red \"Alert\"", "Frank Klepacki")
        assertTrue(url.startsWith("https://musicbrainz.org/ws/2/release?query="))
        assertTrue(url.endsWith("&fmt=json&limit=25"))
        val q = URLDecoder.decode(url.substringAfter("query=").substringBefore("&"), "UTF-8")
        assertEquals("release:\"Red \\\"Alert\\\"\" AND artist:\"Frank Klepacki\"", q)
    }

    @Test fun searchWithoutArtistOnlyNamesTheRelease() {
        val q = URLDecoder.decode(MusicBrainz.searchUrl("Coastline", " ").substringAfter("query=").substringBefore("&"), "UTF-8")
        assertEquals("release:\"Coastline\"", q)
    }

    @Test fun parsesSearchResults() {
        val found = MusicBrainz.parseSearch(search)
        assertEquals(2, found.size)
        val a = found[0]
        assertEquals("rel-25", a.id)
        assertEquals("rg-25", a.releaseGroupId)
        assertEquals("Red Alert (25th Anniversary)", a.title)
        assertEquals("Frank Klepacki", a.artist)
        assertEquals(2021, a.year)
        assertEquals(40, a.trackCount)
        assertEquals(2, a.discCount)
        assertEquals("Digital Media", a.format)
        assertEquals("Album", a.type)
        assertEquals("Frank Klepacki & Someone", found[1].artist)
        assertEquals(1996, found[1].year)
        assertEquals(15, found[1].trackCount) // from the media when the total is missing
    }

    @Test fun parsesAReleasesTracks() {
        val r = MusicBrainz.parseRelease(release)
        assertEquals(4, r.tracks.size)
        val t = r.tracks[2]
        assertEquals(2, t.disc)
        assertEquals(1, t.position)
        assertEquals("Hell March 2", t.title)
        assertEquals("Frank Klepacki feat. Guest", t.artist)
        assertEquals("Frank Klepacki", r.tracks[0].artist) // the release's credit by default
        assertEquals(250_000L, r.tracks[3].lengthMs) // the recording's length when the track has none
    }

    @Test fun matchesSongsByTitleIgnoringNotesAndCase() {
        val r = MusicBrainz.parseRelease(release)
        val songs = listOf(
            song(1, "hell march (Remastered)", durationMs = 201_000, track = 7),
            song(2, "GRINDER", durationMs = 229_000, track = 3),
        )
        val c = MusicBrainz.match(songs, r)
        assertEquals("Hell March", c.tracks.getValue(1).title)
        assertEquals(1, c.tracks.getValue(1).disc)
        assertEquals("Grinder", c.tracks.getValue(2).title)
        assertEquals(2, c.tracks.getValue(2).track)
        assertEquals("Red Alert (25th Anniversary)", c.album)
        assertEquals(2021, c.year)
        assertEquals(1.0, c.score, 1e-9)
    }

    @Test fun placeholderTitlesMatchByPositionAndLength() {
        val r = MusicBrainz.parseRelease(release)
        val songs = listOf(song(1, "track 1", durationMs = 199_500, track = 1, disc = 1))
        assertEquals("Hell March", MusicBrainz.match(songs, r).tracks.getValue(1).title)
    }

    @Test fun eachTrackGoesToOneSongOnly() {
        val r = MusicBrainz.parseRelease(release)
        val songs = listOf(
            song(1, "Hell March", durationMs = 200_000),
            song(2, "Hell March", durationMs = 200_000, path = "/x/copy.mp3"),
        )
        val c = MusicBrainz.match(songs, r)
        val titles = c.tracks.values.map { it.title }
        assertEquals(titles.distinct(), titles)
        assertTrue("Hell March" in titles)
    }

    @Test fun songsNotOnTheReleaseStayUnmatched() {
        val r = MusicBrainz.parseRelease(release)
        val c = MusicBrainz.match(listOf(song(1, "Completely Different Song", durationMs = 60_000, track = 9)), r)
        assertNull(c.tracks[1L])
        assertEquals(0.0, c.score, 1e-9)
    }

    @Test fun placeholderTitles() {
        listOf("track 1", "Track 01", "Faixa 3", "07", "", "Unknown", "untitled 2").forEach {
            assertTrue(it, MusicBrainz.isPlaceholder(it))
        }
        listOf("Hell March", "Track Star", "Last Track").forEach { assertFalse(it, MusicBrainz.isPlaceholder(it)) }
    }

    @Test fun titleSimilarity() {
        assertEquals(1.0, MusicBrainz.titleSimilarity("Café Été", "cafe ete"), 1e-9)
        assertEquals(1.0, MusicBrainz.titleSimilarity("Hell March [2021 Remaster]", "Hell March"), 1e-9)
        assertFalse(MusicBrainz.titleSimilarity("Hell March", "Bigfoot") > 0.5)
    }
}
