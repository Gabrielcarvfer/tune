package com.music.tune.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.music.tune.MainViewModel
import com.music.tune.data.Song
import com.music.tune.data.formatDuration
import com.music.tune.ui.components.AppBarButton
import com.music.tune.ui.components.MText
import com.music.tune.ui.components.MTextEllipsis
import com.music.tune.ui.components.MetroButton
import com.music.tune.ui.components.PageHeader
import com.music.tune.ui.components.ProgressDots
import com.music.tune.ui.components.RoundButton
import com.music.tune.ui.components.VSpace
import com.music.tune.ui.components.metroClick
import com.music.tune.ui.theme.Metro
import com.music.tune.ui.theme.MetroType

/**
 * Songs saved more than once, in groups of copies. Each copy can be previewed;
 * the ticked ones are deleted (after the system asks).
 */
@Composable
fun DuplicatesScreen(vm: MainViewModel, actions: Actions) {
    val lib by vm.library.collectAsState()
    val state by vm.duplicates.collectAsState()
    val selected = remember { mutableStateListOf<Long>() }
    val preview = rememberPreview()

    LaunchedEffect(Unit) { if (state.groups == null && !state.running) vm.findDuplicates() }

    // Songs deleted or changed elsewhere since the search drop out.
    val groups = state.groups.orEmpty()
        .map { g -> g.mapNotNull { lib.song(it.id) } }
        .filter { it.size > 1 }
    val ticked = groups.flatten().filter { it.id in selected }

    BarPage(
        listOf(
            AppBarButton(Icons.Filled.Delete, "delete") {
                if (ticked.isEmpty()) return@AppBarButton vm.toast("tick the copies to delete")
                preview.stop()
                actions.delete(ticked, if (ticked.size == 1) "this copy" else "${ticked.size} copies") {
                    vm.forgetDuplicates(ticked.map { it.id }.toSet())
                    selected.removeAll(ticked.map { it.id })
                }
            },
            AppBarButton(Icons.Filled.Refresh, "search again") {
                preview.stop()
                selected.clear()
                vm.findDuplicates()
            },
        ),
    ) {
        Column(Modifier.fillMaxSize().statusBarsPadding()) {
            PageHeader("music", "duplicates")
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
                item {
                    Column(Modifier.padding(horizontal = 24.dp)) {
                        MText(
                            "Songs saved more than once, found by their sound (so copies in another format or with " +
                                "other tags count too). Listen to each copy, tick the ones to delete, and delete them.",
                            MetroType.small, color = Metro.colors.subtle, maxLines = 6,
                        )
                        VSpace(12)
                        if (state.running) {
                            MText(
                                if (state.total == 0) "comparing..." else "listening to ${state.done} of ${state.total} songs...",
                                MetroType.normal, modifier = Modifier.testTag("dup:status"),
                            )
                            VSpace(8)
                            ProgressDots()
                            VSpace(10)
                            MetroButton("stop") { vm.stopDuplicates() }
                            VSpace(16)
                        }
                    }
                }
                when {
                    state.running -> {}
                    state.groups == null -> {}
                    groups.isEmpty() -> item { EmptyNote("No duplicates: every song is here once.") }
                    else -> {
                        item {
                            MText(
                                if (groups.size == 1) "1 song has copies" else "${groups.size} songs have copies",
                                MetroType.normal, modifier = Modifier.padding(horizontal = 24.dp).testTag("dup:status"),
                            )
                        }
                        groups.forEach { group ->
                            item(key = "head:${group.first().id}") {
                                Column(Modifier.padding(start = 24.dp, end = 24.dp, top = 20.dp, bottom = 4.dp)) {
                                    MTextEllipsis(group.first().title, MetroType.medium)
                                    MText("${group.size} copies", MetroType.small, color = Metro.colors.subtle)
                                    if (group.all { it.id in selected }) {
                                        MText("Every copy is ticked: deleting them all leaves none.", MetroType.small,
                                            color = Metro.colors.accent, maxLines = 2)
                                    }
                                }
                            }
                            items(group, key = { it.id }) { song ->
                                CopyRow(
                                    song,
                                    ticked = song.id in selected,
                                    playing = preview.playing == song.id,
                                    onTick = { if (song.id in selected) selected.remove(song.id) else selected.add(song.id) },
                                    onPreview = { preview.toggle(song) },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/** One copy: tick box, where and what it is, and a preview button. */
@Composable
private fun CopyRow(song: Song, ticked: Boolean, playing: Boolean, onTick: () -> Unit, onPreview: () -> Unit) {
    val c = Metro.colors
    Row(
        Modifier.testTag("dup:tick:${song.id}").metroClick(onClick = onTick).padding(horizontal = 24.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(30.dp)
                .background(if (ticked) c.accent else Color.Transparent)
                .border(2.dp, if (ticked) c.accent else c.foreground),
            contentAlignment = Alignment.Center,
        ) {
            if (ticked) Icon(Icons.Filled.Check, "ticked", tint = Color.White, modifier = Modifier.size(22.dp))
        }
        Column(Modifier.weight(1f)) {
            MTextEllipsis(song.title, MetroType.normal)
            MTextEllipsis("${song.artist} • ${song.album}", MetroType.small, color = c.subtle)
            MText(
                "${folderOf(song.path)}\n${song.path.substringAfterLast('.', "").uppercase()} • ${formatDuration(song.durationMs)}",
                MetroType.small, color = c.subtle, maxLines = 3,
            )
        }
        RoundButton(
            if (playing) Icons.Filled.Stop else Icons.Filled.PlayArrow,
            if (playing) "stop" else "listen",
            Modifier.testTag("dup:listen:${song.id}"),
            size = 44,
            highlighted = playing,
            onClick = onPreview,
        )
    }
}

/** The folder a file is in, without the storage prefix. */
private fun folderOf(path: String): String =
    path.substringBeforeLast('/').removePrefix("/storage/emulated/0/").ifEmpty { "/" }

/** A small player of its own for previews, so the queue is left alone. */
private class Preview(val player: ExoPlayer) {
    var playing by mutableStateOf<Long?>(null)

    fun toggle(song: Song) {
        if (playing == song.id) return stop()
        player.setMediaItem(MediaItem.fromUri(song.uri))
        // From a little way in: intros are often quiet.
        player.prepare()
        player.seekTo(minOf(30_000L, song.durationMs / 3))
        player.play()
        playing = song.id
    }

    fun stop() {
        player.stop()
        playing = null
    }
}

@Composable
private fun rememberPreview(): Preview {
    val context = LocalContext.current
    val preview = remember {
        Preview(
            ExoPlayer.Builder(context)
                // Taking audio focus pauses the main player while a preview plays.
                .setAudioAttributes(
                    AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(),
                    true,
                )
                .build(),
        )
    }
    DisposableEffect(preview) {
        val listener = object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_ENDED) preview.playing = null
            }
        }
        preview.player.addListener(listener)
        onDispose { preview.player.release() }
    }
    return preview
}
