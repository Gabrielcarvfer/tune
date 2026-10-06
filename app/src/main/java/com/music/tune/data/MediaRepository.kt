package com.music.tune.data

import android.content.Context
import android.os.Build
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Reads the device music collection from MediaStore. */
class MediaRepository(private val context: Context) {

    /** Loads the songs inside [folder] (and its subfolders), or the whole device if null. */
    suspend fun load(folder: String? = null): Library = withContext(Dispatchers.IO) {
        val songs = querySongs(folder)
        buildLibrary(songs)
    }

    private fun querySongs(folder: String?): List<Song> {
        val genres = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) emptyMap() else queryLegacyGenres()
        val projection = buildList {
            add(MediaStore.Audio.Media._ID)
            add(MediaStore.Audio.Media.TITLE)
            add(MediaStore.Audio.Media.ARTIST)
            add(MediaStore.Audio.Media.ALBUM)
            add(MediaStore.Audio.Media.ALBUM_ID)
            add(MediaStore.Audio.Media.YEAR)
            add(MediaStore.Audio.Media.TRACK)
            add(MediaStore.Audio.Media.DURATION)
            add(MediaStore.Audio.Media.DATA)
            add(MediaStore.Audio.Media.DATE_ADDED)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                add(MediaStore.Audio.Media.ALBUM_ARTIST)
                add(MediaStore.Audio.Media.GENRE)
            }
        }.toTypedArray()

        val out = ArrayList<Song>()
        context.contentResolver.query(
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
            projection,
            "${MediaStore.Audio.Media.IS_MUSIC} != 0" +
                if (folder != null) " AND ${MediaStore.Audio.Media.DATA} LIKE ? ESCAPE '\\'" else "",
            if (folder != null) arrayOf(escapeLike(folder.trimEnd('/')) + "/%") else null,
            null,
        )?.use { c ->
            val iId = c.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
            val iTitle = c.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
            val iArtist = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
            val iAlbum = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM)
            val iAlbumId = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM_ID)
            val iYear = c.getColumnIndexOrThrow(MediaStore.Audio.Media.YEAR)
            val iTrack = c.getColumnIndexOrThrow(MediaStore.Audio.Media.TRACK)
            val iDur = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
            val iData = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DATA)
            val iAdded = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DATE_ADDED)
            val iAlbumArtist = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R)
                c.getColumnIndex(MediaStore.Audio.Media.ALBUM_ARTIST) else -1
            val iGenre = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R)
                c.getColumnIndex(MediaStore.Audio.Media.GENRE) else -1

            while (c.moveToNext()) {
                val id = c.getLong(iId)
                val artist = c.getString(iArtist).clean()
                val rawTrack = c.getInt(iTrack)
                out += Song(
                    id = id,
                    title = c.getString(iTitle).clean(),
                    artist = artist,
                    album = c.getString(iAlbum).clean(),
                    albumArtist = (if (iAlbumArtist >= 0) c.getString(iAlbumArtist) else null)
                        ?.takeIf { it.isNotBlank() } ?: artist,
                    albumId = c.getLong(iAlbumId),
                    genre = (if (iGenre >= 0) c.getString(iGenre) else genres[id]).clean(),
                    year = c.getInt(iYear),
                    // MediaStore encodes disc * 1000 + track
                    track = rawTrack % 1000,
                    disc = (rawTrack / 1000).coerceAtLeast(1),
                    durationMs = c.getLong(iDur),
                    path = c.getString(iData) ?: "",
                    dateAdded = c.getLong(iAdded),
                )
            }
        }
        return out
    }

    @Suppress("DEPRECATION")
    private fun queryLegacyGenres(): Map<Long, String> {
        val map = HashMap<Long, String>()
        val cr = context.contentResolver
        cr.query(
            MediaStore.Audio.Genres.EXTERNAL_CONTENT_URI,
            arrayOf(MediaStore.Audio.Genres._ID, MediaStore.Audio.Genres.NAME),
            null, null, null,
        )?.use { g ->
            while (g.moveToNext()) {
                val genreId = g.getLong(0)
                val name = g.getString(1) ?: continue
                cr.query(
                    MediaStore.Audio.Genres.Members.getContentUri("external", genreId),
                    arrayOf(MediaStore.Audio.Genres.Members.AUDIO_ID),
                    null, null, null,
                )?.use { m -> while (m.moveToNext()) map[m.getLong(0)] = name }
            }
        }
        return map
    }

    private fun escapeLike(s: String) = s.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")

    private fun String?.clean(): String =
        if (this.isNullOrBlank() || this == "<unknown>") UNKNOWN else this

    companion object {
        /**
         * Groups songs into albums by album artist + title (not MediaStore's
         * album id, which differs per folder), then artists and genres.
         */
        fun buildLibrary(input: List<Song>): Library {
            // One id per album: the most common MediaStore album id among its songs,
            // unless another album already took it (same title, same folder, other
            // artist); then a synthetic negative id (art falls back to embedded pictures).
            val groups = input.groupBy { it.albumArtist.lowercase() + "\u0000" + it.album.lowercase() }
            val used = HashSet<Long>()
            val songs = groups.entries.sortedBy { it.key }.flatMap { (key, group) ->
                var id = group.groupingBy { it.albumId }.eachCount().maxBy { it.value }.key
                if (!used.add(id)) {
                    id = -1L - (key.hashCode().toLong() and 0x7fffffff)
                    while (!used.add(id)) id--
                }
                group.map { if (it.albumId == id) it else it.copy(albumId = id) }
            }
            val sortedSongs = songs.sortedBy { sortKey(it.title) }
            val albums = songs.groupBy { it.albumId }.map { (id, s) ->
                val tracks = s.sortedWith(compareBy({ it.disc }, { it.track }, { it.title }))
                Album(
                    id = id,
                    title = tracks.first().album,
                    artist = tracks.groupingBy { it.albumArtist }.eachCount().maxBy { it.value }.key,
                    year = tracks.maxOf { it.year },
                    songs = tracks,
                )
            }.sortedBy { sortKey(it.title) }

            val artists = songs.groupBy { it.albumArtist }.map { (name, s) ->
                Artist(
                    name = name,
                    albums = albums.filter { a -> a.artist == name }.sortedByDescending { it.year },
                    songs = s.sortedBy { sortKey(it.title) },
                )
            }.sortedBy { sortKey(it.name) }

            val genres = songs.groupBy { it.genre }.map { (name, s) ->
                Genre(name, s.sortedBy { sortKey(it.title) })
            }.sortedBy { sortKey(it.name) }

            return Library(sortedSongs, albums, artists, genres, loaded = true)
        }
    }
}
