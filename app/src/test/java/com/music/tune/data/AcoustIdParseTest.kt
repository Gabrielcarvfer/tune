package com.music.tune.data

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AcoustIdParseTest {

    /** Trimmed real AcoustID v2 response (meta=recordings releasegroups releases tracks). */
    private val response = JSONObject(
        """
        {"status": "ok", "results": [{"id": "9ff43b6a", "score": 0.97, "recordings": [{
          "id": "rec-1", "title": "Teen Angst",
          "artists": [{"id": "a1", "name": "M83"}],
          "releasegroups": [
            {"id": "rg-comp", "title": "Donkey Punch", "type": "Album",
             "artists": [{"id": "va", "name": "Various Artists"}],
             "releases": [{"id": "rel-gb", "country": "GB",
               "date": {"year": 2008, "month": 7, "day": 28},
               "artists": [{"name": "Various Artists", "joinphrase": ", "}, {"name": "François-Eudes Chanfrault"}],
               "medium_count": 2,
               "mediums": [{"format": "CD", "position": 1, "track_count": 16,
                 "tracks": [{"id": "t1", "position": 16, "artists": [{"name": "M83"}]}]}]}]},
            {"id": "rg-album", "title": "Before the Dawn Heals Us", "type": "Album",
             "artists": [{"id": "a1", "name": "M83"}],
             "releases": [
               {"id": "rel-fr", "country": "FR", "date": {"year": 2005}, "medium_count": 1,
                "mediums": [{"format": "Copy Control CD", "position": 1, "track_count": 15,
                  "tracks": [{"id": "t2", "position": 15}]}]},
               {"id": "rel-vinyl", "title": "Before the Dawn Heals Us (Deluxe)", "country": "US",
                "date": {"year": 2014}, "medium_count": 2,
                "mediums": [{"format": "12\" Vinyl", "position": 2, "track_count": 6,
                  "tracks": [{"id": "t3", "position": 6, "title": "Teen Angst (Vinyl)"}]}]}
             ]}
          ]}]}]}
        """.trimIndent(),
    )

    @Test fun flattensEveryReleaseTrack() {
        val m = AcoustId.parse(response)
        assertEquals(3, m.size)
        assertEquals(setOf("rel-gb", "rel-fr", "rel-vinyl"), m.map { it.releaseId }.toSet())
        assertTrue(m.all { it.recordingId == "rec-1" && it.score == 0.97 })
    }

    @Test fun readsTrackPositionDiscAndFormat() {
        val vinyl = AcoustId.parse(response).single { it.releaseId == "rel-vinyl" }
        assertEquals(6, vinyl.track)
        assertEquals(2, vinyl.disc)
        assertEquals(2, vinyl.discCount)
        assertEquals("12\" Vinyl", vinyl.format)
        assertEquals(2014, vinyl.year)
    }

    @Test fun releaseTitleAndTrackTitleOverrideGroupAndRecording() {
        val vinyl = AcoustId.parse(response).single { it.releaseId == "rel-vinyl" }
        assertEquals("Before the Dawn Heals Us (Deluxe)", vinyl.album)
        assertEquals("Teen Angst (Vinyl)", vinyl.title)
        val fr = AcoustId.parse(response).single { it.releaseId == "rel-fr" }
        assertEquals("Before the Dawn Heals Us", fr.album)
        assertEquals("Teen Angst", fr.title)
        assertEquals("M83", fr.albumArtist)
    }

    @Test fun joinsArtistCreditsWithJoinPhrases() {
        val gb = AcoustId.parse(response).single { it.releaseId == "rel-gb" }
        assertEquals("Various Artists, François-Eudes Chanfrault", gb.albumArtist)
        assertEquals("M83", gb.artist)
    }

    @Test fun skipsRecordingsWithoutTitleAndEmptyResults() {
        assertEquals(0, AcoustId.parse(JSONObject("""{"status":"ok","results":[{"id":"x","recordings":[{"id":"r"}]}]}""")).size)
        assertEquals(0, AcoustId.parse(JSONObject("""{"status":"ok","results":[]}""")).size)
        assertEquals(0, AcoustId.parse(JSONObject("""{"status":"ok"}""")).size)
    }

    // --- Picard-style album matching ---------------------------------------

    private fun match(release: String, track: Int, trackCount: Int, type: String = "Album", score: Double = 0.9) = TrackMatch(
        score = score, recordingId = "r$track", title = "T$track", artist = "A", releaseId = release,
        releaseGroupId = "g-$release", album = "Album $release", albumArtist = "A", year = 2000, country = "",
        format = "CD", track = track, trackCount = trackCount, disc = 1, discCount = 1, type = type,
    )

    @Test fun releaseContainingEverySongRanksFirst() {
        val perSong = mapOf(
            1L to listOf(match("full", 1, 3), match("single", 1, 1)),
            2L to listOf(match("full", 2, 3)),
            3L to listOf(match("full", 3, 3), match("compilation", 9, 20, "Compilation")),
        )
        val r = AcoustId.groupByRelease(perSong, 3)
        assertEquals("full", r.first().releaseId)
        assertEquals(3, r.first().tracks.size)
        assertEquals(2, r.first().tracks.getValue(2L).track)
    }

    @Test fun trackCountCloserToAlbumSizeWins() {
        val perSong = mapOf(
            1L to listOf(match("deluxe", 1, 30), match("standard", 1, 2)),
            2L to listOf(match("deluxe", 2, 30), match("standard", 2, 2)),
        )
        assertEquals("standard", AcoustId.groupByRelease(perSong, 2).first().releaseId)
    }

    @Test fun keepsBestScoringMatchPerSongOnARelease() {
        val perSong = mapOf(1L to listOf(match("r", 4, 10, score = 0.5), match("r", 5, 10, score = 0.95)))
        assertEquals(5, AcoustId.groupByRelease(perSong, 1).single().tracks.getValue(1L).track)
    }

    @Test fun noMatchesMeansNoCandidates() {
        assertTrue(AcoustId.groupByRelease(mapOf(1L to emptyList()), 1).isEmpty())
    }
}
