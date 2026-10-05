package com.tune.music

import android.content.SharedPreferences
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.tune.music.data.TagLib
import com.tune.music.support.FakeHttp
import com.tune.music.support.FakeHttp.Companion.Rel
import com.tune.music.support.TestMedia
import com.tune.music.support.TuneTest
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Scanning the collection once (saved), and consolidating an anniversary
 * edition whose songs were each filed under their original soundtrack.
 */
@RunWith(AndroidJUnit4::class)
class ConsolidateTest : TuneTest() {

    private val composer = "Tune Test Composer"
    private val anniversary = "Tune Test 25th Anniversary"

    override val songs = listOf(
        TestMedia.Spec("Hell March", composer, "Tune Test Red Alert", 1, 6, 300.0),
        TestMedia.Spec("Grinder", composer, "Tune Test Counterstrike", 1, 6, 350.0),
        TestMedia.Spec("Bigfoot", composer, "Tune Test Aftermath", 1, 6, 400.0),
        TestMedia.Spec("Lonely", "Tune Test Other", "Tune Test Elsewhere", 1, 6, 450.0),
    )

    override fun prefs(e: SharedPreferences.Editor) {
        e.putString("acoustid", "testkey")
    }

    private fun anniv(track: Int, title: String) = Rel("anniv", "rg-anniv", anniversary, composer, 2021, track, 3, trackTitle = title)

    /** AcoustID answers for each song. */
    private fun serveLookups() {
        val answers = mapOf(
            // Each song's best match is its original soundtrack; all three are on the anniversary release.
            "Hell March" to FakeHttp.lookup("Hell March", composer, Rel("ost-ra", "rg-ra", "Tune Test Red Alert OST", composer, 1996, 1, 15), anniv(1, "Hell March")),
            "Grinder" to FakeHttp.lookup("Grinder", composer, Rel("ost-cs", "rg-cs", "Tune Test Counterstrike OST", composer, 1997, 3, 10), anniv(2, "Grinder")),
            "Bigfoot" to FakeHttp.lookup("Bigfoot", composer, Rel("ost-am", "rg-am", "Tune Test Aftermath OST", composer, 1997, 2, 10), anniv(3, "Bigfoot")),
            "Lonely" to FakeHttp.lookup("Lonely", "Tune Test Other", Rel("solo", "rg-solo", "Tune Test Elsewhere", "Tune Test Other", 2000, 1, 1)),
        )
        answers.forEach { (title, answer) -> answerLookup(title, answer) }
    }

    private val posts get() = fake.requests.count { it.startsWith("POST") }

    private fun scan() {
        goHome()
        tap("settings")
        scrollTo(hasText("scan collection"))
        tap("scan collection")
        waitFor("scan finished", 60_000) { vm.scan.value.let { !it.running && it.already + it.done == 4 } }
    }

    @Test fun scanningLooksEverySongUpOnceAndSavesIt() {
        serveLookups()
        scan()
        assertEquals(4, posts)
        assertEquals(4, runBlocking { vm.scannedCount() })
        scrollTo(hasText("4 of 4 songs scanned"))
        text("4 of 4 songs scanned")

        // Saved: a second scan has nothing to do, and finding info for a song asks nobody.
        onVm { scanCollection() }
        waitFor("second scan done") { !vm.scan.value.running }
        assertEquals(4, posts)
        openCollection("songs")
        longPress("Grinder")
        menuItem("find info online")
        text(anniversary)
        assertEquals(4, posts)
    }

    @Test fun aSongPrefersTheReleaseMostOfTheCollectionIsOn() {
        serveLookups()
        scan()
        openCollection("songs")
        longPress("Hell March")
        menuItem("find info online")
        // Its own lookup ranks the original soundtrack first, but the collection
        // has three songs of the anniversary edition.
        assertTrue(boundsOf(text(anniversary)).top < boundsOf(text("Tune Test Red Alert OST")).top)
    }

    @Test fun consolidatingGathersTheSplitAlbums() {
        serveLookups()
        scan()
        scrollTo(hasText("consolidate albums"))
        tap("consolidate albums")
        waitFor("consolidate screen") { screen == Screen.Consolidate }
        text("1 release gathers split albums. Tap it to review it.")
        text("3 songs from: Tune Test Red Alert, Tune Test Counterstrike, Tune Test Aftermath")
        tap(anniversary)
        text("01  Hell March")
        text("03  Bigfoot")
        button("apply").performClick()
        allowSystemDialog()

        waitFor("songs gathered", 30_000) {
            vm.library.value.songs.count { it.album == anniversary && it.albumArtist == composer } == 3
        }
        waitFor("tags written", 20_000) {
            runCatching { TagLib.nativeRead(TestMedia.pathOf(ctx, uris.getValue("Bigfoot"))!!)!![TagLib.TRACK] }.getOrNull() == "3"
        }
        assertEquals("Tune Test Elsewhere", vm.library.value.songs.first { it.title == "Lonely" }.album)
        // Back on the list, which has nothing left to merge.
        waitFor("back on consolidate") { screen == Screen.Consolidate }
        text("No albums to merge", substring = true, timeoutMs = 20_000)
    }

    @Test fun theScanIsKeptAcrossRestarts() {
        serveLookups()
        scan()
        scenario.close()
        scenario = ActivityScenario.launch(MainActivity::class.java)
        waitFor("library loaded") { vm.library.value.songs.size >= 4 }
        assertEquals(4, runBlocking { vm.scannedCount() })
    }

    @Test fun aBadKeyStopsTheScan() {
        fake.lookupResponses += FakeHttp.error("invalid API key")
        scan0()
        waitFor("stopped") { !vm.scan.value.running && vm.scan.value.error != null }
        text("Stopped: acoustid: invalid API key")
        assertEquals(1, posts)
    }

    private fun scan0() {
        tap("settings")
        scrollTo(hasText("scan collection"))
        tap("scan collection")
    }

    @Test fun scanningAgainOnlyDoesTheSongsLeft() {
        serveLookups()
        // Two songs were scanned before (say, by a scan that was stopped).
        val empty = org.json.JSONObject("""{"status":"ok","results":[]}""")
        listOf("Hell March", "Grinder").forEach { vm.matchCache.put(song(it), "AQAAsaved", empty) }
        scan()
        assertEquals("only the two songs left were looked up", 2, posts)
        assertEquals(4, runBlocking { vm.scannedCount() })
        scrollTo(hasText("4 of 4 songs scanned"))
        text("4 of 4 songs scanned")
        assertEquals(2, vm.scan.value.already)
    }

    @Test fun forgettingTheScanDeletesTheSavedAnswers() {
        serveLookups()
        scan()
        scrollTo(hasText("forget scan"))
        tap("forget scan")
        waitFor("forgotten") { runBlocking { vm.scannedCount() } == 0 }
        text("0 of 4 songs scanned")
    }

    @Test fun consolidateWithoutScanningAsksForAScan() {
        onVm { navigate(Screen.Consolidate) }
        text("Scan the collection first.")
    }
}
