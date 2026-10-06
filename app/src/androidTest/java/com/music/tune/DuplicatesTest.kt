package com.music.tune

import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.music.tune.support.TestMedia
import com.music.tune.support.TuneTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

/** Settings → collection → find duplicates: copies found by sound, previewed, ticked and deleted. */
@RunWith(AndroidJUnit4::class)
class DuplicatesTest : TuneTest() {

    // The same recording saved twice, with other tags and in another album; and
    // a different song by the same artist. Long enough to compare (10 s or more).
    override val songs = listOf(
        TestMedia.Spec("Crystal Dolphin", "Engelwood", "Tune Test Crust FM", 1, 15, 392.0),
        TestMedia.Spec("Crystal Dolphin (Copy)", "Engelwood", "Tune Test Yacht World", 3, 15, 392.0),
        TestMedia.Spec("Another Song", "Engelwood", "Tune Test Crust FM", 2, 15, 659.25),
    )

    private val original get() = song("Crystal Dolphin")
    private val copy get() = song("Crystal Dolphin (Copy)")

    private fun openDuplicates() {
        openSettings("collection")
        scrollTo(hasText("find duplicates"))
        tap("find duplicates")
        waitFor("search done", 60_000) { vm.duplicates.value.let { !it.running && it.groups != null } }
    }

    @Test fun copiesAreFoundBySoundWhateverTheirTags() {
        openDuplicates()
        text("1 song has copies")
        tag("dup:tick:${original.id}")
        tag("dup:tick:${copy.id}")
        assertFalse("a different song isn't a copy", isShown("Another Song"))
        assertEquals(listOf(setOf(original.id, copy.id)), vm.duplicates.value.groups!!.map { g -> g.map { it.id }.toSet() })
        // The fingerprints are saved like a scan's, for next time.
        assertNotNull(vm.matchCache.get(original)?.fingerprint)
    }

    @Test fun eachCopyCanBePreviewedWithoutTouchingTheQueue() {
        onVm { player.play(listOf(song("Another Song"))) }
        waitFor("playing") { player.currentId == song("Another Song").id }
        openDuplicates()
        tag("dup:listen:${copy.id}").performClick()
        compose.onNode(hasTestTag("dup:listen:${copy.id}") and hasContentDescription("stop")).assertExists()
        // The preview has a player of its own: the queue still has the song it had.
        assertEquals(song("Another Song").id, player.currentId)
        tag("dup:listen:${copy.id}").performClick()
        compose.onNode(hasTestTag("dup:listen:${copy.id}") and hasContentDescription("listen")).assertExists()
    }

    @Test fun theTickedCopyIsDeleted() {
        openDuplicates()
        tag("dup:tick:${copy.id}").performClick()
        compose.onNodeWithContentDescription("delete").performClick()
        text("\"Crystal Dolphin (Copy)\" will be deleted from your phone.")
        tap("delete")
        allowSystemDialog()
        waitFor("copy deleted") { vm.library.value.songs.none { it.title == "Crystal Dolphin (Copy)" } }
        text("No duplicates: every song is here once.")
        assertNull(vm.library.value.songs.firstOrNull { it.title == "Crystal Dolphin (Copy)" })
        assertEquals("the original stays", 1, vm.library.value.songs.count { it.title == "Crystal Dolphin" })
    }

    @Test fun tickingEveryCopyWarns() {
        openDuplicates()
        tag("dup:tick:${original.id}").performClick()
        tag("dup:tick:${copy.id}").performClick()
        text("Every copy is ticked: deleting them all leaves none.")
        tag("dup:tick:${copy.id}").performClick()
        assertFalse(isShown("Every copy is ticked: deleting them all leaves none."))
    }

    @Test fun deletingWithNothingTickedSaysWhatToDo() {
        openDuplicates()
        compose.onNodeWithContentDescription("delete").performClick()
        text("tick the copies to delete")
    }
}
