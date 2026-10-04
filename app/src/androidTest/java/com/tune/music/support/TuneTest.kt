package com.tune.music.support

import android.Manifest
import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import android.view.KeyEvent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import com.tune.music.MainActivity
import com.tune.music.MainViewModel
import com.tune.music.Screen
import com.tune.music.data.Net
import com.tune.music.data.Song
import com.tune.music.data.UrlConnectionHttp
import com.tune.music.playback.PlayerState
import org.junit.After
import org.junit.Before
import org.junit.Rule
import java.io.File
import java.util.regex.Pattern

/**
 * Base for the functional tests: fresh app state and test songs for every
 * test, the real activity and playback service, fake network.
 */
abstract class TuneTest {
    @get:Rule(order = 0)
    val permissions: GrantPermissionRule = GrantPermissionRule.grant(
        Manifest.permission.READ_MEDIA_AUDIO,
        Manifest.permission.POST_NOTIFICATIONS,
    )

    @get:Rule(order = 1)
    val compose = createEmptyComposeRule()

    protected val instrumentation = InstrumentationRegistry.getInstrumentation()
    protected val ctx: Context = instrumentation.targetContext
    protected val device: UiDevice = UiDevice.getInstance(instrumentation)
    protected val fake = FakeHttp()
    protected lateinit var scenario: ActivityScenario<MainActivity>
    protected val uris = LinkedHashMap<String, Uri>()

    /** Songs present for the test; most tests use the default four. */
    protected open val songs: List<TestMedia.Spec> = TestMedia.DEFAULT

    /** Preferences to set before the app starts. */
    protected open fun prefs(e: SharedPreferences.Editor) {}

    @Before
    fun startApp() {
        TestMedia.cleanup(ctx)
        ctx.getSharedPreferences("tune", Context.MODE_PRIVATE).edit().clear().also { prefs(it) }.commit()
        File(ctx.filesDir, "playlists.json").delete()
        Net.http = fake
        songs.forEach { uris[it.title] = TestMedia.create(ctx, it) }
        scenario = ActivityScenario.launch(MainActivity::class.java)
        onVm { player.reset() }
        val titles = songs.map { it.title }.toSet()
        waitFor("library scan") { vm.library.value.songs.count { it.title in titles } == songs.size }
        compose.waitForIdle()
    }

    @After
    fun stopApp() {
        runCatching { onVm { player.reset() } }
        runCatching { scenario.close() }
        TestMedia.cleanup(ctx)
        Net.http = UrlConnectionHttp
    }

    // --- app access ---------------------------------------------------------

    protected val vm: MainViewModel
        get() {
            lateinit var v: MainViewModel
            scenario.onActivity { v = it.vm }
            return v
        }

    /** Runs [block] on the main thread with the view model (MediaController needs it). */
    protected fun onVm(block: MainViewModel.() -> Unit) = scenario.onActivity { it.vm.block() }

    protected val player: PlayerState get() = vm.player.state.value

    protected fun song(title: String): Song = vm.library.value.songs.first { it.title == title }

    protected val screen: Screen get() = vm.backStack.last()

    protected fun waitFor(what: String, timeoutMs: Long = 15_000, condition: () -> Boolean) {
        try {
            compose.waitUntil(timeoutMs) { condition() }
        } catch (e: Throwable) {
            throw AssertionError("timed out waiting for $what", e)
        }
    }

    // --- finding things on screen -------------------------------------------

    private val screenWidth get() = ctx.resources.displayMetrics.widthPixels
    private val screenHeight get() = ctx.resources.displayMetrics.heightPixels

    private fun onScreen(n: SemanticsNode): Boolean {
        val b = n.boundsInRoot
        return b.width > 0 && b.height > 0 && b.left >= -1 && b.right <= screenWidth + 1 && b.top >= -1 && b.top < screenHeight
    }

    private fun visibleIndex(text: String, substring: Boolean): Int =
        compose.onAllNodesWithText(text, substring)
            .fetchSemanticsNodes(atLeastOneRootRequired = false)
            .indexOfFirst(::onScreen)

    /**
     * The node showing [text] that is actually on screen (pivots and pagers
     * keep neighbouring pages composed, so text often exists off screen too).
     */
    protected fun text(text: String, substring: Boolean = false, timeoutMs: Long = 10_000): SemanticsNodeInteraction {
        waitFor("\"$text\" on screen", timeoutMs) { visibleIndex(text, substring) >= 0 }
        return compose.onAllNodesWithText(text, substring)[visibleIndex(text, substring)]
    }

    protected fun isShown(text: String, substring: Boolean = false) = visibleIndex(text, substring) >= 0

    protected fun assertNotShown(text: String, substring: Boolean = false) {
        compose.waitForIdle()
        check(!isShown(text, substring)) { "\"$text\" should not be on screen" }
    }

    protected fun tap(text: String, substring: Boolean = false) {
        text(text, substring).performClick()
        compose.waitForIdle()
    }

    protected fun longPress(text: String) {
        text(text).performTouchInput { longClick() }
        compose.waitForIdle()
    }

    protected fun tag(tag: String): SemanticsNodeInteraction = compose.onNodeWithTag(tag)

    protected fun button(label: String): SemanticsNodeInteraction = compose.onNodeWithContentDescription(label)

    protected fun boundsOf(n: SemanticsNodeInteraction): Rect = n.fetchSemanticsNode().boundsInRoot

    protected fun menuItem(label: String) = tap(label)

    /**
     * Scrolls the screen's list until [matcher] is composed and visible (lazy
     * lists drop off-screen rows, e.g. when the soft keyboard opens).
     */
    protected fun scrollTo(matcher: SemanticsMatcher) {
        compose.onAllNodes(hasScrollAction()).onFirst().performScrollToNode(matcher)
        compose.waitForIdle()
    }

    /** A finger-like horizontal swipe inside the node (not from its very edge). */
    protected fun swipeNext(tag: String) {
        tag(tag).performTouchInput {
            swipe(Offset(width * 0.85f, centerY), Offset(width * 0.15f, centerY), 300)
        }
        compose.waitForIdle()
    }

    protected fun swipePrev(tag: String) {
        tag(tag).performTouchInput {
            swipe(Offset(width * 0.15f, centerY), Offset(width * 0.85f, centerY), 300)
        }
        compose.waitForIdle()
    }

    // --- keys and system UI -------------------------------------------------

    /** A real key press through the window, like a hardware keyboard. */
    protected fun key(code: Int, times: Int = 1) {
        repeat(times) { instrumentation.sendKeyDownUpSync(code) }
        compose.waitForIdle()
    }

    /**
     * A media key as the system delivers it from a headset or keyboard (UiAutomator
     * injects system-wide, so the key is routed to the active media session).
     */
    protected fun mediaKey(code: Int) {
        device.pressKeyCode(code)
        compose.waitForIdle()
    }

    /**
     * Types into a text field inside a lazy list. Focusing a field opens the soft
     * keyboard, which resizes the list and recreates rows, so focus first, let
     * the layout settle, then type.
     */
    protected fun replaceText(fieldTag: String, value: String) {
        scrollTo(hasTestTag(fieldTag))
        tag(fieldTag).performClick()
        compose.waitForIdle()
        scrollTo(hasTestTag(fieldTag))
        tag(fieldTag).performTextReplacement(value)
        compose.waitForIdle()
    }

    protected fun back() {
        key(KeyEvent.KEYCODE_BACK)
    }

    /**
     * Accepts Android's scoped-storage consent dialog if it shows up (files the
     * app created itself may be granted without one). Returns whether it did.
     */
    protected fun allowSystemDialog(timeoutMs: Long = 5_000): Boolean {
        val allow = device.wait(Until.findObject(By.text(Pattern.compile("(?i)allow"))), timeoutMs) ?: return false
        allow.click()
        device.waitForIdle()
        return true
    }

    protected fun goHome() {
        onVm { while (back()) Unit }
        compose.waitForIdle()
    }

    protected fun openCollection(pivot: String) {
        goHome()
        tap(pivot)
        waitFor("$pivot pivot") { (screen as? Screen.Collection) != null }
    }

    protected fun playFromSongs(title: String) {
        openCollection("songs")
        tap(title)
        waitFor("$title playing") { player.isPlaying && player.currentId == song(title).id }
    }
}
