package com.tune.music.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/** App-local playlists, persisted as JSON in internal storage. */
class PlaylistStore(context: Context) {
    private val file = File(context.filesDir, "playlists.json")
    private val _playlists = MutableStateFlow(read())
    val playlists: StateFlow<List<Playlist>> = _playlists.asStateFlow()

    fun create(name: String, songIds: List<Long> = emptyList()): Playlist {
        val p = Playlist(UUID.randomUUID().toString(), name, songIds)
        update { it + p }
        return p
    }

    fun rename(id: String, name: String) = update { list ->
        list.map { if (it.id == id) it.copy(name = name) else it }
    }

    fun delete(id: String) = update { list -> list.filterNot { it.id == id } }

    fun addSongs(id: String, songIds: List<Long>) = update { list ->
        list.map { if (it.id == id) it.copy(songIds = it.songIds + songIds) else it }
    }

    fun removeAt(id: String, index: Int) = update { list ->
        list.map { p ->
            if (p.id == id) p.copy(songIds = p.songIds.toMutableList().also { it.removeAt(index) }) else p
        }
    }

    fun move(id: String, from: Int, to: Int) = update { list ->
        list.map { p ->
            if (p.id != id || to !in p.songIds.indices) p
            else p.copy(songIds = p.songIds.toMutableList().also { it.add(to, it.removeAt(from)) })
        }
    }

    /** Drops ids of songs that no longer exist (e.g. after a delete). */
    fun prune(existing: Set<Long>) = update { list ->
        list.map { p -> p.copy(songIds = p.songIds.filter { it in existing }) }
    }

    @Synchronized
    private fun update(f: (List<Playlist>) -> List<Playlist>) {
        val next = f(_playlists.value)
        _playlists.value = next
        write(next)
    }

    private fun read(): List<Playlist> = runCatching {
        if (!file.exists()) return emptyList()
        val arr = JSONArray(file.readText())
        List(arr.length()) { i ->
            val o = arr.getJSONObject(i)
            val ids = o.getJSONArray("songs")
            Playlist(o.getString("id"), o.getString("name"), List(ids.length()) { ids.getLong(it) })
        }
    }.getOrDefault(emptyList())

    private fun write(list: List<Playlist>) {
        val arr = JSONArray()
        list.forEach { p ->
            arr.put(JSONObject().apply {
                put("id", p.id)
                put("name", p.name)
                put("songs", JSONArray(p.songIds))
            })
        }
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(arr.toString())
        tmp.renameTo(file)
    }
}
