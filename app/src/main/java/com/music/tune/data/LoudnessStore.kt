package com.music.tune.data

import android.content.Context
import android.net.Uri
import org.json.JSONObject
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * Each song's measured loudness, so it's measured once. One small JSON file in
 * app-private storage (the music files are never changed), keyed by the
 * MediaStore id like the saved fingerprints; an entry whose length no longer
 * matches the song is measured again.
 */
class LoudnessStore(private val file: File) {
    private class Entry(val durationMs: Long, val loudness: Loudness)

    private var entries: HashMap<Long, Entry>? = null
    private var unsaved = 0

    @Synchronized
    private fun all(): HashMap<Long, Entry> = entries ?: HashMap<Long, Entry>().also { map ->
        runCatching {
            val json = JSONObject(file.readText())
            for (key in json.keys()) {
                val e = json.getJSONObject(key)
                val lufs = if (e.isNull("l")) Double.NEGATIVE_INFINITY else e.getDouble("l")
                map[key.toLong()] = Entry(e.getLong("d"), Loudness(lufs, e.getDouble("p")))
            }
        }
        entries = map
    }

    /** The loudness saved for a song id (for the player, which only knows ids). */
    @Synchronized
    operator fun get(id: Long): Loudness? = all()[id]?.loudness

    /** The loudness saved for [song], if it's still for this recording. */
    @Synchronized
    fun get(song: Song): Loudness? = all()[song.id]?.takeIf { it.durationMs == song.durationMs }?.loudness

    @Synchronized
    fun put(id: Long, durationMs: Long, loudness: Loudness) {
        all()[id] = Entry(durationMs, loudness)
        // Saved every few songs (and on [flush]) rather than after each one.
        if (++unsaved >= 20) flush()
    }

    fun put(song: Song, loudness: Loudness) = put(song.id, song.durationMs, loudness)

    @Synchronized
    fun count(songs: List<Song>): Int = songs.count { get(it) != null }

    @Synchronized
    fun flush() {
        val map = entries ?: return
        unsaved = 0
        val json = JSONObject()
        map.forEach { (id, e) ->
            json.put(id.toString(), JSONObject()
                .put("d", e.durationMs)
                .put("l", if (e.loudness.lufs.isFinite()) e.loudness.lufs else JSONObject.NULL)
                .put("p", e.loudness.peak))
        }
        file.parentFile?.mkdirs()
        val tmp = File(file.path + ".tmp")
        tmp.writeText(json.toString())
        Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    }

    @Synchronized
    fun clear() {
        entries = HashMap()
        unsaved = 0
        file.delete()
    }

    companion object {
        /** Decodes the whole song and measures it. */
        suspend fun measure(context: Context, uri: Uri): Loudness {
            var meter: LoudnessMeter? = null
            AudioDecoder.decode(context, uri, sink = object : AudioDecoder.Sink {
                override fun start(sampleRate: Int, channels: Int) {
                    meter = LoudnessMeter(sampleRate, channels)
                }

                override fun feed(samples: ShortArray, count: Int) {
                    meter!!.feed(samples, count)
                }
            })
            return meter!!.result()
        }
    }
}
