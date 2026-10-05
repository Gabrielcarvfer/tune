package com.tune.music.data

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class NetTest {
    private val calls = ArrayList<String>()
    private val recorder = object : Http {
        override fun get(url: String): ByteArray { calls += "GET $url"; return "ok".toByteArray() }
        override fun postForm(url: String, form: String): String { calls += "POST $url"; return "ok" }
    }

    @After fun restore() {
        Net.blocked = false
        Net.http = UrlConnectionHttp
    }

    @Test fun requestsGoOutNormally() {
        Net.http = recorder
        assertEquals("ok", Net.get("https://musicbrainz.org/x").decodeToString())
        assertEquals("ok", Net.postForm("https://api.acoustid.org/v2/lookup", "a=b"))
        assertEquals(2, calls.size)
    }

    @Test fun theKillSwitchStopsEveryRequestBeforeItLeaves() {
        Net.http = recorder
        Net.blocked = true
        listOf<() -> Unit>({ Net.get("https://coverartarchive.org/x") }, { Net.postForm("https://api.acoustid.org/v2/lookup", "") })
            .forEach { request ->
                try {
                    request()
                    fail("request went out")
                } catch (e: OfflineException) {
                    assertTrue(e.message!!.contains("turned off"))
                }
            }
        assertTrue(calls.isEmpty())
    }
}
