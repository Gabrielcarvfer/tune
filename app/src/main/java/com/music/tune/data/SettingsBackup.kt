package com.music.tune.data

import org.json.JSONArray
import org.json.JSONObject

/**
 * Tune's settings and data as one JSON file, for moving to another install:
 *
 * ```
 * { "format": "tune-settings", "version": 1, "exported": 1759737600000,
 *   "settings":  { "accent": { "type": "int", "value": -2621325 }, ... },
 *   "playlists": [ ... ],      // as in playlists.json
 *   "loudness":  { ... },      // as in loudness.json
 *   "scans":     { "<song id>": { "duration": ..., "fingerprint": ..., "lookup": ... }, ... } }
 * ```
 *
 * Settings keep their types so they come back exactly. Song ids are this
 * phone's media ids: loudness and scans are only useful on the same phone.
 */
object SettingsBackup {
    const val FORMAT = "tune-settings"
    const val VERSION = 1

    class Contents(
        val settings: Map<String, Any?>,
        val playlists: JSONArray?,
        val loudness: JSONObject?,
        val scans: Map<String, JSONObject>,
    )

    fun toJson(c: Contents, exportedAt: Long): JSONObject {
        val settings = JSONObject()
        c.settings.toSortedMap().forEach { (key, value) ->
            val (type, v) = when (value) {
                is Boolean -> "boolean" to value
                is Int -> "int" to value
                is Long -> "long" to value
                is Float -> "float" to value.toDouble()
                is String -> "string" to value
                is Set<*> -> "stringSet" to JSONArray(value.map { it.toString() }.sorted())
                else -> return@forEach
            }
            settings.put(key, JSONObject().put("type", type).put("value", v))
        }
        return JSONObject()
            .put("format", FORMAT)
            .put("version", VERSION)
            .put("exported", exportedAt)
            .put("settings", settings)
            .put("playlists", c.playlists ?: JSONArray())
            .put("loudness", c.loudness ?: JSONObject())
            .put("scans", JSONObject().apply { c.scans.toSortedMap().forEach { (id, e) -> put(id, e) } })
    }

    /** Reads an export; throws IllegalArgumentException if it isn't one Tune can read. */
    fun fromJson(json: JSONObject): Contents {
        require(json.optString("format") == FORMAT) { "not a Tune settings file" }
        require(json.optInt("version") in 1..VERSION) { "made by a newer Tune (format version ${json.optInt("version")})" }
        val settings = LinkedHashMap<String, Any?>()
        json.optJSONObject("settings")?.let { s ->
            for (key in s.keys()) {
                val e = s.getJSONObject(key)
                settings[key] = when (e.getString("type")) {
                    "boolean" -> e.getBoolean("value")
                    "int" -> e.getInt("value")
                    "long" -> e.getLong("value")
                    "float" -> e.getDouble("value").toFloat()
                    "string" -> e.getString("value")
                    "stringSet" -> e.getJSONArray("value").let { a -> (0 until a.length()).map { a.getString(it) }.toSet() }
                    else -> continue
                }
            }
        }
        val scans = LinkedHashMap<String, JSONObject>()
        json.optJSONObject("scans")?.let { s -> for (id in s.keys()) scans[id] = s.getJSONObject(id) }
        return Contents(settings, json.optJSONArray("playlists"), json.optJSONObject("loudness"), scans)
    }
}
