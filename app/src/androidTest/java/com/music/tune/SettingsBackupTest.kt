package com.music.tune

import android.net.Uri
import androidx.compose.ui.test.hasText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.music.tune.data.Loudness
import com.music.tune.support.TuneTest
import com.music.tune.ui.theme.Accents
import com.music.tune.ui.theme.DefaultAccent
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Settings → about → backup: everything goes into one JSON file and comes back. */
@RunWith(AndroidJUnit4::class)
class SettingsBackupTest : TuneTest() {

    private val file by lazy { File(ctx.cacheDir, "tune-settings.json").apply { delete() } }
    private val cobalt get() = Accents.first { it.first == "cobalt" }.second

    private fun setUp() {
        onVm {
            setAccent(cobalt)
            setThemeMode(ThemeMode.LIGHT)
            setAcoustIdKey("my-key")
            setAlbumGrid(false)
            setNormalize(false)
            setOffline(true)
            playlists.create("Road trip", listOf(song("Alpha Song").id, song("Beta Song").id))
            matchCache.put(song("Alpha Song"), "AQAAfp", JSONObject("""{"status":"ok","results":[]}"""))
        }
        (ctx.applicationContext as TuneApp).loudness.put(song("Beta Song"), Loudness(-9.5, 0.98, 6_000))
    }

    private fun wipe() {
        onVm {
            setAccent(DefaultAccent)
            setThemeMode(ThemeMode.SYSTEM)
            setAcoustIdKey("")
            setAlbumGrid(true)
            setNormalize(true)
            setOffline(false)
            playlists.playlists.value.forEach { playlists.delete(it.id) }
            clearScan()
        }
        (ctx.applicationContext as TuneApp).loudness.clear()
        waitFor("wiped") { vm.acoustIdKey.value.isEmpty() && runBlocking { vm.scannedCount() } == 0 }
    }

    @Test fun exportThenImportBringsEverythingBack() {
        setUp()
        runBlocking { vm.exportSettings(Uri.fromFile(file)) }
        val json = JSONObject(file.readText())
        assertEquals("tune-settings", json.getString("format"))
        assertEquals("my-key", json.getJSONObject("settings").getJSONObject("acoustid").getString("value"))

        wipe()
        runBlocking { vm.importSettings(Uri.fromFile(file)) }

        // Applied straight away, not after a restart.
        assertEquals(cobalt, vm.accent.value)
        assertEquals(ThemeMode.LIGHT, vm.themeMode.value)
        assertEquals("my-key", vm.acoustIdKey.value)
        assertFalse(vm.albumGrid.value)
        assertFalse(vm.normalize.value)
        assertTrue(vm.offline.value)
        assertEquals(listOf("Road trip"), vm.playlists.playlists.value.map { it.name })
        assertEquals(2, vm.playlists.playlists.value.single().songIds.size)
        assertEquals(-9.5, vm.loudnessOf(song("Beta Song"))!!.lufs, 1e-9)
        assertEquals(1, runBlocking { vm.scannedCount() })
    }

    @Test fun aFileThatIsntAnExportChangesNothing() {
        setUp()
        file.writeText("""{"hello":"world"}""")
        val result = runCatching { runBlocking { vm.importSettings(Uri.fromFile(file)) } }
        assertTrue(result.isFailure)
        assertEquals("my-key", vm.acoustIdKey.value)
        assertEquals(listOf("Road trip"), vm.playlists.playlists.value.map { it.name })
    }

    @Test fun theButtonsAreOnTheAboutPage() {
        openSettings("about")
        scrollTo(hasText("export settings"))
        text("export settings")
        text("import settings")
    }
}
