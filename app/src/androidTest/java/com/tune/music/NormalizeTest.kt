package com.tune.music

import androidx.compose.ui.test.hasText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.tune.music.data.Normalization
import com.tune.music.support.TestMedia
import com.tune.music.support.TuneTest
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.abs

/** Playing songs at a similar loudness, measured on the phone, without touching the files. */
@RunWith(AndroidJUnit4::class)
class NormalizeTest : TuneTest() {

    private val album = "Tune Test Levels"
    override val songs = listOf(
        TestMedia.Spec("Loud Song", TestMedia.BAND, album, 1, 6, 440.0),
        // 18 dB quieter.
        TestMedia.Spec("Quiet Song", TestMedia.BAND, album, 2, 6, 523.25, level = 0.125),
    )

    private fun measureAll() {
        tap("settings")
        scrollTo(hasText("measure all songs"))
        tap("measure all songs")
        waitFor("measured", 60_000) { vm.measure.value.let { !it.running && it.already + it.done == 2 } }
    }

    private val volume get() = run { var v = 1f; onVm { v = player.volume }; v }

    private fun expectedVolume(title: String) = Normalization.volume(Normalization.gainDb(vm.loudnessOf(song(title))))

    @Test fun measuringFindsTheDifferenceInLoudness() {
        measureAll()
        val loud = vm.loudnessOf(song("Loud Song"))!!.lufs
        val quiet = vm.loudnessOf(song("Quiet Song"))!!.lufs
        assertEquals("18 dB apart (were $loud and $quiet)", 18.0, loud - quiet, 1.0)
        scrollTo(hasText("2 of 2 songs measured"))
        text("2 of 2 songs measured")
        // Measured once: nothing left to measure.
        assertEquals(2, runBlocking { vm.measuredCount() })
    }

    @Test fun theLoudSongIsTurnedDownAndTheQuietOneIsNot() {
        measureAll()
        playFromSongs("Loud Song")
        val down = expectedVolume("Loud Song")
        assertTrue("the loud song needs turning down ($down)", down < 0.9f)
        waitFor("loud song turned down") { abs(volume - down) < 0.01f }

        onVm { player.jumpTo(1) }
        waitFor("quiet song playing") { player.currentId == song("Quiet Song").id }
        waitFor("quiet song at full volume") { abs(volume - 1f) < 0.01f }
    }

    @Test fun aSongNotMeasuredYetIsMeasuredWhenItPlays() {
        playFromSongs("Loud Song")
        waitFor("measured while playing", 30_000) { vm.loudnessOf(song("Loud Song")) != null || runBlocking { vm.measuredCount() } > 0 }
        waitFor("then turned down", 30_000) { volume < 0.9f }
    }

    @Test fun turningItOffPlaysEverythingAsIs() {
        measureAll()
        playFromSongs("Loud Song")
        waitFor("turned down") { volume < 0.9f }
        goHome()
        tap("settings")
        scrollTo(hasText("normalize volume"))
        tap("normalize volume")
        waitFor("setting off") { !vm.normalize.value }
        waitFor("full volume") { abs(volume - 1f) < 0.01f }
    }

    @Test fun filesAreNotChanged() {
        val before = songs.associate { it.title to TestMedia.pathOf(ctx, uris.getValue(it.title))!!.let { p -> java.io.File(p).let { f -> f.length() to f.lastModified() } } }
        measureAll()
        playFromSongs("Loud Song")
        waitFor("turned down") { volume < 0.9f }
        songs.forEach {
            val f = java.io.File(TestMedia.pathOf(ctx, uris.getValue(it.title))!!)
            assertEquals(it.title, before.getValue(it.title), f.length() to f.lastModified())
        }
    }
}
