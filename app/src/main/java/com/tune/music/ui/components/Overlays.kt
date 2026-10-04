package com.tune.music.ui.components

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tune.music.ui.theme.Metro
import com.tune.music.ui.theme.MetroType
import com.tune.music.ui.theme.metroText

sealed interface Overlay {
    /**
     * WP context menu (long press): lowercase items on a light panel, opened
     * right below (or above) the pressed item, which stays visible.
     */
    class Menu(val items: List<MenuItem>, val anchor: Rect? = PressAnchor.take()) : Overlay
    /** WP message box. */
    class Message(val title: String, val text: String, val ok: String, val cancel: String?, val onOk: () -> Unit) : Overlay
    /** Message box with a text box (new playlist, rename...). */
    class Input(val title: String, val initial: String, val ok: String, val onOk: (String) -> Unit) : Overlay
    /** A list of choices (add to playlist...). */
    class Picker(val title: String, val options: List<MenuItem>) : Overlay
}

@Stable
class Overlays {
    var current by mutableStateOf<Overlay?>(null)
        private set

    fun show(o: Overlay) { current = o }
    fun dismiss() { current = null }

    fun menu(vararg items: MenuItem?) = show(Overlay.Menu(items.filterNotNull()))
    fun confirm(title: String, text: String, ok: String = "ok", onOk: () -> Unit) =
        show(Overlay.Message(title, text, ok, "cancel", onOk))
    fun input(title: String, initial: String = "", ok: String = "save", onOk: (String) -> Unit) =
        show(Overlay.Input(title, initial, ok, onOk))
}

val LocalOverlays = staticCompositionLocalOf { Overlays() }

@Composable
fun OverlayHost(overlays: Overlays) {
    val o = overlays.current
    // Keep showing the last overlay while it animates out.
    var last by remember { mutableStateOf(o) }
    if (o != null) last = o
    val shown = o ?: last
    val anchor = (shown as? Overlay.Menu)?.anchor

    BackHandler(enabled = o != null) { overlays.dismiss() }
    AnimatedVisibility(o != null, enter = fadeIn(), exit = fadeOut()) {
        Scrim(anchor, Modifier.clickable(remember { MutableInteractionSource() }, null) { overlays.dismiss() })
    }
    AnimatedVisibility(
        o != null,
        enter = slideInVertically { -it / 8 } + fadeIn(),
        exit = slideOutVertically { -it / 8 } + fadeOut(),
    ) {
        when (shown) {
            is Overlay.Menu -> Anchored(shown.anchor) { MenuPanel(shown, overlays, atTop = shown.anchor == null) }
            is Overlay.Message -> MessagePanel(shown, overlays)
            is Overlay.Input -> InputPanel(shown, overlays)
            is Overlay.Picker -> PickerPanel(shown, overlays)
            null -> {}
        }
    }
}

/** Dims the screen, leaving a hole over [hole] so the pressed item stays visible. */
@Composable
private fun Scrim(hole: Rect?, modifier: Modifier) {
    val dim = Color.Black.copy(alpha = 0.6f)
    Canvas(modifier.fillMaxSize().testTag("overlay:scrim")) {
        if (hole == null) {
            drawRect(dim)
            return@Canvas
        }
        val w = size.width
        val h = size.height
        drawRect(dim, Offset.Zero, Size(w, hole.top.coerceAtLeast(0f)))
        drawRect(dim, Offset(0f, hole.bottom), Size(w, (h - hole.bottom).coerceAtLeast(0f)))
        drawRect(dim, Offset(0f, hole.top), Size(hole.left.coerceAtLeast(0f), hole.height))
        drawRect(dim, Offset(hole.right, hole.top), Size((w - hole.right).coerceAtLeast(0f), hole.height))
    }
}

/**
 * Places [content] under the anchor, or above it when there's more room
 * there; if neither side fits the menu, it shrinks (and scrolls) to the space.
 */
@Composable
private fun Anchored(anchor: Rect?, content: @Composable () -> Unit) {
    val statusBar = WindowInsets.statusBars
    val navBar = WindowInsets.navigationBars
    Layout(content, Modifier.fillMaxSize()) { measurables, c ->
        val m = measurables.first()
        if (anchor == null) {
            val p = m.measure(c.copy(minWidth = 0, minHeight = 0))
            return@Layout layout(c.maxWidth, c.maxHeight) { p.place(0, 0) }
        }
        val top = statusBar.getTop(this)
        val bottom = c.maxHeight - navBar.getBottom(this)
        val below = (bottom - anchor.bottom.toInt()).coerceAtLeast(0)
        val above = (anchor.top.toInt() - top).coerceAtLeast(0)
        val wanted = m.maxIntrinsicHeight(c.maxWidth)
        val placeBelow = wanted <= below || below >= above
        val p = m.measure(c.copy(minWidth = 0, minHeight = 0, maxHeight = if (placeBelow) below else above))
        val y = if (placeBelow) anchor.bottom.toInt() else anchor.top.toInt() - p.height
        layout(c.maxWidth, c.maxHeight) { p.place(0, y) }
    }
}

@Composable
private fun Panel(dark: Boolean, atTop: Boolean = true, tag: String, content: @Composable () -> Unit) {
    val c = Metro.colors
    Column(
        Modifier
            .fillMaxWidth()
            .testTag(tag)
            .background(if (dark) c.chrome else c.menuBackground)
            .topDivider(c.divider)
            .clickable(remember { MutableInteractionSource() }, null) {}
            .then(if (atTop) Modifier.statusBarsPadding() else Modifier)
            .padding(vertical = 16.dp),
    ) { content() }
}

@Composable
private fun MenuPanel(o: Overlay.Menu, overlays: Overlays, atTop: Boolean) {
    Panel(dark = false, atTop = atTop, tag = "overlay:menu") {
        Column(Modifier.heightIn(max = 560.dp).verticalScroll(rememberScrollState())) {
            o.items.forEach { m ->
                MText(
                    m.label.lowercase(), metroText(24.sp),
                    color = Metro.colors.menuForeground,
                    modifier = Modifier
                        .fillMaxWidth()
                        .metroClick {
                            overlays.dismiss()
                            m.onClick()
                        }
                        .padding(horizontal = 24.dp, vertical = 12.dp),
                )
            }
        }
    }
}

@Composable
private fun MessagePanel(o: Overlay.Message, overlays: Overlays) {
    Panel(dark = true, tag = "overlay:message") {
        Column(Modifier.padding(horizontal = 24.dp)) {
            MText(o.title, MetroType.medium.copy(fontWeight = FontWeight.SemiBold), maxLines = 3)
            VSpace(12)
            MText(o.text, MetroType.normal, maxLines = 10)
            VSpace(24)
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                MetroButton(o.ok, Modifier.weight(1f)) {
                    overlays.dismiss()
                    o.onOk()
                }
                if (o.cancel != null) MetroButton(o.cancel, Modifier.weight(1f)) { overlays.dismiss() }
            }
        }
    }
}

@Composable
private fun InputPanel(o: Overlay.Input, overlays: Overlays) {
    var text by remember(o) { mutableStateOf(o.initial) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(o) { runCatching { focus.requestFocus() } }
    val submit = {
        if (text.isNotBlank()) {
            overlays.dismiss()
            o.onOk(text.trim())
        }
    }
    Panel(dark = true, tag = "overlay:input") {
        Column(Modifier.padding(horizontal = 24.dp)) {
            MText(o.title, MetroType.medium.copy(fontWeight = FontWeight.SemiBold))
            VSpace(16)
            MetroTextBox(
                null, text, { text = it }, Modifier.focusRequester(focus),
                imeAction = ImeAction.Done, onSubmit = submit, tag = "field:input",
            )
            VSpace(24)
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                MetroButton(o.ok, Modifier.weight(1f), enabled = text.isNotBlank()) { submit() }
                MetroButton("cancel", Modifier.weight(1f)) { overlays.dismiss() }
            }
        }
    }
}

@Composable
private fun PickerPanel(o: Overlay.Picker, overlays: Overlays) {
    Panel(dark = true, tag = "overlay:picker") {
        MText(o.title.uppercase(), MetroType.appTitle, modifier = Modifier.padding(horizontal = 24.dp))
        VSpace(8)
        Column(Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState())) {
            o.options.forEach { m ->
                MText(
                    m.label, MetroType.large,
                    modifier = Modifier
                        .fillMaxWidth()
                        .metroClick {
                            overlays.dismiss()
                            m.onClick()
                        }
                        .padding(horizontal = 24.dp, vertical = 8.dp),
                )
            }
        }
    }
}
