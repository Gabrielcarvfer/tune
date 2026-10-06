package com.music.tune.data

/**
 * Finds songs that are copies of each other: the same recording saved twice,
 * maybe in another format, bitrate or with other tags.
 *
 * Songs with a fingerprint are compared by sound (their Chromaprint
 * fingerprints, which stay close across encodings); songs without one fall
 * back to the same title and artist. Either way they must be about as long.
 */
object Duplicates {

    /** Copies must be this close in length. */
    const val LENGTH_TOLERANCE_MS = 3_000L

    /** Fingerprint items compared: about the first minute (one item per ~0.12 s). */
    private const val COMPARE_ITEMS = 480

    /** How far one copy may start before the other, in items (~2 s). */
    private const val MAX_OFFSET = 16

    /** Fewest overlapping items worth comparing (~10 s). */
    private const val MIN_OVERLAP = 80

    /**
     * Below this share of differing bits, two fingerprints are the same audio.
     * Re-encodes of one recording differ in well under a tenth of their bits;
     * different songs in about half.
     */
    const val MAX_BIT_ERRORS = 0.2

    /**
     * Groups of copies, each with at least two songs, biggest groups first.
     * [fingerprints] holds the decoded fingerprints of the songs that have one.
     */
    fun find(songs: List<Song>, fingerprints: Map<Long, IntArray>): List<List<Song>> {
        val sorted = songs.sortedBy { it.durationMs }
        val parent = IntArray(sorted.size) { it }
        fun root(i: Int): Int {
            var r = i
            while (parent[r] != r) r = parent[r]
            var j = i
            while (parent[j] != r) j = parent[j].also { parent[j] = r }
            return r
        }

        val names = sorted.map { name(it.title) to name(it.artist) }
        for (i in sorted.indices) {
            val a = sorted[i]
            var j = i + 1
            while (j < sorted.size && sorted[j].durationMs - a.durationMs <= LENGTH_TOLERANCE_MS) {
                val b = sorted[j]
                val fa = fingerprints[a.id]
                val fb = fingerprints[b.id]
                val same = if (fa != null && fb != null) bitErrors(fa, fb) < MAX_BIT_ERRORS
                // Without both fingerprints: same title and artist (both known: "Intro" by
                // nobody in particular could be anything).
                else names[i].first.isNotEmpty() && names[i].second.isNotEmpty() && names[i] == names[j]
                if (same) parent[root(j)] = root(i)
                j++
            }
        }

        return sorted.indices.groupBy { root(it) }.values
            .filter { it.size > 1 }
            .map { group -> group.map { sorted[it] }.sortedBy { it.path } }
            .sortedWith(compareByDescending<List<Song>> { it.size }.thenBy { sortKey(it.first().title) })
    }

    /**
     * The smallest share of differing bits between two fingerprints over
     * their first minute, trying small offsets either way (one copy may have
     * a little more silence at the start). 1.0 if they hardly overlap.
     */
    fun bitErrors(a: IntArray, b: IntArray): Double {
        var best = 1.0
        for (offset in -MAX_OFFSET..MAX_OFFSET) {
            val from = maxOf(0, -offset)
            val to = minOf(a.size, b.size - offset, COMPARE_ITEMS)
            val n = to - from
            if (n < MIN_OVERLAP) continue
            var errors = 0
            for (i in from until to) errors += Integer.bitCount(a[i] xor b[i + offset])
            best = minOf(best, errors / (32.0 * n))
        }
        return best
    }

    /** A title or artist reduced to its letters and digits, for comparing names. */
    fun name(s: String): String =
        if (s.equals(UNKNOWN, ignoreCase = true)) "" else s.lowercase().filter { it.isLetterOrDigit() }
}
