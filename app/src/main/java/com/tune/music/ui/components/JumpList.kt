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
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
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

/** Items grouped by jump-list letter, in letter order. */
private fun <T> groupsOf(items: List<T>, name: (T) -> String): List<Pair<Char, List<T>>> {
    val g = items.groupBy { jumpGroup(name(it)) }
    return GROUPS.filter { it in g }.map { it to g.getValue(it) }
}

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
    val groups = remember(items) { groupsOf(items, name) }
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
            item(key = "jump-$c") { JumpTile(c) { picking = true } }
            list.forEach { item -> item(key = key(item)) { row(item) } }
        }
    }

    if (picking) {
        LetterPicker(groups.map { it.first }.toSet(), onDismiss = { picking = false }) { c ->
            scope.launch { state.scrollToItem(headerIndex.getValue(c)) }
        }
    }
}

/**
 * The same, as a grid of [columns] tiles per row; the letter tiles span a
 * whole row.
 */
@Composable
fun <T> JumpGrid(
    items: List<T>,
    name: (T) -> String,
    key: (T) -> Any,
    modifier: Modifier = Modifier,
    columns: Int = 2,
    cell: @Composable (T) -> Unit,
) {
    val groups = remember(items) { groupsOf(items, name) }
    val state = rememberLazyGridState()
    val scope = rememberCoroutineScope()
    var picking by remember { mutableStateOf(false) }
    val headerIndex = remember(groups) {
        var i = 0
        groups.associate { (c, list) -> c to i.also { i += 1 + list.size } }
    }

    LazyVerticalGrid(
        columns = GridCells.Fixed(columns),
        state = state,
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 24.dp, end = 24.dp, bottom = 96.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        groups.forEach { (c, list) ->
            item(key = "jump-$c", span = { GridItemSpan(maxLineSpan) }) {
                Box { JumpTile(c, inset = false) { picking = true } }
            }
            items(list, key = key) { cell(it) }
        }
    }

    if (picking) {
        LetterPicker(groups.map { it.first }.toSet(), onDismiss = { picking = false }) { c ->
            scope.launch { state.scrollToItem(headerIndex.getValue(c)) }
        }
    }
}

@Composable
private fun JumpTile(c: Char, inset: Boolean = true, onClick: () -> Unit) {
    Box(
        Modifier
            .padding(start = if (inset) 24.dp else 0.dp, top = 12.dp, bottom = if (inset) 8.dp else 0.dp)
            .size(56.dp)
            .testTag("jump:$c")
            .background(Metro.colors.accent)
            .metroClick(onClick = onClick),
        contentAlignment = Alignment.BottomStart,
    ) {
        MText(c.toString(), MetroType.extraLarge, color = Color.White, modifier = Modifier.padding(start = 6.dp))
    }
}

/** Full-screen grid of letters; only letters with items are live. */
@Composable
private fun LetterPicker(present: Set<Char>, onDismiss: () -> Unit, onPick: (Char) -> Unit) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        BackHandler(onBack = onDismiss)
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
                                onDismiss()
                                onPick(c)
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
