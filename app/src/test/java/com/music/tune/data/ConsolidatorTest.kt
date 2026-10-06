package com.music.tune.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ConsolidatorTest {

    private fun m(release: String, title: String, track: Int, trackCount: Int, score: Double = 0.9, disc: Int = 1, type: String = "Album") =
        TrackMatch(
            score = score, recordingId = "rec-$title", title = title, artist = "Frank Klepacki",
            releaseId = release, releaseGroupId = "rg-$release", album = "album $release", albumArtist = "Frank Klepacki",
            year = 2021, country = "XW", format = "Digital Media", track = track, trackCount = trackCount,
            disc = disc, discCount = 1, type = type,
        )

    // Three songs that were each matched to their own original soundtrack, so
    // they sit in three albums; all three are on the 25th anniversary release.
    private val songs = listOf(
        song(1, "Hell March", album = "Red Alert", albumId = 10),
        song(2, "Grinder", album = "Counterstrike", albumId = 20),
        song(3, "Bigfoot", album = "Aftermath", albumId = 30),
        song(4, "Unrelated", album = "Other", albumId = 40),
    )
    private val titles = mapOf(10L to "Red Alert", 20L to "Counterstrike", 30L to "Aftermath", 40L to "Other", 50L to "Twin")

    private fun propose(matches: Map<Long, List<TrackMatch>>, s: List<Song> = songs) =
        Consolidator.propose(s, matches, { it.albumId }, { titles.getValue(it) })

    @Test fun songsSplitAcrossAlbumsGoToTheReleaseCoveringThemAll() {
        val p = propose(
            mapOf(
                // Each song's own lookup ranks its original soundtrack first (higher score).
                1L to listOf(m("ost-ra", "Hell March", 1, 15, 0.99), m("anniv", "Hell March", 1, 3, 0.8)),
                2L to listOf(m("ost-cs", "Grinder", 1, 10, 0.99), m("anniv", "Grinder", 2, 3, 0.8)),
                3L to listOf(m("ost-am", "Bigfoot", 1, 10, 0.99), m("anniv", "Bigfoot", 3, 3, 0.8)),
            ),
        )
        assertEquals(1, p.size)
        assertEquals("anniv", p[0].release.releaseId)
        assertEquals(listOf(1L, 2L, 3L), p[0].songs.map { it.id }) // in the release's track order
        assertEquals(listOf("Red Alert", "Counterstrike", "Aftermath"), p[0].fromAlbums)
        assertEquals(3, p[0].release.tracks.size)
    }

    @Test fun theBiggestReleaseWinsThenTheRestIsConsidered() {
        val more = songs + listOf(
            song(5, "Mud", album = "Red Alert", albumId = 10),
            song(6, "Twin", album = "Twin", albumId = 50),
        )
        val p = propose(
            mapOf(
                1L to listOf(m("anniv", "Hell March", 1, 4), m("small", "Hell March", 1, 2)),
                2L to listOf(m("anniv", "Grinder", 2, 4), m("small", "Grinder", 2, 2)),
                3L to listOf(m("anniv", "Bigfoot", 3, 4)),
                5L to listOf(m("anniv", "Mud", 4, 4)),
                4L to listOf(m("pair", "Unrelated", 1, 2)),
                6L to listOf(m("pair", "Twin", 2, 2)),
            ),
            more,
        )
        // "small" (2 songs) loses its songs to "anniv" (4); the separate pair is proposed too.
        assertEquals(listOf("anniv", "pair"), p.map { it.release.releaseId })
        assertEquals(4, p[0].songs.size)
        assertEquals(2, p[1].songs.size)
    }

    @Test fun aReleaseWithinOneAlbumIsNotProposed() {
        val one = listOf(
            song(1, "A", album = "Same", albumId = 10),
            song(2, "B", album = "Same", albumId = 10),
        )
        val p = Consolidator.propose(one, mapOf(1L to listOf(m("r", "A", 1, 2)), 2L to listOf(m("r", "B", 2, 2))), { it.albumId }, { "Same" })
        assertTrue(p.isEmpty())
    }

    @Test fun aSingleSongIsNotAMerge() {
        val p = propose(mapOf(1L to listOf(m("r", "Hell March", 1, 10))))
        assertTrue(p.isEmpty())
    }

    @Test fun onEqualCoverageAnAlbumBeatsACompilation() {
        val p = propose(
            mapOf(
                1L to listOf(m("comp", "Hell March", 1, 20, type = "Compilation"), m("album", "Hell March", 1, 2)),
                2L to listOf(m("comp", "Grinder", 2, 20, type = "Compilation"), m("album", "Grinder", 2, 2)),
            ),
        )
        assertEquals("album", p.single().release.releaseId)
    }

    @Test fun songsNotInTheCollectionAreIgnored() {
        val p = propose(
            mapOf(
                1L to listOf(m("r", "Hell March", 1, 2)),
                99L to listOf(m("r", "Ghost", 2, 2)),
            ),
        )
        assertTrue(p.isEmpty())
    }
}
