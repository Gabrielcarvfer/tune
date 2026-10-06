package com.music.tune.data

import org.json.JSONObject
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * Remembers each song's Chromaprint fingerprint and AcoustID answer, so songs
 * are fingerprinted and looked up once, not every time. One small file per
 * song in app-private storage, keyed by the MediaStore id: tag edits and
 * organizing keep the id and don't change the audio. An entry whose length no
 * longer matches the song is ignored.
 */
class MatchCache(private val dir: File) {

    class Entry(val fingerprint: String?, val lookup: JSONObject?)

    private fun file(id: Long) = File(dir, "$id.json")

    @Synchronized
    fun get(song: Song): Entry? {
        val f = file(song.id)
        if (!f.isFile) return null
        val json = runCatching { JSONObject(f.readText()) }.getOrNull() ?: return null
        if (json.optLong("duration") != song.durationMs) return null
        return Entry(json.optString("fingerprint").ifEmpty { null }, json.optJSONObject("lookup"))
    }

    /** The cached AcoustID answer for [song], if it has one. */
    fun lookup(song: Song): JSONObject? = get(song)?.lookup

    @Synchronized
    fun put(song: Song, fingerprint: String?, lookup: JSONObject?) {
        dir.mkdirs()
        val prev = get(song)
        val json = JSONObject()
            .put("duration", song.durationMs)
            .put("fingerprint", fingerprint ?: prev?.fingerprint)
            .put("lookup", lookup ?: prev?.lookup)
            .put("time", System.currentTimeMillis())
        val tmp = File(dir, "${song.id}.tmp")
        tmp.writeText(json.toString())
        Files.move(tmp.toPath(), file(song.id).toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        version++
    }

    /** How many of [songs] have an AcoustID answer saved. */
    fun countLooked(songs: List<Song>): Int = songs.count { lookup(it) != null }

    /** Every saved entry by song id (for exporting settings). */
    @Synchronized
    fun all(): Map<String, JSONObject> =
        dir.listFiles { f -> f.name.endsWith(".json") }.orEmpty().mapNotNull { f ->
            runCatching { f.name.removeSuffix(".json") to JSONObject(f.readText()) }.getOrNull()
        }.toMap()

    /** Replaces every entry with [entries] (from an export). */
    @Synchronized
    fun restore(entries: Map<String, JSONObject>) {
        dir.listFiles()?.forEach { it.delete() }
        dir.mkdirs()
        entries.forEach { (id, e) -> if (id.toLongOrNull() != null) File(dir, "$id.json").writeText(e.toString()) }
        version++
    }

    @Synchronized
    fun clear() {
        dir.listFiles()?.forEach { it.delete() }
        version++
    }

    /** Bumped on every change, so callers can tell when derived data is stale. */
    @Volatile
    var version = 0
        private set
}
