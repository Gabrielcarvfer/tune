package com.tune.music.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
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
class AcoustId(private val context: Context) {

    private var lastCall = 0L

    /** All releases each song appears on, best first. */
    suspend fun identify(song: Song, apiKey: String): List<TrackMatch> {
        val fp = Chromaprint.fingerprint(context, song.uri)
        val json = lookup(apiKey, fp, (song.durationMs / 1000).toInt())
        return parse(json)
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
        var firstError: Throwable? = null
        songs.forEachIndexed { i, s ->
            progress(i, songs.size)
            perSong[s.id] = runCatching { identify(s, apiKey) }
                .onFailure { if (it is kotlinx.coroutines.CancellationException) throw it; firstError = firstError ?: it }
                .getOrDefault(emptyList())
        }
        progress(songs.size, songs.size)
        // One bad file shouldn't sink an album, but a bad key or no network should be reported.
        firstError?.let { e -> if (perSong.values.all { it.isEmpty() }) throw e }
        return groupByRelease(perSong, songs.size)
    }

    /** Every image the Cover Art Archive has for a release, front covers first. */
    suspend fun covers(releaseId: String, releaseGroupId: String): List<CoverImage> = withContext(Dispatchers.IO) {
        val out = ArrayList<CoverImage>()
        for (url in listOf("https://coverartarchive.org/release/$releaseId", "https://coverartarchive.org/release-group/$releaseGroupId")) {
            val body = runCatching { Net.http.get(url).decodeToString() }.getOrNull() ?: continue
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

    suspend fun download(url: String): ByteArray = withContext(Dispatchers.IO) { Net.http.get(url) }

    // --- HTTP ---------------------------------------------------------------

    private suspend fun lookup(apiKey: String, fingerprint: String, duration: Int): JSONObject = withContext(Dispatchers.IO) {
        // AcoustID allows 3 requests per second.
        val wait = 350 - (System.currentTimeMillis() - lastCall)
        if (wait > 0) delay(wait)
        lastCall = System.currentTimeMillis()

        val form = listOf(
            "client" to apiKey,
            "format" to "json",
            "duration" to duration.toString(),
            "fingerprint" to fingerprint,
            "meta" to "recordings releasegroups releases tracks compress",
        ).joinToString("&") { (k, v) -> "$k=" + URLEncoder.encode(v, "UTF-8") }

        val json = JSONObject(Net.http.postForm(LOOKUP_URL, form))
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
