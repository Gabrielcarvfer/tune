package com.tune.music.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.URLEncoder

/** One way a recording appears on a MusicBrainz release. */
data class TrackMatch(
    val score: Double,
    val recordingId: String,
    val title: String,
    val artist: String,
    val releaseId: String,
    val releaseGroupId: String,
    val album: String,
    val albumArtist: String,
    val year: Int,
    val country: String,
    val format: String,
    val track: Int,
    val trackCount: Int,
    val disc: Int,
    val discCount: Int,
    val type: String,
) {
    val coverThumb: String get() = "https://coverartarchive.org/release/$releaseId/front-250"
}

/** A release proposed for a group of songs (Picard-style album matching). */
data class ReleaseCandidate(
    val releaseId: String,
    val releaseGroupId: String,
    val album: String,
    val albumArtist: String,
    val year: Int,
    val country: String,
    val format: String,
    val type: String,
    val trackCount: Int,
    val discCount: Int,
    /** Matched track for each song id that was found on this release. */
    val tracks: Map<Long, TrackMatch>,
    val score: Double,
) {
    val coverThumb: String get() = "https://coverartarchive.org/release/$releaseId/front-250"
}

data class CoverImage(val thumb: String, val full: String, val types: List<String>, val front: Boolean)

/**
 * Identifies songs the way Picard does: Chromaprint fingerprint -> AcoustID ->
 * MusicBrainz recordings and releases, then cover art from the Cover Art Archive.
 */
class AcoustId(private val context: Context, val cache: MatchCache) {

    /**
     * All releases each song appears on, best first. Saved answers are reused;
     * otherwise the song is fingerprinted (once) and looked up, and the answer saved.
     */
    suspend fun identify(song: Song, apiKey: String): List<TrackMatch> = parse(lookupSong(song, apiKey))

    /** The AcoustID answer for [song], from the cache or the web service. */
    suspend fun lookupSong(song: Song, apiKey: String): JSONObject {
        var answer: Result<JSONObject>? = null
        lookupSongs(listOf(song), apiKey) { _, r -> answer = r }
        return answer!!.getOrThrow()
    }

    /** What preparing a song gave: a saved answer, or a fingerprint to look up. */
    private sealed interface Prepared {
        class Answered(val lookup: JSONObject) : Prepared
        class Fingerprinted(val fingerprint: String) : Prepared
    }

    /**
     * AcoustID answers for [songs], saved ones reused. Fingerprinting (decoding
     * and Chromaprint, the slow part) runs on several cores, and each song is
     * looked up as soon as its fingerprint is ready, one request at a time
     * within AcoustID's rate limit. [onResult] gets each song's answer or error
     * as it comes, in no particular order. Throwing from it stops everything.
     */
    suspend fun lookupSongs(
        songs: List<Song>,
        apiKey: String,
        onResult: suspend (Song, Result<JSONObject>) -> Unit,
    ) {
        pipeline(
            songs, PARALLEL_FINGERPRINTS, Dispatchers.Default,
            prepare = { song ->
                val cached = withContext(Dispatchers.IO) { cache.get(song) }
                when {
                    cached?.lookup != null -> Prepared.Answered(cached.lookup)
                    cached?.fingerprint != null -> Prepared.Fingerprinted(cached.fingerprint)
                    else -> {
                        val fp = Chromaprint.fingerprint(context, song.uri)
                        withContext(Dispatchers.IO) { cache.put(song, fp, null) }
                        Prepared.Fingerprinted(fp)
                    }
                }
            },
            finish = { song, prepared ->
                val result = prepared.mapCatching { p ->
                    when (p) {
                        is Prepared.Answered -> p.lookup
                        is Prepared.Fingerprinted -> {
                            val json = lookup(apiKey, p.fingerprint, (song.durationMs / 1000).toInt())
                            withContext(Dispatchers.IO) { cache.put(song, p.fingerprint, json) }
                            json
                        }
                    }
                }
                // (mapCatching would also catch a cancellation; let it through.)
                result.exceptionOrNull()?.let { if (it is kotlinx.coroutines.CancellationException) throw it }
                onResult(song, result)
            },
        )
    }

    /**
     * Identifies every song and groups the matches by release, ranking releases
     * by how many of the songs they contain and how well their size fits.
     */
    suspend fun identifyAlbum(
        songs: List<Song>,
        apiKey: String,
        progress: (done: Int, total: Int) -> Unit,
    ): List<ReleaseCandidate> {
        val perSong = LinkedHashMap<Long, List<TrackMatch>>()
        songs.forEach { perSong[it.id] = emptyList() }
        var firstError: Throwable? = null
        var done = 0
        progress(0, songs.size)
        lookupSongs(songs, apiKey) { s, r ->
            r.onSuccess { perSong[s.id] = parse(it) }.onFailure { firstError = firstError ?: it }
            progress(++done, songs.size)
        }
        // One bad file shouldn't sink an album, but a bad key or no network should be reported.
        firstError?.let { e -> if (perSong.values.all { it.isEmpty() }) throw e }
        return groupByRelease(perSong, songs.size)
    }

    /** Every image the Cover Art Archive has for a release, front covers first. */
    suspend fun covers(releaseId: String, releaseGroupId: String): List<CoverImage> = withContext(Dispatchers.IO) {
        val out = ArrayList<CoverImage>()
        for (url in listOf("https://coverartarchive.org/release/$releaseId", "https://coverartarchive.org/release-group/$releaseGroupId")) {
            val body = runCatching { Net.get(url).decodeToString() }.getOrNull() ?: continue
            val images = runCatching { JSONObject(body).optJSONArray("images") }.getOrNull() ?: continue
            for (i in 0 until images.length()) {
                val img = images.getJSONObject(i)
                val th = img.optJSONObject("thumbnails")
                val full = img.optString("image").replace("http://", "https://")
                val thumb = (th?.optString("250")?.takeIf { it.isNotEmpty() } ?: th?.optString("small") ?: full)
                    .replace("http://", "https://")
                val large = (th?.optString("1200")?.takeIf { it.isNotEmpty() } ?: th?.optString("500")?.takeIf { it.isNotEmpty() } ?: full)
                    .replace("http://", "https://")
                val types = img.optJSONArray("types")?.let { t -> List(t.length()) { t.getString(it) } }.orEmpty()
                if (out.none { it.full == large }) out += CoverImage(thumb, large, types, img.optBoolean("front"))
            }
            if (out.isNotEmpty()) break
        }
        out.sortedByDescending { it.front }
    }

    suspend fun download(url: String): ByteArray = withContext(Dispatchers.IO) { Net.get(url) }

    // --- HTTP ---------------------------------------------------------------

    // (Rate limits are kept by the HTTP layer; see Throttle.)
    private suspend fun lookup(apiKey: String, fingerprint: String, duration: Int): JSONObject = withContext(Dispatchers.IO) {
        val form = listOf(
            "client" to apiKey,
            "format" to "json",
            "duration" to duration.toString(),
            "fingerprint" to fingerprint,
            "meta" to "recordings releasegroups releases tracks compress",
        ).joinToString("&") { (k, v) -> "$k=" + URLEncoder.encode(v, "UTF-8") }

        val json = JSONObject(Net.postForm(LOOKUP_URL, form))
        if (json.optString("status") != "ok") {
            val msg = json.optJSONObject("error")?.optString("message") ?: "lookup failed"
            throw IOException("acoustid: $msg")
        }
        json
    }

    // --- parsing ------------------------------------------------------------

    companion object {
        fun groupByRelease(perSong: Map<Long, List<TrackMatch>>, songCount: Int): List<ReleaseCandidate> {
            val byRelease = HashMap<String, HashMap<Long, TrackMatch>>()
            perSong.forEach { (songId, matches) ->
                matches.forEach { m ->
                    val slot = byRelease.getOrPut(m.releaseId) { HashMap() }
                    val prev = slot[songId]
                    if (prev == null || m.score > prev.score) slot[songId] = m
                }
            }
            return byRelease.map { (rid, tracks) ->
                val any = tracks.values.first()
                val coverage = tracks.size.toDouble() / songCount
                val fit = if (any.trackCount > 0) minOf(songCount, any.trackCount).toDouble() / maxOf(songCount, any.trackCount) else 0.5
                val quality = tracks.values.map { it.score }.average()
                // Prefer official albums over compilations, like Picard's release type weights.
                val typeBonus = if (any.type.equals("album", true)) 0.05 else 0.0
                ReleaseCandidate(
                    releaseId = rid,
                    releaseGroupId = any.releaseGroupId,
                    album = any.album,
                    albumArtist = any.albumArtist,
                    year = any.year,
                    country = any.country,
                    format = any.format,
                    type = any.type,
                    trackCount = any.trackCount,
                    discCount = any.discCount,
                    tracks = tracks,
                    score = coverage * 0.6 + fit * 0.25 + quality * 0.1 + typeBonus,
                )
            }.sortedByDescending { it.score }
        }

        const val LOOKUP_URL = "https://api.acoustid.org/v2/lookup"

        /** Songs fingerprinted at once: the spare cores, at most 4. */
        val PARALLEL_FINGERPRINTS = (Runtime.getRuntime().availableProcessors() - 1).coerceIn(1, 4)

        fun parse(json: JSONObject): List<TrackMatch> {
            val out = ArrayList<TrackMatch>()
            val results = json.optJSONArray("results") ?: return out
            for (r in results.objects()) {
                val score = r.optDouble("score", 1.0)
                for (rec in r.optJSONArray("recordings").objects()) {
                    val recTitle = rec.optString("title")
                    val recArtist = artists(rec.optJSONArray("artists"))
                    if (recTitle.isEmpty()) continue
                    for (rg in rec.optJSONArray("releasegroups").objects()) {
                        val rgTitle = rg.optString("title")
                        val rgArtist = artists(rg.optJSONArray("artists")).ifEmpty { recArtist }
                        val type = rg.optString("type")
                        for (rel in rg.optJSONArray("releases").objects()) {
                            val date = rel.optJSONObject("date")
                            for (medium in rel.optJSONArray("mediums").objects()) {
                                for (t in medium.optJSONArray("tracks").objects()) {
                                    out += TrackMatch(
                                        score = score,
                                        recordingId = rec.optString("id"),
                                        title = t.optString("title").ifEmpty { recTitle },
                                        artist = artists(t.optJSONArray("artists")).ifEmpty { recArtist },
                                        releaseId = rel.optString("id"),
                                        releaseGroupId = rg.optString("id"),
                                        album = rel.optString("title").ifEmpty { rgTitle },
                                        albumArtist = artists(rel.optJSONArray("artists")).ifEmpty { rgArtist },
                                        year = date?.optInt("year") ?: 0,
                                        country = rel.optString("country"),
                                        format = medium.optString("format"),
                                        track = t.optInt("position"),
                                        trackCount = medium.optInt("track_count"),
                                        disc = medium.optInt("position", 1),
                                        discCount = rel.optInt("medium_count", 1),
                                        type = type,
                                    )
                                }
                            }
                        }
                    }
                }
            }
            return out.sortedWith(compareByDescending<TrackMatch> { it.score }.thenBy { if (it.type.equals("album", true)) 0 else 1 }.thenBy { it.year.takeIf { y -> y > 0 } ?: 9999 })
        }

        /** Joins an artist credit list using MusicBrainz join phrases. */
        private fun artists(arr: JSONArray?): String {
            if (arr == null) return ""
            val sb = StringBuilder()
            for (i in 0 until arr.length()) {
                val a = arr.getJSONObject(i)
                sb.append(a.optString("name"))
                sb.append(if (a.has("joinphrase")) a.optString("joinphrase") else if (i < arr.length() - 1) ", " else "")
            }
            return sb.toString().trim()
        }

        private fun JSONArray?.objects(): List<JSONObject> =
            if (this == null) emptyList() else List(length()) { getJSONObject(it) }
    }
}
