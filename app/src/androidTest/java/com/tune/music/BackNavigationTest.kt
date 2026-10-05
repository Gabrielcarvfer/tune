package com.tune.music

import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.tune.music.support.TestMedia
import com.tune.music.support.TuneTest
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.abs

/** Going back returns to the pivot and the scroll position you left, not the first ones. */
@RunWith(AndroidJUnit4::class)
class BackNavigationTest : TuneTest() {

    // Enough songs for the songs list to scroll.
    override val songs = TestMedia.DEFAULT + (1..24).map {
        TestMedia.Spec("Zz Filler %02d".format(it), "Tune Test Filler", "Tune Test Filler Album", it, 3, 200.0 + it * 13)
    }

    @Test fun backFromAGenreReturnsToGenres() {
        openCollection("artists")
        tag("pivot:current").assertTextEquals("artists")
        swipePrev("pivot:pager")
        tag("pivot:current").assertTextEquals("genres")
        tap("electronic")
        waitFor("genre page") { screen is Screen.GenrePage }
        back()
        waitFor("collection") { screen is Screen.Collection }
        tag("pivot:current").assertTextEquals("genres")
    }

    @Test fun backFromAnArtistReturnsToArtistsNotTheFirstPivotOpened() {
        openCollection("playlists")
        tag("pivot:current").assertTextEquals("playlists")
        swipeNext("pivot:pager")
        swipeNext("pivot:pager")
        tag("pivot:current").assertTextEquals("artists")
        tap(TestMedia.BAND.lowercase())
        waitFor("artist page") { screen is Screen.ArtistPage }
        back()
        waitFor("collection") { screen is Screen.Collection }
        tag("pivot:current").assertTextEquals("artists")
    }

    @Test fun backFromThePlayerKeepsTheScrollPosition() {
        openCollection("songs")
        // Scroll the list down to the last songs.
        repeat(6) {
            if (!isShown("Zz Filler 24")) tag("pivot:pager").performTouchInput { swipeUp() }
            compose.waitForIdle()
        }
        val before = boundsOf(text("Zz Filler 24")).top
        // Playing a song opens the player.
        tap("Zz Filler 24")
        waitFor("now playing") { screen == Screen.NowPlaying }
        waitFor("playing") { player.isPlaying && player.currentId == song("Zz Filler 24").id }
        back()
        waitFor("collection") { screen is Screen.Collection }
        tag("pivot:current").assertTextEquals("songs")
        val after = boundsOf(text("Zz Filler 24")).top
        assertEquals("same scroll position (was $before, now $after)", true, abs(after - before) < 4f)
    }
}

/** Settings → about → open-source licences. */
@RunWith(AndroidJUnit4::class)
class LicensesTest : TuneTest() {
    @Test fun everyComponentAndItsLicenceTextAreShown() {
        tap("settings")
        scrollTo(hasText("open-source licences"))
        tap("open-source licences")
        waitFor("licences") { screen == Screen.Licenses }
        text("Tune is free software under the GNU GPL 3.0", substring = true)
        scrollTo(hasText("Chromaprint 1.5.1"))
        tap("Chromaprint 1.5.1")
        waitFor("licence text") { screen is Screen.LicenseText }
        text("Chromaprint's own source code is licensed under the MIT license", substring = true)
        back()
        scrollTo(hasText("Selawik font"))
        tap("Selawik font")
        text("SIL OPEN FONT LICENSE", substring = true)
    }
}
