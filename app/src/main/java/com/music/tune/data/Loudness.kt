package com.music.tune.data

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.tan

/** A song's integrated loudness (LUFS, -infinity for silence) and sample peak (0..1 of full scale). */
data class Loudness(val lufs: Double, val peak: Double)

/**
 * Integrated loudness after ITU-R BS.1770-4 / EBU R128, the measure ReplayGain
 * 2 and streaming services use: K-weighting, 400 ms blocks overlapping by 75%,
 * an absolute gate at -70 LUFS and a relative gate 10 LU below the ungated level.
 * Feed it interleaved 16-bit samples, then read [result].
 */
class LoudnessMeter(sampleRate: Int, private val channels: Int) {
    private val shelf = Biquad.highShelf(sampleRate.toDouble())
    private val highPass = Biquad.highPass(sampleRate.toDouble())
    // One filter state per channel, two stages each.
    private val state = Array(channels) { DoubleArray(8) }
    // BS.1770 channel weights: surround channels count 1.41, the LFE not at all.
    private val weight = DoubleArray(channels) { ch ->
        if (channels == 6) doubleArrayOf(1.0, 1.0, 1.0, 0.0, 1.41, 1.41)[ch] else 1.0
    }
    private val subBlock = max(1, sampleRate / 10) // 100 ms: blocks are 4 of these
    private val power = ArrayList<Double>()         // weighted mean square per 100 ms
    private var acc = 0.0
    private var accFrames = 0
    private var channel = 0
    private var peak = 0

    fun feed(samples: ShortArray, count: Int) {
        for (i in 0 until count) {
            val s = samples[i].toInt()
            peak = max(peak, abs(s))
            val y = filter(state[channel], s / 32768.0)
            acc += weight[channel] * y * y
            if (++channel == channels) {
                channel = 0
                if (++accFrames == subBlock) {
                    power += acc / subBlock
                    acc = 0.0
                    accFrames = 0
                }
            }
        }
    }

    fun result(): Loudness {
        // 400 ms blocks every 100 ms.
        val blocks = DoubleArray(max(0, power.size - 3)) { j -> (power[j] + power[j + 1] + power[j + 2] + power[j + 3]) / 4 }
        val loud = { z: Double -> -0.691 + 10 * log10(z) }
        val aboveAbsolute = blocks.filter { it > 0 && loud(it) > -70.0 }
        val lufs = if (aboveAbsolute.isEmpty()) Double.NEGATIVE_INFINITY else {
            val relative = loud(aboveAbsolute.average()) - 10.0
            val gated = aboveAbsolute.filter { loud(it) > relative }
            loud(gated.average())
        }
        return Loudness(lufs, peak / 32768.0)
    }

    private fun filter(st: DoubleArray, x: Double): Double {
        val a = shelf.run(st, 0, x)
        return highPass.run(st, 4, a)
    }

    /** A direct-form-I biquad; its state lives in the caller's array at [offset]. */
    private class Biquad(val b0: Double, val b1: Double, val b2: Double, val a1: Double, val a2: Double) {
        fun run(st: DoubleArray, offset: Int, x: Double): Double {
            val y = b0 * x + b1 * st[offset] + b2 * st[offset + 1] - a1 * st[offset + 2] - a2 * st[offset + 3]
            st[offset + 1] = st[offset]
            st[offset] = x
            st[offset + 3] = st[offset + 2]
            st[offset + 2] = y
            return y
        }

        companion object {
            // The BS.1770 K-weighting stages, derived for any sample rate (as libebur128 does).
            fun highShelf(fs: Double): Biquad {
                val f0 = 1681.974450955533
                val g = 3.999843853973347
                val q = 0.7071752369554196
                val k = tan(PI * f0 / fs)
                val vh = 10.0.pow(g / 20)
                val vb = vh.pow(0.4996667741545416)
                val a0 = 1 + k / q + k * k
                return Biquad(
                    (vh + vb * k / q + k * k) / a0,
                    2 * (k * k - vh) / a0,
                    (vh - vb * k / q + k * k) / a0,
                    2 * (k * k - 1) / a0,
                    (1 - k / q + k * k) / a0,
                )
            }

            fun highPass(fs: Double): Biquad {
                val f0 = 38.13547087602444
                val q = 0.5003270373238773
                val k = tan(PI * f0 / fs)
                val a0 = 1 + k / q + k * k
                return Biquad(1.0, -2.0, 1.0, 2 * (k * k - 1) / a0, (1 - k / q + k * k) / a0)
            }
        }
    }
}

/** How loud songs are played: every song is turned down to [TARGET_LUFS], never up. */
object Normalization {
    /** The ReplayGain 2 reference level. */
    const val TARGET_LUFS = -18.0

    /**
     * The gain (dB, at most 0) that brings a song down to the target. Quieter
     * songs play as they are: raising them could clip.
     */
    fun gainDb(l: Loudness?): Double =
        if (l == null || !l.lufs.isFinite()) 0.0 else min(0.0, TARGET_LUFS - l.lufs)

    /** The player volume (0..1) for a gain in dB. */
    fun volume(gainDb: Double): Float = 10.0.pow(gainDb / 20).toFloat()
}
