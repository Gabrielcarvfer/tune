package com.tune.music

import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertHasNoClickAction
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.tune.music.support.TestMedia
import com.tune.music.support.TuneTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Hub, pivots, jump list, album/artist pages and search, driven by touch and keys. */
@RunWith(AndroidJUnit4::class)
class NavigationTest : TuneTest() {

    @Test fun hubShowsPanoramaAndCollectionLinks() {
        text("music")
        text("collection")
        listOf("artists", "albums", "songs", "playlists", "genres", "shuffle all", "search", "settings").forEach { text(it) }
    }

    @Test fun swipingThePanoramaRevealsHistoryAndNew() {
        swipeNext("panorama")
        text("history")
        text("Music you play will show up here.")
        swipeNext("panorama")
        text("new")
        text(TestMedia.ALBUM)
        swipePrev("panorama")
        text("history")
    }

    @Test fun collectionPivotOpensOnTappedSectionAndSwipesAround() {
        tap("albums")
        tag("pivot:current").assertTextEquals("albums")
        text(TestMedia.ALBUM)

        swipeNext("pivot:pager")
        tag("pivot:current").assertTextEquals("songs")
        text("Alpha Song")

        swipePrev("pivot:pager")
        swipePrev("pivot:pager")
        tag("pivot:current").assertTextEquals("artists")

        // The pivot wraps around like on Windows Phone: artists <- genres.
        swipePrev("pivot:pager")
        tag("pivot:current").assertTextEquals("genres")
        text("ambient")
        text("electronic")
    }

    @Test fun tappingAPivotHeaderJumpsToIt() {
        tap("artists")
        tag("pivot:playlists").performClick()
        compose.waitForIdle()
        tag("pivot:current").assertTextEquals("playlists")
        text("new playlist")
    }

    @Test fun backKeyReturnsThroughTheStack() {
        tap("albums")
        tap(TestMedia.ALBUM)
        text(TestMedia.BAND.uppercase())
        back()
        tag("pivot:current").assertTextEquals("albums")
        back()
        text("collection")
        assertEquals(Screen.Hub, screen)
    }

    @Test fun jumpListGridJumpsToALetter() {
        openCollection("songs")
        tag("jump:a").performClick()
        compose.waitForIdle()
        // Letters with songs are live tiles, the others are greyed out.
        tag("jumpgrid:d").assertHasClickAction()
        tag("jumpgrid:z").assertHasNoClickAction()
        tag("jumpgrid:d").performClick()
        compose.waitForIdle()
        text("Delta Tune")
        assertTrue("grid closed", compose.onAllNodesWithTagCount("jumpgrid:d") == 0)
    }

    @Test fun albumPageListsTracksInDiscOrder() {
        openCollection("albums")
        tap(TestMedia.ALBUM)
        val ys = listOf("Alpha Song", "Beta Song", "Gamma Song").map { boundsOf(text(it)).top }
        assertEquals(ys.sorted(), ys)
        text("3 songs")
        text("2020")
    }

    @Test fun artistPageSwipesBetweenAlbumsAndSongs() {
        openCollection("artists")
        tap(TestMedia.SOLO.lowercase())
        text(TestMedia.SOLO.uppercase())
        tag("pivot:current").assertTextEquals("albums")
        text(TestMedia.SINGLE)
        swipeNext("pivot:pager")
        tag("pivot:current").assertTextEquals("songs")
        text("Delta Tune")
    }

    @Test fun searchFindsArtistsAlbumsAndSongsAsYouType() {
        tap("search")
        tag("field:search").performTextInput("tune test")
        text("artists")
        text(TestMedia.ALBUM)
        scrollTo(hasText("Alpha Song"))
        text("Alpha Song")

        tag("field:search").performTextReplacement("beta")
        text("Beta Song")
        assertNotShown("Alpha Song")

        tag("field:search").performTextReplacement("no such thing")
        text("No results.")

        // The keyboard's search key just closes the keyboard.
        tag("field:search").performTextReplacement("delta")
        tag("field:search").performImeAction()
        tap("Delta Tune")
        waitFor("Delta playing") { player.currentId == song("Delta Tune").id }
    }

    @Test fun settingsSwitchesThemeAndAccent() {
        tap("settings")
        assertEquals("follows the system by default", ThemeMode.SYSTEM, vm.themeMode.value)
        tap("light")
        waitFor("light theme") { vm.themeMode.value == ThemeMode.LIGHT }
        tap("magenta")
        waitFor("magenta accent") { vm.accent.value == com.tune.music.ui.theme.Accents.first { it.first == "magenta" }.second }
        tap("dark")
        waitFor("dark theme") { vm.themeMode.value == ThemeMode.DARK }
        tap("system")
        waitFor("system theme") { vm.themeMode.value == ThemeMode.SYSTEM }

        scrollTo(hasText("accent colour for titles"))
        tap("accent colour for titles")
        waitFor("accent titles on") { vm.accentTitles.value }
        goHome()
        text("music") // panorama title, now drawn in the accent colour
        assertTrue(vm.accentTitles.value)
    }

    private fun androidx.compose.ui.test.junit4.ComposeTestRule.onAllNodesWithTagCount(tag: String) =
        onAllNodes(androidx.compose.ui.test.hasTestTag(tag)).fetchSemanticsNodes(atLeastOneRootRequired = false).size
}
