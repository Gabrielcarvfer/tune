package com.music.tune.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.text.Normalizer
import kotlin.math.abs

/**
 * Finding releases on MusicBrainz by name, for when fingerprints point at the
 * wrong edition (or there's no AcoustID key). Songs are then matched to the
 * release's tracks by title, length and track number.
 * Rate limited to 1 request a second by the HTTP layer (see Throttle).
 */
object MusicBrainz {
    const val BASE = "https://musicbrainz.org/ws/2"

    /** A release found by a search; its tracks come from [release]. */
    data class Found(
        val id: String,
        val releaseGroupId: String,
        val title: String,
        val artist: String,
        val year: Int,
        val country: String,
        val format: String,
        val trackCount: Int,
        val discCount: Int,
        val type: String,
    ) {
        val coverThumb: String get() = "https://coverartarchive.org/release/$id/front-250"
    }

    data class Track(
        val disc: Int,
        val position: Int,
        val title: String,
        val artist: String,
        val lengthMs: Long,
        val recordingId: String,
        val discTrackCount: Int,
        val format: String,
    )

    data class Release(val info: Found, val tracks: List<Track>)

    fun searchUrl(album: String, artist: String): String {
        val q = buildString {
            append("release:").append(quote(album))
            if (artist.isNotBlank()) append(" AND artist:").append(quote(artist))
        }
        return "$BASE/release?query=" + URLEncoder.encode(q, "UTF-8") + "&fmt=json&limit=25"
    }

    fun releaseUrl(id: String) = "$BASE/release/$id?inc=recordings+artist-credits+release-groups&fmt=json"

    /** A Lucene phrase: quotes and backslashes escaped. */
    private fun quote(s: String) = "\"" + s.trim().replace("\\", "\\\\").replace("\"", "\\\"") + "\""

    suspend fun search(album: String, artist: String): List<Found> = withContext(Dispatchers.IO) {
        parseSearch(JSONObject(Net.get(searchUrl(album, artist)).decodeToString()))
    }

    suspend fun release(id: String): Release = withContext(Dispatchers.IO) {
        parseRelease(JSONObject(Net.get(releaseUrl(id)).decodeToString()))
    }

    // --- parsing ------------------------------------------------------------

    fun parseSearch(json: JSONObject): List<Found> = json.optJSONArray("releases").objects().map { info(it) }

    fun parseRelease(json: JSONObject): Release {
        val info = info(json)
        val tracks = ArrayList<Track>()
        for (m in json.optJSONArray("media").objects()) {
            val tl = m.optJSONArray("tracks").objects()
            for (t in tl) {
                val rec = t.optJSONObject("recording")
                tracks += Track(
                    disc = m.optInt("position", 1),
                    position = t.optInt("position"),
                    title = t.optString("title").ifEmpty { rec?.optString("title").orEmpty() },
                    artist = artists(t.optJSONArray("artist-credit")).ifEmpty { info.artist },
                    lengthMs = t.optLong("length").takeIf { it > 0 } ?: rec?.optLong("length") ?: 0L,
                    recordingId = rec?.optString("id").orEmpty(),
                    discTrackCount = m.optInt("track-count", tl.size),
                    format = m.optString("format"),
                )
            }
        }
        return Release(info, tracks)
    }

    private fun info(r: JSONObject): Found {
        val media = r.optJSONArray("media").objects()
        val rg = r.optJSONObject("release-group")
        return Found(
            id = r.optString("id"),
            releaseGroupId = rg?.optString("id").orEmpty(),
            title = r.optString("title"),
            artist = artists(r.optJSONArray("artist-credit")),
            year = r.optString("date").take(4).toIntOrNull() ?: 0,
            country = r.optString("country"),
            format = media.map { it.optString("format") }.filter { it.isNotEmpty() }.distinct().joinToString(" + "),
            trackCount = r.optInt("track-count").takeIf { it > 0 } ?: media.sumOf { it.optInt("track-count") },
            discCount = media.size.coerceAtLeast(1),
            type = rg?.optString("primary-type").orEmpty(),
        )
    }

    private fun artists(arr: JSONArray?): String {
        if (arr == null) return ""
        val sb = StringBuilder()
        for (a in arr.objects()) {
            sb.append(a.optString("name").ifEmpty { a.optJSONObject("artist")?.optString("name").orEmpty() })
            sb.append(a.optString("joinphrase"))
        }
        return sb.toString().trim()
    }

    // --- matching songs to tracks -------------------------------------------

    /**
     * Pairs each song with at most one track of [release], best pairs first,
     * and returns the release as a candidate like the fingerprint path does.
     * Songs without a good enough pair keep their title (album info still applies).
     */
    fun match(songs: List<Song>, release: Release): ReleaseCandidate {
        val info = release.info
        val pairs = ArrayList<Triple<Song, Track, Double>>()
        for (s in songs) for (t in release.tracks) {
            val score = pairScore(s, t, release.tracks.size)
            if (score >= MIN_SCORE) pairs += Triple(s, t, score)
        }
        pairs.sortByDescending { it.third }
        val usedSongs = HashSet<Long>()
        val usedTracks = HashSet<Track>()
        val tracks = HashMap<Long, TrackMatch>()
        for ((s, t, score) in pairs) {
            if (s.id in usedSongs || t in usedTracks) continue
            usedSongs += s.id
            usedTracks += t
            tracks[s.id] = TrackMatch(
                score = score,
                recordingId = t.recordingId,
                title = t.title,
                artist = t.artist,
                releaseId = info.id,
                releaseGroupId = info.releaseGroupId,
                album = info.title,
                albumArtist = info.artist,
                year = info.year,
                country = info.country,
                format = t.format,
                track = t.position,
                trackCount = t.discTrackCount,
                disc = t.disc,
                discCount = info.discCount,
                type = info.type,
            )
        }
        return ReleaseCandidate(
            releaseId = info.id,
            releaseGroupId = info.releaseGroupId,
            album = info.title,
            albumArtist = info.artist,
            year = info.year,
            country = info.country,
            format = info.format,
            type = info.type,
            trackCount = info.trackCount,
            discCount = info.discCount,
            tracks = tracks,
            score = if (songs.isEmpty()) 0.0 else tracks.size.toDouble() / songs.size,
        )
    }

    const val MIN_SCORE = 0.55

    /**
     * How well a song fits a track: mostly the title, then the length, plus a
     * little for the same position (which rescues "track 1"-style titles).
     */
    fun pairScore(s: Song, t: Track, releaseTracks: Int): Double {
        // "track 01", "Unknown": the title says nothing either way.
        val title = if (isPlaceholder(s.title)) 0.5 else titleSimilarity(s.title, t.title)
        val length = if (s.durationMs > 0 && t.lengthMs > 0) {
            (1.0 - abs(s.durationMs - t.lengthMs) / 10_000.0).coerceIn(0.0, 1.0)
        } else 0.5
        val position = if (s.track == t.position && (s.disc <= 1 && t.disc <= 1 || s.disc == t.disc)) 0.15 else 0.0
        return title * 0.7 + length * 0.3 + position
    }

    private val PLACEHOLDER = Regex("""^\s*(track|faixa|piste|pista|titel|title)?\s*#?\s*\d*\s*$|^\s*(unknown|untitled)\b.*""", RegexOption.IGNORE_CASE)

    fun isPlaceholder(title: String) = PLACEHOLDER.matches(title)

    /** 0..1, ignoring case, accents, punctuation and bracketed notes like "(Remastered)". */
    fun titleSimilarity(a: String, b: String): Double {
        val full = ratio(norm(a), norm(b))
        val bare = ratio(norm(stripNotes(a)), norm(stripNotes(b)))
        return maxOf(full, bare)
    }

    private fun stripNotes(s: String) = s.replace(Regex("""\s*[(\[][^)\]]*[)\]]"""), "")

    private fun norm(s: String): String =
        Normalizer.normalize(s.lowercase(), Normalizer.Form.NFD)
            .replace(Regex("\\p{M}+"), "")
            .replace(Regex("[^\\p{L}\\p{N}]+"), " ")
            .trim()

    private fun ratio(a: String, b: String): Double {
        if (a.isEmpty() && b.isEmpty()) return 1.0
        val n = maxOf(a.length, b.length)
        return 1.0 - levenshtein(a, b).toDouble() / n
    }

    private fun levenshtein(a: String, b: String): Int {
        var prev = IntArray(b.length + 1) { it }
        var cur = IntArray(b.length + 1)
        for (i in 1..a.length) {
            cur[0] = i
            for (j in 1..b.length) {
                cur[j] = minOf(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1)
            }
            val t = prev; prev = cur; cur = t
        }
        return prev[b.length]
    }

    private fun JSONArray?.objects(): List<JSONObject> =
        if (this == null) emptyList() else List(length()) { getJSONObject(it) }
}
