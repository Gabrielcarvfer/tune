package com.music.tune

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.performTouchInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.music.tune.support.TuneTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.abs

/** The long-press menu opens next to the item, keeps it visible, and closes. */
@RunWith(AndroidJUnit4::class)
class ContextMenuTest : TuneTest() {

    private val screenHeight get() = ctx.resources.displayMetrics.heightPixels

    private fun menuShown() =
        compose.onAllNodes(hasTestTag("overlay:menu")).fetchSemanticsNodes(atLeastOneRootRequired = false).isNotEmpty()

    /** Long-presses [title] and checks the menu hugs it from the right side. */
    private fun assertMenuAnchoredTo(title: String) {
        val item = boundsOf(text(title))
        longPress(title)
        val below = item.center.y < screenHeight / 2f
        // The menu slides in; wait for it to settle before measuring.
        try {
            waitFor("menu settled next to $title", 5_000) {
                val m = boundsOf(tag("overlay:menu"))
                if (below) abs(m.top - item.bottom) < 2f else abs(m.bottom - item.top) < 2f
            }
        } catch (e: AssertionError) {
            throw AssertionError("menu ${boundsOf(tag("overlay:menu"))} not next to $title $item (below=$below, screen=$screenHeight)", e)
        }
        val menu = boundsOf(tag("overlay:menu"))
        // The pressed item isn't covered by the menu.
        assertTrue(menu.bottom <= item.top + 2 || menu.top >= item.bottom - 2)
    }

    @Test fun menuOpensBelowAnItemInTheUpperHalf() {
        openCollection("songs")
        assertMenuAnchoredTo("Alpha Song")
        text("play next")
        text("add to playlist")
        text("delete")
    }

    @Test fun menuOpensAboveAnItemInTheLowerHalf() {
        openCollection("songs")
        val gamma = boundsOf(text("Gamma Song"))
        // Make sure the case is real: Gamma sits low on the screen.
        assertTrue("Gamma is in the lower half (${gamma.center.y} of $screenHeight)", gamma.center.y > screenHeight / 2f)
        assertMenuAnchoredTo("Gamma Song")
    }

    @Test fun menuForAnAlbumOffersAlbumActions() {
        openCollection("albums")
        assertMenuAnchoredTo(com.music.tune.support.TestMedia.ALBUM)
        text("edit album info")
        text("find album info online")
        text("organize files")
    }

    @Test fun tappingOutsideClosesTheMenu() {
        openCollection("songs")
        longPress("Alpha Song")
        tag("overlay:menu").assertIsDisplayed()
        // Tap near the bottom of the dimmed area.
        tag("overlay:scrim").performTouchInput { click(androidx.compose.ui.geometry.Offset(centerX, bottom - 20f)) }
        compose.waitForIdle()
        assertTrue(!menuShown())
    }

    @Test fun backKeyClosesTheMenuNotThePage() {
        openCollection("songs")
        longPress("Beta Song")
        back()
        assertTrue(!menuShown())
        text("Beta Song")
        assertTrue(screen is Screen.Collection)
    }

    @Test fun choosingAnItemRunsItAndCloses() {
        openCollection("songs")
        longPress("Beta Song")
        menuItem("play")
        waitFor("Beta playing") { player.currentId == song("Beta Song").id && player.isPlaying }
        assertTrue(!menuShown())
        assertEquals(Screen.NowPlaying, screen)
    }

    @Test fun appBarMenuStillOpensFromTheBar() {
        openCollection("albums")
        tap(com.music.tune.support.TestMedia.ALBUM)
        tag("appbar:more").performTouchInput { click() }
        compose.waitForIdle()
        text("edit album info")
        assertTrue("no context menu for the app bar", !menuShown())
        assertTrue(abs(boundsOf(text("edit album info")).top) > 0)
    }
}
