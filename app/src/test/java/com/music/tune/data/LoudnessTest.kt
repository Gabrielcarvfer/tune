package com.music.tune.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import kotlin.math.PI
import kotlin.math.pow
import kotlin.math.sin

class LoudnessMeterTest {

    /** [seconds] of a 997 Hz sine at [dbfs] (peak), interleaved over [channels]; then [silence] seconds of nothing. */
    private fun measure(dbfs: Double, seconds: Int, rate: Int = 48_000, channels: Int = 2, silence: Int = 0, then: Pair<Double, Int>? = null): Loudness {
        val meter = LoudnessMeter(rate, channels)
        fun tone(db: Double, secs: Int, phase0: Long) {
            val amp = 10.0.pow(db / 20) * 32767
            val frames = rate.toLong() * secs
            val buf = ShortArray(4096 * channels)
            var f = 0L
            while (f < frames) {
                val n = minOf(4096L, frames - f).toInt()
                for (i in 0 until n) {
                    val v = (amp * sin(2 * PI * 997.0 * (phase0 + f + i) / rate)).toInt().toShort()
                    for (c in 0 until channels) buf[i * channels + c] = v
                }
                meter.feed(buf, n * channels)
                f += n
            }
        }
        tone(dbfs, seconds, 0)
        if (silence > 0) meter.feed(ShortArray(rate * channels * silence), rate * channels * silence)
        then?.let { (db, secs) -> tone(db, secs, rate.toLong() * seconds) }
        return meter.result()
    }

    @Test fun theReferenceToneMeasuresMinus23() {
        // EBU Tech 3341: a stereo 1 kHz sine at -23 dBFS reads -23 LUFS.
        assertEquals(-23.0, measure(-23.0, 20).lufs, 0.1)
    }

    @Test fun theSampleRateDoesntMatter() {
        assertEquals(-23.0, measure(-23.0, 20, rate = 44_100).lufs, 0.1)
    }

    @Test fun louderIsLouderByAsManyDb() {
        assertEquals(-13.0, measure(-13.0, 10).lufs, 0.1)
        assertEquals(-33.0, measure(-33.0, 10).lufs, 0.1)
    }

    @Test fun monoCountsOneChannel() {
        // Half the power of the same tone on two channels.
        assertEquals(-26.0, measure(-23.0, 10, channels = 1).lufs, 0.1)
    }

    @Test fun silenceIsGatedOut() {
        assertEquals(-23.0, measure(-23.0, 10, silence = 10).lufs, 0.1)
    }

    @Test fun quietPassagesFarBelowTheRestAreGatedOut() {
        // A -43 dBFS part is 20 LU below the -23 dBFS part: left out by the relative gate.
        assertEquals(-23.0, measure(-23.0, 10, then = -43.0 to 10).lufs, 0.1)
    }

    @Test fun silenceAloneHasNoLoudness() {
        val m = LoudnessMeter(48_000, 2)
        m.feed(ShortArray(96_000 * 5), 96_000 * 5)
        assertTrue(m.result().lufs == Double.NEGATIVE_INFINITY)
    }

    @Test fun measuresThePeak() {
        assertEquals(10.0.pow(-6.0 / 20), measure(-6.0, 2).peak, 0.001)
    }
}

class NormalizationTest {
    @Test fun loudSongsAreTurnedDownToTheTarget() {
        assertEquals(-8.0, Normalization.gainDb(Loudness(-10.0, 1.0)), 1e-9)
        assertEquals(0.5f, Normalization.volume(-6.0206), 0.001f)
    }

    @Test fun quietSongsAreNeverTurnedUp() {
        assertEquals(0.0, Normalization.gainDb(Loudness(-25.0, 0.1)), 1e-9)
    }

    @Test fun unmeasuredOrSilentSongsPlayAsTheyAre() {
        assertEquals(0.0, Normalization.gainDb(null), 1e-9)
        assertEquals(0.0, Normalization.gainDb(Loudness(Double.NEGATIVE_INFINITY, 0.0)), 1e-9)
        assertEquals(1f, Normalization.volume(0.0), 1e-6f)
    }
}

class LoudnessStoreTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test fun savesAndReadsBack() {
        val file = java.io.File(tmp.root, "loudness.json")
        LoudnessStore(file).apply {
            put(song(1, durationMs = 200_000), Loudness(-9.5, 0.98))
            put(song(2), Loudness(Double.NEGATIVE_INFINITY, 0.0))
            flush()
        }
        val again = LoudnessStore(file)
        assertEquals(-9.5, again.get(song(1, durationMs = 200_000))!!.lufs, 1e-9)
        assertEquals(-9.5, again[1L]!!.lufs, 1e-9)
        assertTrue(again.get(song(2))!!.lufs == Double.NEGATIVE_INFINITY)
        assertEquals(2, again.count(listOf(song(1, durationMs = 200_000), song(2), song(3))))
    }

    @Test fun aDifferentLengthMeansADifferentRecording() {
        val store = LoudnessStore(java.io.File(tmp.root, "l.json"))
        store.put(song(1, durationMs = 200_000), Loudness(-9.5, 0.98))
        assertNull(store.get(song(1, durationMs = 180_000)))
    }

    @Test fun clearForgetsEverything() {
        val file = java.io.File(tmp.root, "c.json")
        val store = LoudnessStore(file)
        store.put(song(1), Loudness(-9.5, 0.98))
        store.flush()
        store.clear()
        assertNull(LoudnessStore(file)[1L])
    }
}
