package com.music.tune.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import android.os.SystemClock
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.music.tune.ui.theme.Metro
import com.music.tune.ui.theme.MetroType

/** The Windows Phone "tilt" effect: the element leans away from the finger. */
fun Modifier.tilt(): Modifier = composed {
    var press by remember { mutableStateOf<Offset?>(null) }
    var size by remember { mutableStateOf(IntSize.Zero) }
    val p = press
    val nx = if (p != null && size.width > 0) (p.x / size.width - 0.5f) * 2f else 0f
    val ny = if (p != null && size.height > 0) (p.y / size.height - 0.5f) * 2f else 0f
    // Wide rows tilt less sideways so they don't swing wildly.
    val maxY = if (size.width > size.height * 3) 3f else 10f
    val rx by animateFloatAsState(-ny * 10f, tween(if (p != null) 60 else 200), label = "rx")
    val ry by animateFloatAsState(nx * maxY, tween(if (p != null) 60 else 200), label = "ry")
    val sc by animateFloatAsState(if (p != null) 0.97f else 1f, tween(if (p != null) 60 else 200), label = "sc")
    val density = LocalDensity.current.density
    this
        .onSizeChanged { size = it }
        .pointerInput(Unit) {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                press = down.position
                waitForUpOrCancellation()
                press = null
            }
        }
        .graphicsLayer {
            rotationX = rx
            rotationY = ry
            scaleX = sc
            scaleY = sc
            cameraDistance = 14f * density
        }
}

/**
 * Where the last long press happened (root coordinates), so the context menu
 * can open next to the pressed item like on Windows Phone.
 */
object PressAnchor {
    private var rect: Rect? = null
    private var at = 0L

    fun set(r: Rect?) {
        rect = r
        at = SystemClock.uptimeMillis()
    }

    /** The anchor of a long press that just happened, consumed once. */
    fun take(): Rect? {
        val r = rect.takeIf { SystemClock.uptimeMillis() - at < 1000 }
        rect = null
        return r
    }
}

/**
 * Click with tilt feedback and no ripple. Keyboard focus shows an accent
 * outline; Enter / D-pad centre activate it like a tap.
 */
@OptIn(ExperimentalFoundationApi::class)
fun Modifier.metroClick(onLongClick: (() -> Unit)? = null, onClick: () -> Unit): Modifier = composed {
    val src = remember { MutableInteractionSource() }
    val focused by src.collectIsFocusedAsState()
    val bounds = remember { arrayOfNulls<Rect>(1) }
    val accent = Metro.colors.accent
    this
        .onGloballyPositioned { bounds[0] = it.boundsInRoot() }
        .tilt()
        .then(if (focused) Modifier.border(2.dp, accent) else Modifier)
        .combinedClickable(
            interactionSource = src,
            indication = null,
            onLongClick = onLongClick?.let { cb ->
                {
                    PressAnchor.set(bounds[0])
                    cb()
                }
            },
            onClick = onClick,
        )
}

@Composable
fun MText(
    text: String,
    style: TextStyle,
    modifier: Modifier = Modifier,
    color: Color = Metro.colors.foreground,
    maxLines: Int = 1,
    softWrap: Boolean = maxLines > 1,
) {
    Text(
        text = text,
        style = style,
        color = color,
        modifier = modifier,
        maxLines = maxLines,
        softWrap = softWrap,
        overflow = TextOverflow.Clip,
    )
}

@Composable
fun MTextEllipsis(text: String, style: TextStyle, modifier: Modifier = Modifier, color: Color = Metro.colors.foreground) {
    Text(text, style = style, color = color, modifier = modifier, maxLines = 1, overflow = TextOverflow.Ellipsis)
}

/** "MUSIC" over a big lowercase title, the standard WP page header. */
@Composable
fun PageHeader(app: String, title: String, modifier: Modifier = Modifier) {
    Column(modifier.padding(start = 24.dp, top = 16.dp, bottom = 12.dp)) {
        MText(app.uppercase(), MetroType.appTitle)
        MText(title.lowercase(), MetroType.pivot, Modifier.offset(x = (-3).dp), color = Metro.colors.title)
    }
}

/** Circle-outlined icon button (app bar & transport controls). */
@Composable
fun RoundButton(
    icon: ImageVector,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    size: Int = 48,
    highlighted: Boolean = false,
    onClick: () -> Unit,
) {
    val src = remember { MutableInteractionSource() }
    val pressed by src.collectIsPressedAsState()
    val keyFocused by src.collectIsFocusedAsState()
    val c = Metro.colors
    val filled = pressed || highlighted
    Box(
        modifier
            .size(size.dp)
            .clip(CircleShape)
            .background(if (filled) c.foreground else Color.Transparent)
            .border(if (keyFocused) 4.dp else 2.dp, if (keyFocused) c.accent else c.foreground, CircleShape)
            .clickable(interactionSource = src, indication = null, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription, tint = if (filled) c.background else c.foreground,
            modifier = Modifier.size((size * 0.5f).dp))
    }
}

/** Rectangular bordered WP button. */
@Composable
fun MetroButton(text: String, modifier: Modifier = Modifier, enabled: Boolean = true, onClick: () -> Unit) {
    val src = remember { MutableInteractionSource() }
    val pressed by src.collectIsPressedAsState()
    val c = Metro.colors
    val fg = if (enabled) c.foreground else c.subtle
    Box(
        modifier
            .height(56.dp)
            .background(if (pressed && enabled) c.accent else Color.Transparent)
            .border(2.dp, fg)
            .clickable(src, null, enabled = enabled, onClick = onClick)
            .padding(horizontal = 20.dp),
        contentAlignment = Alignment.Center,
    ) {
        MText(text.lowercase(), MetroType.normal.copy(fontSize = MetroType.normal.fontSize), color = fg)
    }
}

/** WP text box: light grey when idle, white with accent border when focused. */
@Composable
fun MetroTextBox(
    label: String?,
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    numeric: Boolean = false,
    imeAction: ImeAction = ImeAction.Next,
    /** Called for the keyboard's done/search/go key (and Enter on a hardware keyboard). */
    onSubmit: (() -> Unit)? = null,
    tag: String? = label?.let { "field:$it" },
) {
    val c = Metro.colors
    val focusManager = LocalFocusManager.current
    var focused by remember { mutableStateOf(false) }
    Column(modifier) {
        if (label != null) MText(label.lowercase(), MetroType.small, color = c.subtle, modifier = Modifier.padding(bottom = 4.dp))
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = true,
            textStyle = MetroType.normal.copy(color = Color.Black),
            cursorBrush = SolidColor(c.accent),
            keyboardOptions = KeyboardOptions(
                keyboardType = if (numeric) KeyboardType.Number else KeyboardType.Text,
                imeAction = imeAction,
            ),
            keyboardActions = KeyboardActions(
                onDone = { onSubmit?.invoke() },
                onGo = { onSubmit?.invoke() },
                onSearch = { onSubmit?.invoke() },
                onNext = { focusManager.moveFocus(FocusDirection.Down) },
            ),
            modifier = Modifier
                .fillMaxWidth()
                .then(if (tag != null) Modifier.testTag(tag) else Modifier)
                .onFocusChanged { focused = it.isFocused }
                .background(if (focused) Color.White else c.boxIdle)
                .border(2.dp, if (focused) c.accent else if (c.dark) Color.Transparent else c.boxBorder, RectangleShape)
                .padding(horizontal = 10.dp, vertical = 12.dp),
        )
    }
}

data class AppBarButton(val icon: ImageVector, val label: String, val highlighted: Boolean = false, val onClick: () -> Unit)
data class MenuItem(val label: String, val onClick: () -> Unit)

/** The WP8 application bar: round icons, "..." to reveal labels and the menu. */
@Composable
fun AppBar(buttons: List<AppBarButton>, menu: List<MenuItem> = emptyList(), modifier: Modifier = Modifier) {
    val c = Metro.colors
    var expanded by remember { mutableStateOf(false) }
    Column(
        modifier
            .fillMaxWidth()
            .background(c.chrome)
            .topDivider(c.divider)
            .navigationBarsPadding(),
    ) {
        Box(Modifier.fillMaxWidth().height(if (expanded) 88.dp else 72.dp)) {
            Row(
                Modifier.align(Alignment.TopCenter).padding(top = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(28.dp),
            ) {
                buttons.forEach { b ->
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        RoundButton(b.icon, b.label, highlighted = b.highlighted, onClick = {
                            expanded = false
                            b.onClick()
                        })
                        if (expanded) MText(b.label.lowercase(), MetroType.small.copy(fontSize = MetroType.small.fontSize * 0.85f),
                            modifier = Modifier.padding(top = 4.dp))
                    }
                }
            }
            if (buttons.isNotEmpty() || menu.isNotEmpty()) {
                Box(
                    Modifier
                        .align(Alignment.TopEnd)
                        .size(56.dp, 40.dp)
                        .testTag("appbar:more")
                        .clickable(remember { MutableInteractionSource() }, null) { expanded = !expanded },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Filled.MoreHoriz, "more", tint = c.foreground)
                }
            }
        }
        AnimatedVisibility(expanded && menu.isNotEmpty(), enter = expandVertically(), exit = shrinkVertically()) {
            Column(Modifier.padding(bottom = 12.dp)) {
                menu.forEach { m ->
                    MText(
                        m.label.lowercase(), MetroType.medium,
                        modifier = Modifier
                            .fillMaxWidth()
                            .metroClick {
                                expanded = false
                                m.onClick()
                            }
                            .padding(horizontal = 24.dp, vertical = 10.dp),
                    )
                }
            }
        }
    }
}

/** The five flying dots of the WP indeterminate progress bar. */
@Composable
fun ProgressDots(modifier: Modifier = Modifier) {
    val c = Metro.colors
    val t = rememberInfiniteTransition(label = "dots")
    val phase by t.animateFloat(0f, 1f, infiniteRepeatable(tween(2600, easing = LinearEasing)), label = "phase")
    BoxWithConstraints(modifier.fillMaxWidth().height(6.dp)) {
        val w = maxWidth
        for (i in 0 until 5) {
            val local = (phase * 1.5f - i * 0.07f)
            if (local in 0f..1f) {
                val u = local - 0.5f
                val x = 0.5f + 4f * u * u * u // fast - slow - fast
                Box(
                    Modifier
                        .offset(x = w * x)
                        .size(4.dp)
                        .background(c.accent),
                )
            }
        }
    }
}

@Composable
fun Toast(text: String, modifier: Modifier = Modifier) {
    val c = Metro.colors
    Box(
        modifier
            .fillMaxWidth()
            .background(c.accent)
            .padding(horizontal = 24.dp, vertical = 14.dp),
    ) {
        MText(text, MetroType.normal.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold), color = Color.White)
    }
}

/** A 1dp line along the top edge (bars in the light theme). */
fun Modifier.topDivider(color: Color): Modifier =
    if (color == Color.Transparent) this
    else drawBehind { drawLine(color, androidx.compose.ui.geometry.Offset.Zero, androidx.compose.ui.geometry.Offset(size.width, 0f), 1.dp.toPx()) }

@Composable
fun VSpace(dp: Int) = Spacer(Modifier.height(dp.dp))

@Composable
fun HSpace(dp: Int) = Spacer(Modifier.width(dp.dp))

/** Fades a pivot/panorama header in when it becomes selected. */
@Composable
fun animatedAlpha(selected: Boolean): Float =
    animateFloatAsState(if (selected) 1f else 0.4f, tween(250, easing = FastOutSlowInEasing), label = "a").value
