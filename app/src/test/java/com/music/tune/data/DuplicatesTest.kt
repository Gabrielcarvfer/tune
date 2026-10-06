package com.music.tune.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class DuplicatesTest {

    /** A made-up fingerprint: two minutes of random items. */
    private fun fingerprint(seed: Int, items: Int = 960) = Random(seed).let { r -> IntArray(items) { r.nextInt() } }

    /** [fp] with about [share] of its bits flipped, as a re-encode would. */
    private fun noisy(fp: IntArray, share: Double, seed: Int = 99): IntArray {
        val r = Random(seed)
        return IntArray(fp.size) { i ->
            var v = fp[i]
            for (bit in 0 until 32) if (r.nextDouble() < share) v = v xor (1 shl bit)
            v
        }
    }

    @Test fun aReEncodeIsTheSameAudio() {
        val fp = fingerprint(1)
        assertTrue(Duplicates.bitErrors(fp, fp) == 0.0)
        assertTrue(Duplicates.bitErrors(fp, noisy(fp, 0.08)) < Duplicates.MAX_BIT_ERRORS)
    }

    @Test fun aCopyStartingALittleLaterStillMatches() {
        val fp = fingerprint(2)
        val later = IntArray(5) { 0 } + fp // half a second more silence at the start
        assertTrue(Duplicates.bitErrors(fp, later) < 0.01)
        assertTrue(Duplicates.bitErrors(later, fp) < 0.01)
    }

    @Test fun differentSongsAreFarApart() {
        assertTrue(Duplicates.bitErrors(fingerprint(3), fingerprint(4)) > 0.4)
    }

    @Test fun tooShortToCompareCountsAsDifferent() {
        assertEquals(1.0, Duplicates.bitErrors(fingerprint(5, 40), fingerprint(5, 40)), 0.0)
    }

    @Test fun copiesAreFoundBySoundWhateverTheirTags() {
        val fp = fingerprint(6)
        val a = song(1, "Pull Up to the Bumper", path = "/Music/a/Pull Up.mp3")
        val b = song(2, "Pull up to the bumper (remastered)", artist = "Jones, Grace", path = "/Music/b/01.flac", durationMs = 181_000)
        val other = song(3, "Nightclubbing")
        val groups = Duplicates.find(listOf(a, b, other), mapOf(1L to fp, 2L to noisy(fp, 0.05), 3L to fingerprint(7)))
        assertEquals(listOf(listOf(a, b)), groups)
    }

    @Test fun differentRecordingsWithTheSameNameAreNotCopies() {
        // Both fingerprinted: the sound decides, not the names.
        val studio = song(1, "Nightclubbing")
        val live = song(2, "Nightclubbing")
        assertEquals(emptyList<List<Song>>(), Duplicates.find(listOf(studio, live), mapOf(1L to fingerprint(8), 2L to fingerprint(9))))
    }

    @Test fun withoutFingerprintsTheSameTitleAndArtistMatch() {
        val a = song(1, "Crystal Dolphin", artist = "Engelwood", path = "/Music/x/Crystal Dolphin.mp3")
        val b = song(2, "crystal dolphin!", artist = "ENGELWOOD", path = "/Music/y/crystal dolphin.m4a", durationMs = 182_500)
        val c = song(3, "Crystal Dolphin", artist = "Someone Else")
        // One with a fingerprint, one without: names are all there is to go on.
        assertEquals(listOf(listOf(a, b)), Duplicates.find(listOf(a, b, c), mapOf(1L to fingerprint(10))))
    }

    @Test fun unknownArtistsDontMatchByName() {
        val a = song(1, "Intro", artist = UNKNOWN)
        val b = song(2, "Intro", artist = UNKNOWN)
        assertEquals(emptyList<List<Song>>(), Duplicates.find(listOf(a, b), emptyMap()))
    }

    @Test fun songsOfDifferentLengthsAreNotCopies() {
        val fp = fingerprint(11)
        val radio = song(1, "Song", durationMs = 180_000)
        val extended = song(2, "Song", durationMs = 420_000)
        assertEquals(emptyList<List<Song>>(), Duplicates.find(listOf(radio, extended), mapOf(1L to fp, 2L to fp)))
    }

    @Test fun threeCopiesMakeOneGroupAndBiggerGroupsComeFirst() {
        val fp = fingerprint(12)
        val x = (1..3).map { song(it.toLong(), "Zebra", path = "/Music/$it/zebra.mp3") }
        val y = (4..5).map { song(it.toLong(), "Aardvark", path = "/Music/$it/aardvark.mp3", durationMs = 200_000) }
        val groups = Duplicates.find(y + x, mapOf(1L to fp, 2L to noisy(fp, 0.05, 1), 3L to noisy(fp, 0.05, 2)))
        assertEquals(listOf(x, y), groups)
    }
}
