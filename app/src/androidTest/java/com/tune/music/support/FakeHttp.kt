package com.tune.music.support

import com.tune.music.data.Http
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.URLDecoder
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Stands in for AcoustID and the Cover Art Archive. Lookups are answered in
 * call order (songs are identified in album order); GETs by URL.
 */
class FakeHttp : Http {
    val lookupResponses = ArrayList<String>()
    val pages = HashMap<String, ByteArray>()
    val requests = CopyOnWriteArrayList<String>()
    private var lookups = 0

    override fun get(url: String): ByteArray {
        requests += "GET $url"
        return pages[url] ?: throw IOException("HTTP 404 $url")
    }

    override fun postForm(url: String, form: String): String {
        requests += "POST $url ${URLDecoder.decode(form, "UTF-8").take(200)}"
        return lookupResponses.getOrElse(lookups++) { lookupResponses.last() }
    }

    fun formOf(i: Int): Map<String, String> = requests.filter { it.startsWith("POST") }[i]
        .substringAfter(' ').substringAfter(' ')
        .split('&').associate { it.substringBefore('=') to it.substringAfter('=') }

    companion object {
        fun error(message: String) = """{"status":"error","error":{"code":4,"message":"$message"}}"""

        /** One release a recording appears on, as AcoustID reports it. */
        data class Rel(
            val id: String,
            val group: String,
            val title: String,
            val artist: String,
            val year: Int,
            val track: Int,
            val trackCount: Int,
            val trackTitle: String? = null,
            val type: String = "Album",
            val country: String = "XW",
        )

        fun lookup(recordingTitle: String, recordingArtist: String, vararg releases: Rel, score: Double = 0.95): String {
            val groups = releases.groupBy { it.group }.map { (gid, rels) ->
                val f = rels.first()
                JSONObject()
                    .put("id", gid).put("title", f.title).put("type", f.type)
                    .put("artists", JSONArray().put(JSONObject().put("name", f.artist)))
                    .put("releases", JSONArray().apply {
                        rels.forEach { r ->
                            put(JSONObject()
                                .put("id", r.id).put("country", r.country)
                                .put("date", JSONObject().put("year", r.year))
                                .put("medium_count", 1)
                                .put("mediums", JSONArray().put(JSONObject()
                                    .put("format", "Digital Media").put("position", 1).put("track_count", r.trackCount)
                                    .put("tracks", JSONArray().put(JSONObject().put("position", r.track).apply {
                                        if (r.trackTitle != null) put("title", r.trackTitle)
                                    })))))
                        }
                    })
            }
            return JSONObject()
                .put("status", "ok")
                .put("results", JSONArray().put(JSONObject()
                    .put("id", "acoustid-$recordingTitle").put("score", score)
                    .put("recordings", JSONArray().put(JSONObject()
                        .put("id", "rec-$recordingTitle").put("title", recordingTitle)
                        .put("artists", JSONArray().put(JSONObject().put("name", recordingArtist)))
                        .put("releasegroups", JSONArray(groups))))))
                .toString()
        }

        fun coverArchive(vararg images: Triple<String, String, Boolean>): ByteArray =
            JSONObject().put("images", JSONArray().apply {
                images.forEach { (full, type, front) ->
                    put(JSONObject().put("image", full).put("front", front)
                        .put("types", JSONArray().put(type))
                        .put("thumbnails", JSONObject().put("250", "$full-250").put("1200", full)))
                }
            }).toString().toByteArray()
    }
}
