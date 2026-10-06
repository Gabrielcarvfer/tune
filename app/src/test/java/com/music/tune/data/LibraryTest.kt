package com.music.tune.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LibraryFolderTest {
    private val primary = "/storage/emulated/0"

    @Test fun folderPickerIdsMapToStoragePaths() {
        val music = LibraryFolder.fromTreeDocumentId("primary:Music/Mine", primary)!!
        assertEquals("/storage/emulated/0/Music/Mine", music.absolutePath)
        assertEquals("Music/Mine", music.label)
        assertTrue(music.isPrimary)

        val sd = LibraryFolder.fromTreeDocumentId("1234-ABCD:Music", primary)!!
        assertEquals("/storage/1234-ABCD/Music", sd.absolutePath)
        assertEquals("Music (SD card)", sd.label)

        val root = LibraryFolder.fromTreeDocumentId("primary:", primary)!!
        assertEquals(primary, root.absolutePath)
        assertEquals("whole storage", root.label)

        assertNull(LibraryFolder.fromTreeDocumentId("not-a-tree-id", primary))
    }

    @Test fun storedPathsRoundTrip() {
        listOf("/storage/emulated/0/Music/Mine", "/storage/emulated/0", "/storage/1234-ABCD/Songs/A B").forEach { p ->
            assertEquals(p, LibraryFolder.fromPath(p, primary)!!.absolutePath)
        }
        assertEquals("Music/Mine", LibraryFolder.fromPath("/storage/emulated/0/Music/Mine/", primary)!!.relativePath)
        assertNull(LibraryFolder.fromPath("/data/local/tmp", primary))
    }

    @Test fun onlyStandardFoldersCanReceiveOrganizedFiles() {
        assertTrue(LibraryFolder(primary, "Music").canOrganizeInto)
        assertTrue(LibraryFolder(primary, "Music/Mine/Lossless").canOrganizeInto)
        assertTrue(LibraryFolder(primary, "Download/Albums").canOrganizeInto)
        assertFalse(LibraryFolder(primary, "MyMusic").canOrganizeInto)
        assertFalse(LibraryFolder(primary, "").canOrganizeInto)
    }

    @Test fun containsIsAFolderPrefixNotAStringPrefix() {
        val f = LibraryFolder(primary, "Music/Mine")
        assertTrue(f.contains("/storage/emulated/0/Music/Mine/a.mp3"))
        assertTrue(f.contains("/storage/emulated/0/Music/Mine/x/y/a.mp3"))
        assertFalse(f.contains("/storage/emulated/0/Music/Mine2/a.mp3"))
    }

    @Test fun organizedFilesNeverLeaveTheLibraryFolder() {
        // No folder: whole phone, organized into Music.
        assertEquals("Music", Organizer.rootFor(null, allFilesAccess = false))
        // A folder MediaStore can move music into: organized in place.
        assertEquals("Music/Mine", Organizer.rootFor(LibraryFolder(primary, "Music/Mine"), allFilesAccess = false))
        // A synced folder outside Music: only with All files access, else never moved.
        val synced = LibraryFolder(primary, "Resilio/Music")
        assertEquals(null, Organizer.rootFor(synced, allFilesAccess = false))
        assertEquals("Resilio/Music", Organizer.rootFor(synced, allFilesAccess = true))
        // SD cards and the storage root are never organized.
        assertEquals(null, Organizer.rootFor(LibraryFolder("/storage/1234-ABCD", "Music"), allFilesAccess = true))
        assertEquals(null, Organizer.rootFor(LibraryFolder(primary, ""), allFilesAccess = true))
    }

    @Test fun organizerTargetsTheLibraryFolder() {
        val t = Organizer.target(song(title = "Song", albumArtist = "Band", album = "Record", track = 3), false, "Music/Mine")
        assertEquals("Music/Mine/Band/Record/", t.relativePath)
        assertEquals("03-Song.mp3", t.name)
    }
}

class AlbumGroupingTest {
    @Test fun oneAlbumSplitAcrossFoldersIsMerged() {
        // MediaStore gives the same album a different id per folder.
        val a = song(id = 1, title = "One", album = "Good", albumArtist = "Saints", track = 1).copy(albumId = 10)
        val b = song(id = 2, title = "Two", album = "Good", albumArtist = "Saints", track = 2).copy(albumId = 20)
        val c = song(id = 3, title = "Three", album = "good", albumArtist = "SAINTS", track = 3).copy(albumId = 10)
        val lib = MediaRepository.buildLibrary(listOf(a, b, c))
        assertEquals(1, lib.albums.size)
        val album = lib.albums.single()
        assertEquals(10L, album.id)
        assertEquals(listOf("One", "Two", "Three"), album.songs.map { it.title })
        // Songs point at the merged album, so "go to album" and history work.
        assertTrue(lib.songs.all { it.albumId == 10L })
        assertEquals(album, lib.album(lib.song(2)!!.albumId))
    }

    @Test fun sameTitleByDifferentArtistsStaysSeparate() {
        val a = song(id = 1, title = "A", album = "Greatest Hits", albumArtist = "Band One").copy(albumId = 1)
        val b = song(id = 2, title = "B", album = "Greatest Hits", albumArtist = "Band Two").copy(albumId = 1)
        val lib = MediaRepository.buildLibrary(listOf(a, b))
        assertEquals(2, lib.albums.size)
        assertEquals(2, lib.artists.size)
    }
}
