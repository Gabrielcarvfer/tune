package com.tune.music.data

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class MatchCacheTest {
    @get:Rule val tmp = TemporaryFolder()

    private val cache by lazy { MatchCache(tmp.newFolder("acoustid")) }
    private val answer = JSONObject().put("status", "ok").put("results", org.json.JSONArray())

    @Test fun savesAndReturnsTheFingerprintAndAnswer() {
        val s = song(7)
        cache.put(s, "AQAAfp", answer)
        val e = cache.get(s)!!
        assertEquals("AQAAfp", e.fingerprint)
        assertEquals("ok", e.lookup!!.getString("status"))
    }

    @Test fun aFingerprintAloneIsKeptUntilTheAnswerComes() {
        val s = song(7)
        cache.put(s, "AQAAfp", null)
        assertNull(cache.lookup(s))
        cache.put(s, null, answer)
        assertEquals("AQAAfp", cache.get(s)!!.fingerprint)
        assertEquals("ok", cache.lookup(s)!!.getString("status"))
    }

    @Test fun survivesTagEditsAndMovesButNotADifferentRecording() {
        cache.put(song(7, durationMs = 180_000), "fp", answer)
        // Same MediaStore id, new title and path: same audio, still cached.
        assertEquals("fp", cache.get(song(7, title = "Renamed", path = "/elsewhere/x.mp3", durationMs = 180_000))!!.fingerprint)
        // A different length means the file was replaced.
        assertNull(cache.get(song(7, durationMs = 200_000)))
    }

    @Test fun countsAndClears() {
        cache.put(song(1), "fp", answer)
        cache.put(song(2), "fp", null)
        assertEquals(1, cache.countLooked(listOf(song(1), song(2), song(3))))
        val v = cache.version
        cache.clear()
        assertNotEquals(v, cache.version)
        assertEquals(0, cache.countLooked(listOf(song(1))))
    }

    @Test fun aNewInstanceReadsWhatWasSaved() {
        val dir = tmp.newFolder("shared")
        MatchCache(dir).put(song(3), "fp", answer)
        assertEquals("fp", MatchCache(dir).get(song(3))!!.fingerprint)
    }
}
