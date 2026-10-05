package com.tune.music.data

import org.junit.Assert.assertEquals
import org.junit.Test

class ThrottleTest {
    private var clock = 0L
    private val waits = ArrayList<Long>()
    private val throttle = Throttle(
        mapOf("musicbrainz.org" to 1_000L, "api.acoustid.org" to 334L),
        now = { clock },
        sleep = { waits += it; clock += it },
    )

    @Test fun secondRequestWaitsForTheInterval() {
        throttle.acquire("musicbrainz.org")
        throttle.acquire("musicbrainz.org")
        assertEquals(listOf(1_000L), waits)
    }

    @Test fun requestsSpacedOutAlreadyDontWait() {
        throttle.acquire("musicbrainz.org")
        clock += 1_500
        throttle.acquire("musicbrainz.org")
        assertEquals(emptyList<Long>(), waits)
    }

    @Test fun aBurstIsSpreadOneIntervalApart() {
        // Three callers at the same instant (no sleeping between them).
        val t = Throttle(mapOf("api.acoustid.org" to 334L), now = { 0L }, sleep = { waits += it })
        repeat(3) { t.acquire("api.acoustid.org") }
        assertEquals(listOf(334L, 668L), waits)
    }

    @Test fun eachServiceHasItsOwnLimit() {
        throttle.acquire("musicbrainz.org")
        throttle.acquire("api.acoustid.org")
        assertEquals(emptyList<Long>(), waits)
    }

    @Test fun subdomainsShareTheirServicesLimit() {
        throttle.acquire("musicbrainz.org")
        throttle.acquire("beta.musicbrainz.org")
        assertEquals(emptyList<Long>(), waits) // separate hosts, separate slots
        throttle.acquire("beta.musicbrainz.org")
        assertEquals(listOf(1_000L), waits)
    }

    @Test fun otherHostsAreNotLimited() {
        repeat(3) { throttle.acquire("ia800000.us.archive.org") }
        assertEquals(emptyList<Long>(), waits)
    }

    @Test fun theAppsLimitsMatchThePublishedOnes() {
        assertEquals(1_000L, Throttle.SERVICES["musicbrainz.org"])
        assertEquals(334L, Throttle.SERVICES["api.acoustid.org"]) // 3 per second
    }
}
