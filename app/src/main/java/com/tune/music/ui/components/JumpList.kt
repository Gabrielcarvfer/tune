package com.tune.music.ui.components

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.tune.music.data.jumpGroup
import com.tune.music.ui.theme.Metro
import com.tune.music.ui.theme.MetroType
import kotlinx.coroutines.launch

private val GROUPS = listOf('#') + ('a'..'z').toList()

/**
 * Alphabetical list with accent-coloured letter tiles. Tapping a tile opens the
 * letter grid; tapping a letter there jumps to it.
 */
@Composable
fun <T> JumpList(
    items: List<T>,
    name: (T) -> String,
    key: (T) -> Any,
    modifier: Modifier = Modifier,
    /** Extra items above the list; [headerCount] must say how many it adds. */
    header: (LazyListScope.() -> Unit)? = null,
    headerCount: Int = 0,
    row: @Composable (T) -> Unit,
) {
    val groups = remember(items) {
        val g = items.groupBy { jumpGroup(name(it)) }
        GROUPS.filter { it in g }.map { it to g.getValue(it) }
    }
    val state = rememberLazyListState()
    val scope = rememberCoroutineScope()
    var picking by remember { mutableStateOf(false) }

    // Header item indices, so the grid can jump straight to them.
    val headerIndex = remember(groups, headerCount) {
        var i = headerCount
        groups.associate { (c, list) -> c to i.also { i += 1 + list.size } }
    }

    LazyColumn(modifier.fillMaxSize(), state = state, contentPadding = PaddingValues(bottom = 96.dp)) {
        header?.invoke(this)
        groups.forEach { (c, list) ->
            item(key = "jump-$c") {
                Box(
                    Modifier
                        .padding(start = 24.dp, top = 12.dp, bottom = 8.dp)
                        .size(56.dp)
                        .testTag("jump:$c")
                        .background(Metro.colors.accent)
                        .metroClick { picking = true },
                    contentAlignment = Alignment.BottomStart,
                ) {
                    MText(c.toString(), MetroType.extraLarge, color = Color.White, modifier = Modifier.padding(start = 6.dp, bottom = 0.dp))
                }
            }
            list.forEach { item -> item(key = key(item)) { row(item) } }
        }
    }

    if (picking) {
        Dialog(
            onDismissRequest = { picking = false },
            properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
        ) {
            BackHandler { picking = false }
            val present = groups.map { it.first }.toSet()
            LazyVerticalGrid(
                columns = GridCells.Fixed(4),
                modifier = Modifier
                    .fillMaxSize()
                    .background(Metro.colors.background.copy(alpha = 0.97f))
                    .padding(horizontal = 20.dp, vertical = 48.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(GROUPS) { c ->
                    val on = c in present
                    Box(
                        Modifier
                            .aspectRatio(1f)
                            .testTag("jumpgrid:$c")
                            .background(if (on) Metro.colors.accent else Metro.colors.disabled)
                            .then(
                                if (on) Modifier.metroClick {
                                    picking = false
                                    scope.launch { state.scrollToItem(headerIndex.getValue(c)) }
                                } else Modifier,
                            ),
                        contentAlignment = Alignment.BottomStart,
                    ) {
                        MText(c.toString(), MetroType.extraLarge,
                            color = if (on) Color.White else Metro.colors.subtle,
                            modifier = Modifier.padding(start = 8.dp))
                    }
                }
            }
        }
    }
}
