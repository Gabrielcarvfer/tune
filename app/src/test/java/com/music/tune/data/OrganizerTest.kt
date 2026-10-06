package com.music.tune.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OrganizerTest {
    @Test fun buildsArtistAlbumNumberTitlePath() {
        val t = Organizer.target(song(title = "Kenaston", albumArtist = "Chilly Gonzales", album = "Solo Piano II", track = 2,
            path = "/x/02 Kenaston.m4a"), multiDisc = false)
        assertEquals("Music/Chilly Gonzales/Solo Piano II/", t.relativePath)
        assertEquals("02-Kenaston.m4a", t.name)
    }

    @Test fun multiDiscAlbumsGetDiscPrefix() {
        val t = Organizer.target(song(title = "Wax", track = 7, disc = 3), multiDisc = true)
        assertEquals("3-07-Wax.mp3", t.name)
    }

    @Test fun missingTrackNumberUsesTitleOnly() {
        val t = Organizer.target(song(title = "Airwalk", track = 0), multiDisc = false)
        assertEquals("Airwalk.mp3", t.name)
    }

    @Test fun replacesCharactersFilesystemsReject() {
        val t = Organizer.target(song(title = "What? Now: <yes>", albumArtist = "AC/DC", album = "L.A."), false)
        assertEquals("Music/AC_DC/L.A/", t.relativePath) // trailing dot trimmed for FAT/Windows
        assertEquals("01-What_ Now_ _yes_.mp3", t.name)
    }

    @Test fun unknownTagsFallBackToReadableNames() {
        val t = Organizer.target(song(title = UNKNOWN, albumArtist = UNKNOWN, album = ""), false)
        assertEquals("Music/unknown artist/unknown album/", t.relativePath)
        assertEquals("01-unknown.mp3", t.name)
    }

    @Test fun extensionIsLowercased() {
        assertEquals("01-A.flac", Organizer.target(song(title = "A", path = "/x/A.FLAC"), false).name)
    }

    @Test fun recognisesAlreadyOrganizedFiles() {
        val s = song(title = "Airwalk", albumArtist = "Saints", album = "Good", track = 8,
            path = "/storage/emulated/0/Music/Saints/Good/08-Airwalk.mp3")
        assertTrue(Organizer.isOrganized(s, Organizer.target(s, false)))
        assertFalse(Organizer.isOrganized(s.copy(path = "/storage/emulated/0/Music/in/8. Airwalk.mp3"), Organizer.target(s, false)))
    }

    @Test fun numberedCopiesFromNameClashesCountAsOrganized() {
        val s = song(title = "Airwalk", albumArtist = "Saints", album = "Good", track = 0,
            path = "/storage/emulated/0/Music/Saints/Good/Airwalk (2).mp3")
        val t = Organizer.target(s, false)
        assertTrue(Organizer.isOrganized(s, t))
        assertFalse(Organizer.isOrganized(s.copy(path = "/storage/emulated/0/Music/Saints/Good/Airwalk (2) remix.mp3"), t))
        assertFalse(Organizer.isOrganized(s.copy(path = "/storage/emulated/0/Music/Other/Good/Airwalk.mp3"), t))
    }

    @Test fun leadingDotsDontMakeHiddenFolders() {
        // Android's media scanner skips hidden folders, so the song would vanish.
        val t = Organizer.target(song(title = "Take Good Care", albumArtist = "Le Flex", album = "... To Be Continued", track = 6), false)
        assertEquals("Music/Le Flex/_.. To Be Continued/", t.relativePath)
        assertEquals("_38 Special", Organizer.clean(".38 Special", "x"))
        assertEquals("06-_hidden.mp3", Organizer.target(song(title = ".hidden", track = 6), false).name)
    }

    @Test fun cleanTruncatesVeryLongNames() {
        assertEquals(100, Organizer.clean("x".repeat(300), "f").length)
    }
}
