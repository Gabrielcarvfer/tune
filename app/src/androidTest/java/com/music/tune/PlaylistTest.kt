package com.music.tune

import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.music.tune.support.TestMedia
import com.music.tune.support.TuneTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PlaylistTest : TuneTest() {

    private fun id(title: String) = song(title).id
    private fun playlist(name: String) = vm.playlists.playlists.value.firstOrNull { it.name == name }

    private fun openPlaylists() {
        openCollection("playlists")
    }

    @Test fun createPlaylistFromTheSongMenuWithTheKeyboard() {
        openCollection("songs")
        longPress("Alpha Song")
        menuItem("add to playlist")
        tap("new playlist")
        tag("field:input").performTextInput("road trip")
        tag("field:input").performImeAction()
        text("playlist created")
        assertEquals(listOf(id("Alpha Song")), playlist("road trip")?.songIds)

        tag("pivot:playlists").performClick()
        compose.waitForIdle()
        text("road trip")
        text("1 song")
    }

    @Test fun addToAnExistingPlaylistFromThePicker() {
        onVm { playlists.create("mix", listOf(song("Alpha Song").id)) }
        openCollection("songs")
        longPress("Delta Tune")
        menuItem("add to playlist")
        tap("mix")
        text("added to playlist")
        assertEquals(listOf(id("Alpha Song"), id("Delta Tune")), playlist("mix")?.songIds)
    }

    @Test fun reorderAndRemoveSongs() {
        onVm { playlists.create("mix", listOf("Alpha Song", "Beta Song", "Gamma Song").map { t -> song(t).id }) }
        openPlaylists()
        tap("mix")
        longPress("Gamma Song")
        menuItem("move up")
        waitFor("Gamma moved up") { playlist("mix")?.songIds == listOf(id("Alpha Song"), id("Gamma Song"), id("Beta Song")) }
        val ys = listOf("Alpha Song", "Gamma Song", "Beta Song").map { boundsOf(text(it)).top }
        assertEquals(ys.sorted(), ys)

        longPress("Alpha Song")
        menuItem("remove from playlist")
        waitFor("Alpha removed") { playlist("mix")?.songIds == listOf(id("Gamma Song"), id("Beta Song")) }
        assertNotShown("Alpha Song")
    }

    @Test fun renameAndDeleteFromTheAppBar() {
        onVm { playlists.create("mix", listOf(song("Beta Song").id)) }
        openPlaylists()
        tap("mix")
        tag("appbar:more").performClick()
        menuItem("rename")
        tag("field:input").performTextReplacement("evening")
        tap("save")
        text("evening")
        assertTrue(playlist("evening") != null)

        tag("appbar:more").performClick()
        menuItem("delete playlist")
        tap("delete")
        waitFor("playlist gone") { vm.playlists.playlists.value.isEmpty() }
        waitFor("left the deleted playlist") { screen is Screen.Collection }
    }

    @Test fun playAndShuffleAPlaylist() {
        onVm { playlists.create("mix", listOf("Gamma Song", "Alpha Song").map { t -> song(t).id }) }
        openPlaylists()
        tap("mix")
        button("play").performClick()
        waitFor("playlist plays in order") { player.currentId == id("Gamma Song") && player.queue == listOf(id("Gamma Song"), id("Alpha Song")) }
        back()
        button("shuffle").performClick()
        waitFor("shuffled") { player.shuffle && player.queue.size == 2 }
    }

    @Test fun saveNowPlayingAsAPlaylist() {
        openCollection("albums")
        tap(TestMedia.ALBUM)
        button("play").performClick()
        waitFor("playing") { player.currentId == id("Alpha Song") }
        tag("appbar:more").performClick()
        menuItem("save now playing as playlist")
        tag("field:input").performTextInput("whole album")
        tap("save")
        waitFor("saved") { playlist("whole album")?.songIds == listOf("Alpha Song", "Beta Song", "Gamma Song").map { id(it) } }
    }

    @Test fun playlistsSurviveAnAppRestart() {
        openCollection("songs")
        longPress("Beta Song")
        menuItem("add to playlist")
        tap("new playlist")
        tag("field:input").performTextInput("keepers")
        tap("create")
        text("playlist created")

        scenario.close()
        scenario = ActivityScenario.launch(MainActivity::class.java)
        waitFor("library") { vm.library.value.songs.isNotEmpty() }
        openPlaylists()
        text("keepers")
        tap("keepers")
        text("Beta Song")
    }

    @Test fun deletedSongsDisappearFromPlaylists() {
        onVm { playlists.create("mix", listOf("Alpha Song", "Delta Tune").map { t -> song(t).id }) }
        val delta = id("Delta Tune")
        openCollection("songs")
        longPress("Delta Tune")
        menuItem("delete")
        tap("delete")
        allowSystemDialog()
        waitFor("Delta deleted") { vm.library.value.songs.none { it.id == delta } }
        waitFor("pruned from playlist") { playlist("mix")?.songIds == listOf(id("Alpha Song")) }
    }
}
