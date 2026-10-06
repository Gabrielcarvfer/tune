package com.music.tune.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PageSize
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.music.tune.ui.theme.Metro
import com.music.tune.ui.theme.MetroType
import kotlinx.coroutines.launch

private const val LOOPS = 2000

/**
 * WP pivot: small app title, a row of lowercase headers that wraps around,
 * and swipeable full-width pages, also wrapping.
 */
@Composable
fun Pivot(
    title: String,
    headers: List<String>,
    initial: Int = 0,
    modifier: Modifier = Modifier,
    onPageChanged: (Int) -> Unit = {},
    page: @Composable (Int) -> Unit,
) {
    val n = headers.size
    val start = LOOPS / 2 * n + initial
    val state = rememberPagerState(initialPage = start) { LOOPS * n }
    val scope = rememberCoroutineScope()
    val widths = remember { mutableStateMapOf<Int, Int>() }
    val density = LocalDensity.current
    LaunchedEffect(state) {
        snapshotFlow { state.currentPage }.collect { onPageChanged(it % n) }
    }

    Column(modifier.fillMaxSize()) {
        MText(title.uppercase(), MetroType.appTitle, modifier = Modifier.padding(start = 24.dp, top = 16.dp))
        val current = state.currentPage
        val cur = current % n
        val frac = state.currentPageOffsetFraction
        val shiftPx = when {
            frac > 0 -> -(widths[cur] ?: 0) * frac
            else -> 0f
        }
        Row(
            Modifier
                .fillMaxWidth()
                .wrapContentWidth(Alignment.Start, unbounded = true)
                .padding(start = 21.dp)
                .graphicsLayer {
                    translationX = shiftPx
                    alpha = if (frac < 0) 1f + frac * 0.6f else 1f
                },
        ) {
            for (k in 0 until n) {
                val idx = (cur + k) % n
                MText(
                    headers[idx].lowercase(),
                    MetroType.pivot,
                    color = if (k == 0) Metro.colors.title else Metro.colors.subtle.copy(alpha = 0.6f),
                    modifier = Modifier
                        .testTag(if (k == 0) "pivot:current" else "pivot:${headers[idx].lowercase()}")
                        .onSizeChanged { widths[idx] = it.width + with(density) { 20.dp.roundToPx() } }
                        .padding(end = 20.dp)
                        .metroClick { scope.launch { state.animateScrollToPage(current + k) } },
                )
            }
        }
        HorizontalPager(
            state = state,
            modifier = Modifier.fillMaxSize().testTag("pivot:pager"),
            beyondViewportPageCount = 1,
            key = { it },
        ) { p -> Box(Modifier.fillMaxSize()) { page(p % n) } }
    }
}

/**
 * WP panorama: a huge title that scrolls slower than the sections beneath it,
 * with the next section peeking in from the right.
 */
@Composable
fun Panorama(
    title: String,
    sections: List<Pair<String, @Composable () -> Unit>>,
    modifier: Modifier = Modifier,
) {
    val state = rememberPagerState { sections.size }
    BoxWithConstraints(modifier.fillMaxSize()) {
        val progress = state.currentPage + state.currentPageOffsetFraction
        MText(
            title.lowercase(),
            MetroType.panorama,
            color = Metro.colors.title,
            modifier = Modifier
                .wrapContentWidth(Alignment.Start, unbounded = true)
                .offset(x = 12.dp - (progress * 90).dp, y = (-6).dp),
        )
        HorizontalPager(
            state = state,
            pageSize = PageSize.Fill,
            // The next section peeks in from the right; the padding also lets the
            // last section scroll fully left instead of stopping right-aligned.
            contentPadding = PaddingValues(end = 48.dp),
            modifier = Modifier.fillMaxSize().padding(top = 128.dp).testTag("panorama"),
            beyondViewportPageCount = 1,
        ) { i ->
            val (header, content) = sections[i]
            Column(Modifier.fillMaxSize().padding(start = 24.dp, end = 12.dp)) {
                MText(header.lowercase(), MetroType.pivot.copy(fontSize = MetroType.pivot.fontSize * 0.9f),
                    color = Metro.colors.title, modifier = Modifier.padding(bottom = 8.dp))
                content()
            }
        }
    }
}
