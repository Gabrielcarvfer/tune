package com.music.tune.data

import android.content.ContentUris
import android.net.Uri
import android.provider.MediaStore

data class Song(
    val id: Long,
    val title: String,
    val artist: String,
    val album: String,
    val albumArtist: String,
    val albumId: Long,
    val genre: String,
    val year: Int,
    val track: Int,
    val disc: Int,
    val durationMs: Long,
    val path: String,
    val dateAdded: Long,
) {
    val uri: Uri get() = ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id)
    val artUri: Uri get() = albumArtUri(albumId)
}

data class Album(
    val id: Long,
    val title: String,
    val artist: String,
    val year: Int,
    val songs: List<Song>,
) {
    val artUri: Uri get() = albumArtUri(id)
    val durationMs: Long get() = songs.sumOf { it.durationMs }
}

data class Artist(
    val name: String,
    val albums: List<Album>,
    val songs: List<Song>,
)

data class Genre(
    val name: String,
    val songs: List<Song>,
)

data class Playlist(
    val id: String,
    val name: String,
    val songIds: List<Long>,
)

data class Library(
    val songs: List<Song> = emptyList(),
    val albums: List<Album> = emptyList(),
    val artists: List<Artist> = emptyList(),
    val genres: List<Genre> = emptyList(),
    val loaded: Boolean = false,
    /** Changes on every reload, so cached album art gets refreshed after edits. */
    val version: Long = System.nanoTime(),
) {
    private val byId: Map<Long, Song> by lazy { songs.associateBy { it.id } }
    fun song(id: Long): Song? = byId[id]
    fun album(id: Long): Album? = albums.firstOrNull { it.id == id }
    fun artist(name: String): Artist? = artists.firstOrNull { it.name == name }
    fun genre(name: String): Genre? = genres.firstOrNull { it.name == name }
}

const val UNKNOWN = "unknown"

fun albumArtUri(albumId: Long): Uri =
    ContentUris.withAppendedId(Uri.parse("content://media/external/audio/albumart"), albumId)

/** Sort key used by Metro jump lists: ignores a leading "the ". */
fun sortKey(s: String): String = s.lowercase().removePrefix("the ").trim()

/** The jump-list group a name belongs to: a..z, or '#' for everything else. */
fun jumpGroup(s: String): Char {
    val c = sortKey(s).firstOrNull()?.lowercaseChar() ?: return '#'
    return if (c in 'a'..'z') c else '#'
}

fun formatDuration(ms: Long): String {
    val total = (ms / 1000).coerceAtLeast(0)
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}
