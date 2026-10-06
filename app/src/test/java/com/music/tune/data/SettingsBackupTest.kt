package com.music.tune.data

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class SettingsBackupTest {

    private val settings = mapOf<String, Any?>(
        "accent" to -2621325,
        "acoustid" to "secret-key",
        "albumGrid" to false,
        "libraryFolder" to "/storage/emulated/0/Resilio/Music",
        "history" to "3107710707238007335,5632141290367127279",
        "big" to 5_000_000_000L,
        "ratio" to 0.75f,
        "tags" to setOf("b", "a"),
    )

    private fun backup() = SettingsBackup.Contents(
        settings,
        JSONArray().put(JSONObject().put("id", "p1").put("name", "Road trip").put("songs", JSONArray(listOf(1, 2)))),
        JSONObject().put("7", JSONObject().put("d", 180000).put("l", -9.5).put("p", 0.98)),
        mapOf("7" to JSONObject().put("duration", 180000).put("fingerprint", "AQAA")),
    )

    @Test fun everythingComesBackExactly() {
        val json = SettingsBackup.toJson(backup(), 1759737600000)
        // Through text, as in the file.
        val back = SettingsBackup.fromJson(JSONObject(json.toString(1)))
        assertEquals(settings, back.settings)
        assertEquals("Road trip", back.playlists!!.getJSONObject(0).getString("name"))
        assertEquals(-9.5, back.loudness!!.getJSONObject("7").getDouble("l"), 1e-9)
        assertEquals("AQAA", back.scans.getValue("7").getString("fingerprint"))
    }

    @Test fun theFileSaysWhatItIs() {
        val json = SettingsBackup.toJson(backup(), 1759737600000)
        assertEquals("tune-settings", json.getString("format"))
        assertEquals(1, json.getInt("version"))
        assertEquals(1759737600000, json.getLong("exported"))
        assertEquals("bool keeps its type", "boolean", json.getJSONObject("settings").getJSONObject("albumGrid").getString("type"))
    }

    @Test fun otherFilesAreRefused() {
        listOf(JSONObject().put("hello", 1), JSONObject().put("format", "tune-settings").put("version", 99)).forEach {
            try {
                SettingsBackup.fromJson(it)
                fail("accepted $it")
            } catch (e: IllegalArgumentException) {
                assertTrue(e.message!!.isNotEmpty())
            }
        }
    }

    @Test fun missingPartsAreFine() {
        val back = SettingsBackup.fromJson(JSONObject().put("format", "tune-settings").put("version", 1))
        assertTrue(back.settings.isEmpty() && back.scans.isEmpty())
    }
}
