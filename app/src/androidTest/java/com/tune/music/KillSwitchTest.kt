package com.tune.music

import android.content.SharedPreferences
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.tune.music.data.MusicBrainz
import com.tune.music.data.Net
import com.tune.music.support.TestMedia
import com.tune.music.support.TuneTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** With the network kill switch on, nothing goes online, whatever the user does. */
@RunWith(AndroidJUnit4::class)
class KillSwitchTest : TuneTest() {

    override fun prefs(e: SharedPreferences.Editor) {
        e.putString("acoustid", "testkey").putBoolean("offline", true)
    }

    private fun findAlbumInfo() {
        openCollection("albums")
        tap(TestMedia.ALBUM)
        tag("appbar:more").performClick()
        menuItem("find album info online")
    }

    @Test fun identifyingIsRefused() {
        assertTrue(Net.blocked)
        findAlbumInfo()
        text("Couldn't identify: web requests are turned off", substring = true)
        assertTrue(fake.requests.isEmpty())
    }

    @Test fun searchingByNameIsRefused() {
        fake.pages[MusicBrainz.searchUrl(TestMedia.ALBUM, TestMedia.BAND)] = """{"releases":[]}""".toByteArray()
        findAlbumInfo()
        text("Couldn't identify", substring = true)
        tap("search by name")
        tap("search")
        text("Couldn't search: web requests are turned off", substring = true)
        assertTrue(fake.requests.isEmpty())
    }

    @Test fun scanningIsRefused() {
        openSettings("collection")
        scrollTo(hasText("scan collection"))
        tap("scan collection")
        text("web requests are turned off")
        assertFalse(vm.scan.value.running)
        assertTrue(fake.requests.isEmpty())
    }

    @Test fun turningItOffAllowsRequestsAgain() {
        openSettings("network")
        scrollTo(hasText("network kill switch"))
        text("On: Tune makes no web requests at all", substring = true)
        tap("network kill switch")
        waitFor("kill switch off") { !vm.offline.value }
        assertFalse(Net.blocked)
        text("Tune only goes online when you use finding info online", substring = true)
        tap("network kill switch")
        waitFor("kill switch on") { vm.offline.value }
        assertTrue(Net.blocked)
    }

    @Test fun aboutNamesTheWebServices() {
        openSettings("about")
        scrollTo(hasText("privacy policy"))
        text("AcoustID identifies recordings from audio fingerprints", substring = true)
        text("Cover Art Archive", substring = true)
    }
}
