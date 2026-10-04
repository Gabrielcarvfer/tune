package com.tune.music

import android.view.KeyEvent
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.tune.music.support.TuneTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Hardware keyboard / D-pad use: focus movement, Enter, Escape, typing. */
@RunWith(AndroidJUnit4::class)
class KeyboardTest : TuneTest() {

    private fun focused(m: SemanticsMatcher) =
        compose.onAllNodes(m and isFocused()).fetchSemanticsNodes(atLeastOneRootRequired = false).isNotEmpty()

    /** Presses [code] until the item showing [text] has keyboard focus. */
    private fun moveFocusTo(text: String, code: Int, max: Int = 25) {
        repeat(max) {
            if (focused(hasText(text))) return
            key(code)
        }
        throw AssertionError("couldn't focus \"$text\" with key $code")
    }

    @Test fun tabAndEnterOpenACollectionSection() {
        moveFocusTo("albums", KeyEvent.KEYCODE_TAB)
        key(KeyEvent.KEYCODE_ENTER)
        waitFor("albums pivot") { screen == Screen.Collection(1) }
    }

    @Test fun dpadMovesThroughSongsAndCenterPlays() {
        openCollection("songs")
        moveFocusTo("Beta Song", KeyEvent.KEYCODE_DPAD_DOWN)
        key(KeyEvent.KEYCODE_DPAD_CENTER)
        waitFor("Beta playing") { player.currentId == song("Beta Song").id && player.isPlaying }
    }

    @Test fun escapeClosesTheMenuThenGoesBack() {
        openCollection("songs")
        longPress("Alpha Song")
        text("play next")
        key(KeyEvent.KEYCODE_ESCAPE)
        assertTrue("menu closed", !isShown("play next"))
        assertTrue(screen is Screen.Collection)
        key(KeyEvent.KEYCODE_ESCAPE)
        waitFor("back on the hub") { screen == Screen.Hub }
    }

    @Test fun typingAPlaylistNameAndPressingEnterCreatesIt() {
        openCollection("songs")
        longPress("Gamma Song")
        tap("add to playlist")
        tap("new playlist")
        tag("field:input").assertIsFocused()
        instrumentation.sendStringSync("late night")
        key(KeyEvent.KEYCODE_ENTER)
        waitFor("playlist created") { vm.playlists.playlists.value.any { it.name == "late night" } }
        assertEquals(listOf(song("Gamma Song").id), vm.playlists.playlists.value.single().songIds)
    }

    @Test fun tabMovesBetweenEditorFields() {
        openCollection("songs")
        longPress("Alpha Song")
        tap("edit info")
        waitFor("editor") { isShown("title") }
        tag("field:title").performClick()
        tag("field:title").assertIsFocused()
        key(KeyEvent.KEYCODE_TAB)
        tag("field:artist").assertIsFocused()
        key(KeyEvent.KEYCODE_TAB)
        tag("field:album").assertIsFocused()
    }

    @Test fun typingInSearchWithHardwareKeys() {
        tap("search")
        tag("field:search").performClick()
        instrumentation.sendStringSync("gamma")
        text("Gamma Song")
        assertTrue(!isShown("Alpha Song"))
        key(KeyEvent.KEYCODE_ENTER) // search key: just closes the keyboard
        text("Gamma Song")
    }

    @Test fun backKeyLeavesAnEditorWithoutSaving() {
        openCollection("songs")
        longPress("Beta Song")
        tap("edit info")
        waitFor("editor") { isShown("title") }
        back() // closes the soft keyboard if any, or the page
        if (screen is Screen.EditSong) back()
        waitFor("left editor") { screen is Screen.Collection }
        text("Beta Song")
    }
}
