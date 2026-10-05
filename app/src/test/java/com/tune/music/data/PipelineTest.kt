package com.tune.music.data

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PipelineTest {

    @Test fun eachItemIsFinishedAsSoonAsItsReady() = runTest {
        val finished = ArrayList<Pair<Int, Int>>()
        pipeline(
            listOf(1, 2, 3, 4), parallel = 4, dispatcher = StandardTestDispatcher(testScheduler),
            prepare = { delay((5 - it) * 100L); it * 10 }, // the last item is ready first
            finish = { item, r -> finished += item to r.getOrThrow() },
        )
        // Not held back by slower items listed before it; each result keeps its item.
        assertEquals(listOf(4 to 40, 3 to 30, 2 to 20, 1 to 10), finished)
    }

    @Test fun neverPreparesMoreThanAllowedAtOnce() = runTest {
        var running = 0
        var most = 0
        pipeline(
            (1..10).toList(), parallel = 3, dispatcher = StandardTestDispatcher(testScheduler),
            prepare = {
                running++
                most = maxOf(most, running)
                delay(100)
                running--
            },
            finish = { _, _ -> },
        )
        assertEquals(3, most)
    }

    @Test fun preparingRunsAheadWhileFinishingIsSlow() = runTest {
        // Like fingerprinting (1 s each, 4 cores) ahead of rate-limited lookups (0.3 s each).
        pipeline(
            (1..8).toList(), parallel = 4, dispatcher = StandardTestDispatcher(testScheduler),
            prepare = { delay(1_000) },
            finish = { _, _ -> delay(300) },
        )
        // One at a time: 8 × 1.3 s = 10.4 s. Overlapped: 4 are ready at 1 s and
        // 4 more at 2 s, and the finishing steps run back to back from 1 s:
        // 1 s + 8 × 0.3 s = 3.4 s.
        assertEquals(3_400L, currentTime)
    }

    @Test fun aFailedPreparationIsPassedOnAndTheRestGoOn() = runTest {
        val results = HashMap<Int, String>()
        pipeline(
            listOf(1, 2, 3), parallel = 2, dispatcher = StandardTestDispatcher(testScheduler),
            prepare = { if (it == 2) error("can't decode") else it },
            finish = { item, r -> results[item] = r.exceptionOrNull()?.message ?: r.getOrThrow().toString() },
        )
        assertEquals(mapOf(1 to "1", 2 to "can't decode", 3 to "3"), results)
    }

    @Test fun throwingFromFinishStopsEverything() = runTest {
        val prepared = ArrayList<Int>()
        try {
            pipeline(
                (1..20).toList(), parallel = 2, dispatcher = StandardTestDispatcher(testScheduler),
                prepare = { delay(100); prepared += it },
                finish = { _, _ -> throw IllegalStateException("bad key") },
            )
            fail("should have stopped")
        } catch (e: IllegalStateException) {
            assertEquals("bad key", e.message)
        }
        assertTrue("stopped early, prepared ${prepared.size}", prepared.size < 20)
    }
}
