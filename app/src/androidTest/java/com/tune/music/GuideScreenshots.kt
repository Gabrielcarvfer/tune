package com.tune.music

import android.content.SharedPreferences
import android.graphics.RectF
import android.os.Environment
import android.os.SystemClock
import android.view.inputmethod.InputMethodManager
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextReplacement
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.Until
import com.tune.music.data.LibraryFolder
import com.tune.music.data.Organizer
import com.tune.music.support.FakeHttp
import com.tune.music.support.FakeHttp.Companion.Rel
import com.tune.music.support.GuideMedia
import com.tune.music.support.TestMedia
import com.tune.music.support.TuneTest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.AssumptionViolatedException
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestRule
import org.junit.runner.RunWith
import java.io.File
import java.util.regex.Pattern

/**
 * Takes the screenshots for the README's usage guide, and records where the
 * things to tap are; tools/guide/annotate.py then draws the numbered arrows.
 *
 * Skipped unless asked for (see tools/guide/README.md):
 * -Pandroid.testInstrumentationRunnerArguments.guide=true
 */
@RunWith(AndroidJUnit4::class)
class GuideScreenshots : TuneTest() {

    @get:Rule(order = -1)
    val onlyWhenAsked = TestRule { base, _ ->
        if (InstrumentationRegistry.getArguments().getString("guide") != "true") {
            object : org.junit.runners.model.Statement() {
                override fun evaluate() = throw AssumptionViolatedException("guide screenshots not requested")
            }
        } else base
    }

    override val songs get() = GuideMedia.SONGS

    override fun prefs(e: SharedPreferences.Editor) {
        e.putString("theme", "DARK")
        e.putBoolean("albumGrid", true)
        // Only the made-up songs, whatever else is on the device.
        e.putString("libraryFolder", File(TestMedia.musicDir(), TestMedia.IN_DIR).path)
    }

    // --- capturing ----------------------------------------------------------

    /** Something to tap, numbered [n]; [side] places its badge (left/right/above/below) when the default spot is crowded. */
    private class Mark(val n: Int, val box: RectF, val side: String? = null)

    private val out by lazy { File(ctx.getExternalFilesDir(null), "guide").apply { mkdirs() } }

    /** Where the app's window sits on the screen (Compose bounds are window-relative). */
    private fun windowOffset(): IntArray {
        val loc = IntArray(2)
        scenario.onActivity { it.window.decorView.getLocationOnScreen(loc) }
        return loc
    }

    private fun mark(n: Int, node: SemanticsNodeInteraction, side: String? = null): Mark {
        val b = node.fetchSemanticsNode().boundsInWindow
        val (x, y) = windowOffset().let { it[0] to it[1] }
        return Mark(n, RectF(b.left + x, b.top + y, b.right + x, b.bottom + y), side)
    }

    private fun mark(n: Int, o: UiObject2) = Mark(n, RectF(o.visibleBounds))

    /**
     * Types into a field without leaving it focused (a focused field brings up
     * the keyboard's floating toolbar, which would sit in the screenshot).
     */
    private fun type(fieldTag: String, value: String) {
        tag(fieldTag).performTextReplacement(value)
        scenario.onActivity { a ->
            a.window.decorView.clearFocus()
            a.getSystemService(InputMethodManager::class.java).hideSoftInputFromWindow(a.window.decorView.windowToken, 0)
        }
        compose.waitForIdle()
    }

    private fun shot(name: String, vararg marks: Mark, inApp: Boolean = true) {
        if (inApp) compose.waitForIdle()
        device.waitForIdle()
        SystemClock.sleep(1_000) // covers load, ripples and toasts fade
        check(device.takeScreenshot(File(out, "$name.png"))) { "screenshot $name" }
        val json = JSONObject()
            .put("width", device.displayWidth).put("height", device.displayHeight)
            .put("marks", JSONArray().apply {
                marks.forEach { m ->
                    put(JSONObject().put("n", m.n).put("side", m.side)
                        .put("left", m.box.left).put("top", m.box.top).put("right", m.box.right).put("bottom", m.box.bottom))
                }
            })
        File(out, "$name.json").writeText(json.toString(2))
        // Gradle uninstalls the app (and its files) after the run; keep a copy.
        device.executeShellCommand("mkdir -p $KEEP")
        device.executeShellCommand("cp ${out.path}/$name.png ${out.path}/$name.json $KEEP/")
    }

    companion object {
        /** Where the screenshots end up on the device (tools/guide/capture.sh pulls them). */
        const val KEEP = "/data/local/tmp/tune-guide"
    }

    private fun systemText(regex: String, timeoutMs: Long = 10_000): UiObject2 =
        device.wait(Until.findObject(By.text(Pattern.compile(regex))), timeoutMs)
            ?: throw AssertionError("no \"$regex\" on screen")

    /** Scrolls the settings list so the section titled [title] is at the top. */
    private fun settingsAt(title: String) {
        goHome()
        tap("settings")
        waitFor("settings") { screen == Screen.Settings }
        val list = compose.onAllNodes(hasScrollAction()).onFirst()
        // Sections near the end can't reach the top: then the list stays at its end.
        for (i in 0..40) {
            if (runCatching { list.performScrollToIndex(i) }.isFailure) break
            compose.waitForIdle()
            if (isShown(title) && boundsOf(text(title)).top < 300) return
        }
        text(title)
    }

    // --- the guide ----------------------------------------------------------

    @Test fun musicFolder() {
        shot("folder-1", mark(1, text("settings")))

        settingsAt("music folder")
        shot("folder-2", mark(2, text("choose folder"), side = "below"))

        // Android's own folder picker, then its permission question.
        tap("choose folder")
        val use = systemText("(?i)use this folder")
        shot("folder-3", mark(3, use), inApp = false)
        use.click()
        val allow = systemText("(?i)allow")
        shot("folder-4", mark(4, allow), inApp = false)
        allow.click()
        device.wait(Until.hasObject(By.pkg(ctx.packageName)), 5_000)

        // A folder outside Music (like a synced one) needs all files access to
        // organize inside it.
        if (!Environment.isExternalStorageManager()) {
            onVm { setLibraryFolder(LibraryFolder(Organizer.primaryRoot, "Sync/Music")) }
            settingsAt("music folder")
            shot("folder-5", mark(5, text("allow all files access")))
            tap("allow all files access")
            val toggle = systemText("(?i)allow access to manage all files")
            // The whole row, switch included.
            shot("folder-6", mark(6, toggle.parent ?: toggle), inApp = false)
            device.pressBack()
            device.wait(Until.hasObject(By.pkg(ctx.packageName)), 5_000)
        }
    }

    @Test fun editingInfo() {
        openCollection("songs")
        scrollTo(hasText("Harbour Lights"))
        longPress("Harbour Lights")
        shot("edit-1", mark(1, text("edit info")))
        tap("edit info")
        waitFor("editor") { screen is Screen.EditSong && isShown("Harbour Lights") }
        type("field:genre", "Indie Folk")
        shot("edit-2", mark(2, tag("field:genre")), mark(3, button("save")))
        button("cancel").performClick()

        openCollection("albums")
        tag("albums:grid").performScrollToNode(hasText("Night Ferry"))
        tap("Night Ferry")
        waitFor("album page") { screen is Screen.AlbumPage }
        tag("appbar:more").performClick()
        compose.waitForIdle()
        shot("edit-3", mark(4, tag("appbar:more")), mark(5, text("edit album info")))
        tap("edit album info")
        waitFor("album editor") { screen is Screen.EditAlbum && isShown("tap to choose", substring = true) }
        shot("edit-4", mark(6, text("tap to choose", substring = true)), mark(7, tag("field:album artist")), mark(8, button("save")))
    }

    @Test fun findingInfoOnline() {
        serveRelease()
        settingsAt("finding info online")
        type("field:acoustid api key", "your-api-key")
        shot("acoustid-1", mark(1, text("get a key")), mark(2, tag("field:acoustid api key")), mark(3, text("save key")))
        tap("save key")
        SystemClock.sleep(3_000) // the "key saved" toast fades

        openCollection("albums")
        tag("albums:grid").performScrollToNode(hasText(GuideMedia.MESSY_ALBUM))
        tap(GuideMedia.MESSY_ALBUM)
        waitFor("album page") { screen is Screen.AlbumPage }
        tag("appbar:more").performClick()
        compose.waitForIdle()
        shot("acoustid-2", mark(4, tag("appbar:more")), mark(5, text("find album info online")))
        tap("find album info online")

        text("matches 3 of 3 songs")
        shot("acoustid-3", mark(6, text(GuideMedia.RELEASE)))
        tap(GuideMedia.RELEASE)
        text("front")
        SystemClock.sleep(3_000) // cover thumbnails
        shot("acoustid-4", mark(7, text("front")), mark(8, button("apply")))
    }

    /** AcoustID and the Cover Art Archive, answering for the badly tagged album. */
    private fun serveRelease() {
        val full = { i: Int -> Rel("rel-ps", "rg-ps", GuideMedia.RELEASE, GuideMedia.MESSY_ARTIST, 2018, i + 1, 3, trackTitle = GuideMedia.RELEASE_TRACKS[i]) }
        GuideMedia.RELEASE_TRACKS.forEachIndexed { i, t ->
            val others = if (i == 0) arrayOf(Rel("rel-lns", "rg-lns", "Late Night Signals", "Various Artists", 2021, 7, 18, trackTitle = t, type = "Compilation"))
            else emptyArray()
            fake.lookupResponses += FakeHttp.lookup(t, GuideMedia.MESSY_ARTIST, full(i), *others)
        }
        // Thumbnails are shown straight from their URL, so they're local files.
        val front = GuideMedia.cover(0xFF0D1B2A, 0xFF3A86FF, 0)
        val back = GuideMedia.cover(0xFF3A86FF, 0xFF0D1B2A, 3)
        val images = JSONArray()
        listOf(Triple("front", front, true), Triple("back", back, false)).forEach { (type, bytes, isFront) ->
            val file = File(ctx.cacheDir, "guide-$type.jpg").apply { writeBytes(bytes) }
            val url = "https://img.test/$type.jpg"
            fake.pages[url] = bytes
            images.put(JSONObject().put("image", url).put("front", isFront).put("types", JSONArray().put(type.replaceFirstChar { it.uppercase() }))
                .put("thumbnails", JSONObject().put("250", "file://${file.path}").put("1200", url)))
        }
        fake.pages["https://coverartarchive.org/release/rel-ps"] = JSONObject().put("images", images).toString().toByteArray()
    }
}
