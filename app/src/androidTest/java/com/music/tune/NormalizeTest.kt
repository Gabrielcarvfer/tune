package com.music.tune

import androidx.compose.ui.test.hasText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.music.tune.data.Normalization
import com.music.tune.support.TestMedia
import com.music.tune.support.TuneTest
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
        openSettings("playback")
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
        openSettings("playback")
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

/** Stopping "measure all songs" stops it. */
@RunWith(AndroidJUnit4::class)
class StopMeasuringTest : TuneTest() {
    override val songs = (1..8).map { TestMedia.Spec("Long Song %02d".format(it), TestMedia.BAND, "Tune Test Long", it, 40, 200.0 + it * 23) }

    @Test fun stopStopsTheMeasuring() {
        openSettings("playback")
        scrollTo(hasText("measure all songs"))
        tap("measure all songs")
        waitFor("measuring") { vm.measure.value.running }
        scrollTo(hasText("stop measuring"))
        tap("stop measuring")
        waitFor("stopped", 5_000) { !vm.measure.value.running }
        val done = vm.measure.value.done
        assertTrue("stopped before the end ($done of 8)", done < 8)
        // And it stays stopped: nothing more gets measured.
        Thread.sleep(3_000)
        assertEquals(done, vm.measure.value.done)
        text("measure all songs")
    }
}

/** Moving on when a song's sound has ended, instead of playing out its silent ending. */
@RunWith(AndroidJUnit4::class)
class SkipQuietEndingsTest : TuneTest() {
    override val songs = listOf(
        // 5 s of melody, then 10 s of silence.
        TestMedia.Spec("Fading Song", TestMedia.BAND, "Tune Test Endings", 1, 15, 440.0, silentEnd = 10),
        TestMedia.Spec("Next Song", TestMedia.BAND, "Tune Test Endings", 2, 6, 523.25),
    )

    private fun measureAll() {
        openSettings("playback")
        scrollTo(hasText("measure all songs"))
        tap("measure all songs")
        waitFor("measured", 60_000) { vm.measure.value.let { !it.running && it.already + it.done == 2 } }
        assertEquals(5_000.0, vm.loudnessOf(song("Fading Song"))!!.endMs!!.toDouble(), 500.0)
    }

    private fun playFirst() {
        openCollection("songs")
        tap("Fading Song")
        waitFor("playing") { player.isPlaying && player.currentId == song("Fading Song").id }
    }

    @Test fun theSilentEndingIsSkipped() {
        measureAll()
        val start = android.os.SystemClock.uptimeMillis()
        playFirst()
        waitFor("next song", 12_000) { player.currentId == song("Next Song").id }
        val took = android.os.SystemClock.uptimeMillis() - start
        assertTrue("moved on after the sound, not the silence ($took ms)", took < 11_000)
    }

    @Test fun offPlaysTheWholeSong() {
        onVm { setSkipTails(false) }
        measureAll()
        playFirst()
        Thread.sleep(9_000)
        assertEquals("still in its silent ending", song("Fading Song").id, player.currentId)
        waitFor("next song after the end", 15_000) { player.currentId == song("Next Song").id }
    }

    @Test fun repeatOneStartsTheSongAgain() {
        measureAll()
        playFirst()
        onVm { player.cycleRepeat(); player.cycleRepeat() } // off -> all -> one
        waitFor("repeat one") { player.repeat == com.music.tune.playback.RepeatMode.ONE }
        waitFor("past its sound", 10_000) { player.positionMs > 4_000 }
        waitFor("back at its start", 8_000) { player.positionMs < 2_000 && player.currentId == song("Fading Song").id }
    }
}
