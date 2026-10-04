package com.tune.music

import android.view.KeyEvent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.click
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.tune.music.playback.RepeatMode
import com.tune.music.support.TestMedia
import com.tune.music.support.TuneTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.abs

/** Transport controls, gestures, shuffle/repeat, the queue and media keys. */
@RunWith(AndroidJUnit4::class)
class PlaybackTest : TuneTest() {

    private fun id(title: String) = song(title).id

    @Test fun tappingASongPlaysItAndOpensNowPlaying() {
        playFromSongs("Alpha Song")
        assertEquals(Screen.NowPlaying, screen)
        tag("np:title").assertIsDisplayed()
        text(TestMedia.BAND.lowercase())
        text(TestMedia.ALBUM.lowercase())
        // Up next, in list order: Beta, then Delta.
        text("Beta Song")
        text("Delta Tune")
        waitFor("position advancing") { player.positionMs > 500 }
    }

    @Test fun playPauseButtonToggles() {
        playFromSongs("Alpha Song")
        button("play/pause").performClick()
        waitFor("paused") { !player.isPlaying }
        button("play/pause").performClick()
        waitFor("playing again") { player.isPlaying }
    }

    @Test fun nextAndPreviousButtons() {
        playFromSongs("Alpha Song")
        button("next").performClick()
        waitFor("Beta") { player.currentId == id("Beta Song") }
        // Within the first 3 seconds, previous goes to the previous song...
        button("previous").performClick()
        waitFor("back to Alpha") { player.currentId == id("Alpha Song") }
    }

    @Test fun previousAfterThreeSecondsRestartsTheSong() {
        playFromSongs("Alpha Song")
        waitFor("past 3s", 10_000) { player.positionMs > 3_300 }
        button("previous").performClick()
        waitFor("restarted") { player.positionMs < 2_000 }
        assertEquals(id("Alpha Song"), player.currentId)
    }

    @Test fun swipingTheAlbumArtSkipsSongs() {
        playFromSongs("Beta Song")
        swipeNext("np:art")
        waitFor("next song") { player.currentId == id("Delta Tune") }
        swipePrev("np:art")
        waitFor("previous song") { player.currentId == id("Beta Song") }
    }

    @Test fun tappingTheAlbumArtPausesAndResumes() {
        playFromSongs("Alpha Song")
        tag("np:art").performTouchInput { click() }
        waitFor("paused") { !player.isPlaying }
        tag("np:art").performTouchInput { click() }
        waitFor("playing") { player.isPlaying }
    }

    @Test fun tappingTheSeekBarSeeks() {
        playFromSongs("Alpha Song")
        waitFor("duration known") { player.durationMs > 0 }
        tag("np:seek").performTouchInput { click(Offset(width * 0.75f, centerY)) }
        waitFor("seeked to ~75%") { abs(player.positionMs - player.durationMs * 0.75) < 900 }
    }

    @Test fun shuffleAndRepeatButtonsCycle() {
        playFromSongs("Alpha Song")
        button("shuffle").performClick()
        waitFor("shuffle on") { player.shuffle }
        button("shuffle on").assertIsDisplayed()

        button("repeat").performClick()
        waitFor("repeat all") { player.repeat == RepeatMode.ALL }
        button("repeat all").performClick()
        waitFor("repeat one") { player.repeat == RepeatMode.ONE }
        button("repeat one").performClick()
        waitFor("repeat off") { player.repeat == RepeatMode.OFF }

        button("shuffle on").performClick()
        waitFor("shuffle off") { !player.shuffle }
    }

    @Test fun songEndAdvancesToTheNext() {
        playFromSongs("Alpha Song")
        waitFor("duration known") { player.durationMs > 0 }
        onVm { player.seekTo(this.player.state.value.durationMs - 400) }
        waitFor("advanced to Beta") { player.currentId == id("Beta Song") }
    }

    @Test fun repeatOneReplaysTheSameSong() {
        playFromSongs("Alpha Song")
        button("repeat").performClick()
        button("repeat all").performClick()
        waitFor("repeat one") { player.repeat == RepeatMode.ONE }
        waitFor("duration known") { player.durationMs > 0 }
        onVm { player.seekTo(this.player.state.value.durationMs - 400) }
        waitFor("near the end") { player.positionMs > player.durationMs - 1_500 }
        waitFor("started over") { player.positionMs < 1_500 }
        assertEquals(id("Alpha Song"), player.currentId)
    }

    @Test fun shuffleAllStartsOnTheCurrentSongAndQueuesEverything() {
        tap("shuffle all")
        waitFor("playing") { player.isPlaying }
        assertTrue(player.shuffle)
        assertEquals("current song is first in the shuffled order", 0, player.queuePosition)
        assertEquals(vm.library.value.songs.map { it.id }.toSet(), player.queue.toSet())
        assertEquals(vm.library.value.songs.size, player.queue.size)
    }

    @Test fun playNextWhileShuffledPlaysNext() {
        tap("shuffle all")
        waitFor("playing") { player.isPlaying && player.queue.isNotEmpty() }
        val other = vm.library.value.songs.first { it.id != player.currentId && it.id != player.upcoming.firstOrNull() }
        openCollection("songs")
        longPress(other.title)
        menuItem("play next")
        waitFor("${other.title} is up next") { player.upcoming.firstOrNull() == other.id }
    }

    @Test fun addToNowPlayingAppends() {
        openCollection("albums")
        tap(TestMedia.SINGLE)
        button("play").performClick()
        waitFor("Delta playing") { player.currentId == id("Delta Tune") }
        openCollection("albums")
        longPress(TestMedia.ALBUM)
        menuItem("add to now playing")
        waitFor("album appended") { player.queue == listOf(id("Delta Tune"), id("Alpha Song"), id("Beta Song"), id("Gamma Song")) }
    }

    @Test fun queueJumpsAndRemoves() {
        openCollection("albums")
        tap(TestMedia.ALBUM)
        button("play").performClick()
        waitFor("Alpha playing") { player.currentId == id("Alpha Song") }
        button("queue").performClick()
        text("queue")
        tap("Gamma Song")
        waitFor("jumped to Gamma") { player.currentId == id("Gamma Song") }
        longPress("Beta Song")
        menuItem("remove from now playing")
        waitFor("Beta removed") { id("Beta Song") !in player.queue }
        assertEquals(listOf(id("Alpha Song"), id("Gamma Song")), player.queue)
    }

    @Test fun mediaKeysControlPlayback() {
        playFromSongs("Alpha Song")
        // Headset-style toggle first, while playback is steady.
        mediaKey(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)
        waitFor("toggled off by media key") { !player.isPlaying }
        mediaKey(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)
        waitFor("toggled on by media key") { player.isPlaying }
        mediaKey(KeyEvent.KEYCODE_MEDIA_PAUSE)
        waitFor("paused by media key") { !player.isPlaying }
        mediaKey(KeyEvent.KEYCODE_MEDIA_PLAY)
        waitFor("playing by media key") { player.isPlaying }
        mediaKey(KeyEvent.KEYCODE_MEDIA_NEXT)
        waitFor("next by media key") { player.currentId == id("Beta Song") }
    }

    @Test fun miniPlayerOnTheHubOpensNowPlaying() {
        playFromSongs("Alpha Song")
        goHome()
        tag("miniplayer").assertIsDisplayed()
        text("Alpha Song")
        tag("miniplayer").performClick()
        waitFor("now playing") { screen == Screen.NowPlaying }
    }

    @Test fun playedAlbumsShowUpInHistory() {
        playFromSongs("Alpha Song")
        goHome()
        swipeNext("panorama")
        text("history")
        text(TestMedia.ALBUM)
        assertFalse(isShown("Music you play will show up here."))
    }
}
