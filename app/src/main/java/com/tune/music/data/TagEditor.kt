package com.tune.music.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaScannerConnection
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import kotlin.coroutines.resume

/** The editable tag fields. A null field means "leave unchanged". */
data class TagEdit(
    val title: String? = null,
    val artist: String? = null,
    val album: String? = null,
    val albumArtist: String? = null,
    val genre: String? = null,
    val year: String? = null,
    val track: String? = null,
    val disc: String? = null,
    /** Raw image bytes (jpeg/png) to embed as front cover. */
    val artwork: ByteArray? = null,
    val artworkMime: String? = null,
)

/** Tag values as read from the file. */
data class TagValues(
    val title: String = "",
    val artist: String = "",
    val album: String = "",
    val albumArtist: String = "",
    val genre: String = "",
    val year: String = "",
    val track: String = "",
    val disc: String = "",
)

/**
 * Reads and writes tags inside the audio files using TagLib. Since Android
 * scoped storage gives no direct File access, the song is copied to the cache,
 * edited there, then streamed back through its content Uri. The caller must
 * already hold write access to the Uri (see [MainViewModel] write requests).
 */
class TagEditor(private val context: Context) {

    suspend fun read(song: Song): TagValues = withContext(Dispatchers.IO) {
        val tmp = runCatching { copyToCache(song) }.getOrElse { return@withContext fallback(song) }
        try {
            val v = TagLib.nativeRead(tmp.absolutePath) ?: return@withContext fallback(song)
            TagValues(
                title = v[TagLib.TITLE],
                artist = v[TagLib.ARTIST],
                album = v[TagLib.ALBUM],
                albumArtist = v[TagLib.ALBUM_ARTIST],
                genre = v[TagLib.GENRE],
                year = v[TagLib.DATE],
                track = v[TagLib.TRACK],
                disc = v[TagLib.DISC],
            )
        } finally {
            tmp.delete()
        }
    }

    private fun fallback(s: Song) = TagValues(
        title = s.title, artist = s.artist, album = s.album, albumArtist = s.albumArtist,
        genre = s.genre.takeIf { it != UNKNOWN }.orEmpty(),
        year = if (s.year > 0) s.year.toString() else "",
        track = if (s.track > 0) s.track.toString() else "",
        disc = s.disc.toString(),
    )

    /** Writes [edit] into the song file. Throws on failure (incl. SecurityException). */
    suspend fun write(song: Song, edit: TagEdit) = withContext(Dispatchers.IO) {
        val tmp = copyToCache(song)
        try {
            val values = arrayOfNulls<String>(TagLib.FIELD_COUNT)
            values[TagLib.TITLE] = edit.title?.trim()
            values[TagLib.ARTIST] = edit.artist?.trim()
            values[TagLib.ALBUM] = edit.album?.trim()
            values[TagLib.ALBUM_ARTIST] = edit.albumArtist?.trim()
            values[TagLib.GENRE] = edit.genre?.trim()
            values[TagLib.DATE] = edit.year?.trim()
            values[TagLib.TRACK] = edit.track?.trim()
            values[TagLib.DISC] = edit.disc?.trim()
            if (!TagLib.nativeWrite(tmp.absolutePath, values, edit.artwork, edit.artworkMime)) {
                throw IOException("TagLib couldn't save ${song.path}")
            }
            context.contentResolver.openOutputStream(song.uri, "wt")?.use { out ->
                tmp.inputStream().use { it.copyTo(out) }
            } ?: throw IOException("cannot open ${song.uri} for writing")
        } finally {
            tmp.delete()
        }
        rescan(listOf(song.path))
    }

    /** Asks MediaStore to re-read the files so the library picks up new tags. */
    suspend fun rescan(paths: List<String>) {
        val valid = paths.filter { it.isNotEmpty() }
        if (valid.isEmpty()) return
        suspendCancellableCoroutine { cont ->
            var remaining = valid.size
            MediaScannerConnection.scanFile(context, valid.toTypedArray(), null) { _, _ ->
                synchronized(this) {
                    remaining--
                    if (remaining == 0 && cont.isActive) cont.resume(Unit)
                }
            }
        }
    }

    private fun copyToCache(song: Song): File {
        // TagLib picks the format from the extension.
        val ext = song.path.substringAfterLast('.', "mp3").lowercase()
        val tmp = File.createTempFile("tag_", ".$ext", context.cacheDir)
        context.contentResolver.openInputStream(song.uri)?.use { input ->
            tmp.outputStream().use { input.copyTo(it) }
        } ?: throw IOException("cannot read ${song.uri}")
        return tmp
    }

    /**
     * Loads an image picked by the user for use as cover art. Images larger
     * than [MAX_COVER] px are scaled down and re-encoded as JPEG, like Picard.
     */
    fun readImage(uri: Uri): Pair<ByteArray, String>? = runCatching {
        val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: return null
        prepareCover(bytes, context.contentResolver.getType(uri))
    }.getOrNull()

    companion object {
        const val MAX_COVER = 1200

        fun prepareCover(bytes: ByteArray, mime: String?): Pair<ByteArray, String> {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            val big = maxOf(bounds.outWidth, bounds.outHeight)
            val okType = mime == "image/jpeg" || mime == "image/png"
            if (big in 1..MAX_COVER && okType) return bytes to mime!!

            var sample = 1
            while (big / (sample * 2) >= MAX_COVER) sample *= 2
            val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
                ?: return bytes to (mime ?: "image/jpeg")
            val scale = MAX_COVER.toFloat() / maxOf(decoded.width, decoded.height)
            val bmp = if (scale < 1f) {
                Bitmap.createScaledBitmap(decoded, (decoded.width * scale).toInt(), (decoded.height * scale).toInt(), true)
            } else decoded
            val out = ByteArrayOutputStream()
            bmp.compress(Bitmap.CompressFormat.JPEG, 90, out)
            return out.toByteArray() to "image/jpeg"
        }
    }
}
