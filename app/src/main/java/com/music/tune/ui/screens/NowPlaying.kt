package com.music.tune.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.QueueMusic
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.RepeatOne
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.music.tune.MainViewModel
import com.music.tune.Screen
import com.music.tune.data.formatDuration
import com.music.tune.playback.RepeatMode
import com.music.tune.ui.components.AlbumArt
import com.music.tune.ui.components.AppBarButton
import com.music.tune.ui.components.MText
import com.music.tune.ui.components.MTextEllipsis
import com.music.tune.ui.components.MenuItem
import com.music.tune.ui.components.PageHeader
import com.music.tune.ui.components.RoundButton
import com.music.tune.ui.components.VSpace
import com.music.tune.ui.theme.Metro
import com.music.tune.ui.theme.MetroType
import kotlin.math.abs

@Composable
fun NowPlayingScreen(vm: MainViewModel, actions: Actions) {
    val st by vm.player.state.collectAsState()
    val lib by vm.library.collectAsState()
    val song = st.currentId?.let { lib.song(it) }
    val c = Metro.colors

    if (song == null) {
        Column(Modifier.fillMaxSize().statusBarsPadding()) {
            PageHeader("music", "now playing")
            EmptyNote("Nothing is playing. Pick something from your collection.")
        }
        return
    }

    BarPage(
        listOf(
            AppBarButton(Icons.Filled.Shuffle, if (st.shuffle) "shuffle on" else "shuffle", highlighted = st.shuffle) {
                vm.player.toggleShuffle()
            },
            AppBarButton(
                if (st.repeat == RepeatMode.ONE) Icons.Filled.RepeatOne else Icons.Filled.Repeat,
                when (st.repeat) {
                    RepeatMode.OFF -> "repeat"
                    RepeatMode.ALL -> "repeat all"
                    RepeatMode.ONE -> "repeat one"
                },
                highlighted = st.repeat != RepeatMode.OFF,
            ) { vm.player.cycleRepeat() },
            AppBarButton(Icons.Filled.QueueMusic, "queue") { vm.navigate(Screen.Queue) },
        ),
        listOf(
            MenuItem("edit song info") { vm.navigate(Screen.EditSong(song.id)) },
            MenuItem("find info online") { vm.navigate(Screen.Identify(listOf(song.id), null)) },
            MenuItem("add to playlist") { actions.addToPlaylist(listOf(song)) },
            MenuItem("save now playing as playlist") {
                val songs = st.queue.mapNotNull { lib.song(it) }
                actions.overlays.input("save as playlist", "", "save") { vm.newPlaylist(it, songs) }
            },
            MenuItem("go to album") { vm.navigate(Screen.AlbumPage(song.albumId)) },
            MenuItem("go to artist") { vm.navigate(Screen.ArtistPage(song.albumArtist)) },
        ),
    ) {
        Column(Modifier.fillMaxSize().statusBarsPadding()) {
            // Artist name, huge and cropped like the WP8 player.
            MText(
                song.artist.lowercase(), MetroType.panorama.copy(fontSize = MetroType.panorama.fontSize * 0.55f),
                color = c.title,
                modifier = Modifier
                    .wrapContentWidth(Alignment.Start, unbounded = true)
                    .padding(start = 18.dp, top = 4.dp),
            )
            MTextEllipsis(song.album.lowercase(), MetroType.medium, color = c.subtle, modifier = Modifier.padding(horizontal = 24.dp))
            VSpace(12)

            // Album art: swipe left/right to skip, tap to play/pause.
            var drag by remember { mutableFloatStateOf(0f) }
            BoxWithConstraints(
                Modifier
                    .weight(1f, fill = true)
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp),
                contentAlignment = Alignment.TopStart,
            ) {
                val side = minOf(maxWidth, maxHeight)
                AlbumArt(
                    song.albumId, song.uri,
                    Modifier
                        .testTag("np:art")
                        .height(side)
                        .aspectRatio(1f)
                        .graphicsLayer {
                            translationX = drag
                            alpha = 1f - (abs(drag) / 900f).coerceAtMost(0.6f)
                        }
                        .pointerInput(song.id) {
                            detectHorizontalDragGestures(
                                onDragEnd = {
                                    when {
                                        drag < -160 -> vm.player.next()
                                        drag > 160 -> vm.player.previous()
                                    }
                                    drag = 0f
                                },
                                onDragCancel = { drag = 0f },
                            ) { _, d -> drag += d }
                        }
                        .pointerInput(Unit) { detectTapGestures { vm.player.togglePlay() } },
                )
            }

            VSpace(12)
            SeekBar(st.positionMs, st.durationMs) { vm.player.seekTo(it) }
            Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                MText(formatDuration(st.positionMs), MetroType.small, color = c.subtle)
                MText("-" + formatDuration((st.durationMs - st.positionMs).coerceAtLeast(0)), MetroType.small, color = c.subtle)
            }
            VSpace(6)
            MTextEllipsis(song.title, MetroType.large, modifier = Modifier.padding(horizontal = 24.dp).testTag("np:title"))
            st.upcoming.take(2).mapNotNull { lib.song(it) }.forEach { n ->
                MTextEllipsis(n.title, MetroType.normal, color = c.subtle, modifier = Modifier.padding(horizontal = 24.dp))
            }
            VSpace(14)
            Row(
                Modifier.fillMaxWidth().padding(bottom = 14.dp),
                horizontalArrangement = Arrangement.spacedBy(28.dp, Alignment.CenterHorizontally),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RoundButton(Icons.Filled.SkipPrevious, "previous", size = 56) { vm.player.previous() }
                RoundButton(if (st.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow, "play/pause", size = 72) {
                    vm.player.togglePlay()
                }
                RoundButton(Icons.Filled.SkipNext, "next", size = 56) { vm.player.next() }
            }
        }
    }
}

/** Thin Metro seek bar: accent fill on a dim track; tap or drag to seek. */
@Composable
private fun SeekBar(position: Long, duration: Long, onSeek: (Long) -> Unit) {
    val c = Metro.colors
    var dragging by remember { mutableStateOf<Float?>(null) }
    val frac = dragging ?: if (duration > 0) (position.toFloat() / duration).coerceIn(0f, 1f) else 0f
    BoxWithConstraints(
        Modifier
            .fillMaxWidth()
            .height(28.dp)
            .padding(horizontal = 24.dp)
            .testTag("np:seek")
            .pointerInput(duration) {
                detectTapGestures { o -> if (duration > 0) onSeek((o.x / size.width * duration).toLong()) }
            }
            .pointerInput(duration) {
                detectHorizontalDragGestures(
                    onDragStart = { o -> dragging = (o.x / size.width).coerceIn(0f, 1f) },
                    onDragEnd = {
                        dragging?.let { if (duration > 0) onSeek((it * duration).toLong()) }
                        dragging = null
                    },
                    onDragCancel = { dragging = null },
                ) { ch, _ -> dragging = (ch.position.x / size.width).coerceIn(0f, 1f) }
            },
        contentAlignment = Alignment.CenterStart,
    ) {
        Box(Modifier.fillMaxWidth().height(4.dp).background(c.disabled))
        Box(Modifier.fillMaxWidth(frac).height(4.dp).background(c.accent))
        Box(Modifier.offset(x = maxWidth * frac - 2.dp).height(14.dp).width(4.dp).background(c.foreground))
    }
}

@Composable
fun QueueScreen(vm: MainViewModel, actions: Actions) {
    val st by vm.player.state.collectAsState()
    val lib by vm.library.collectAsState()
    val list = rememberLazyListState()
    LaunchedEffect(Unit) { if (st.queuePosition > 2) list.scrollToItem(st.queuePosition - 2) }
    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        PageHeader("now playing", "queue")
        if (st.queue.isEmpty()) EmptyNote("The queue is empty.")
        LazyColumn(Modifier.fillMaxSize(), state = list, contentPadding = PaddingValues(bottom = 48.dp)) {
            itemsIndexed(st.queue, key = { i, id -> "$i-$id" }) { i, id ->
                val s = lib.song(id) ?: return@itemsIndexed
                SongRow(
                    s, current = i == st.queuePosition,
                    onLongClick = {
                        actions.overlays.menu(
                            MenuItem("play") { vm.player.jumpTo(i) },
                            MenuItem("remove from now playing") { vm.player.removeFromQueue(i) },
                            MenuItem("add to playlist") { actions.addToPlaylist(listOf(s)) },
                            MenuItem("edit info") { vm.navigate(Screen.EditSong(s.id)) },
                        )
                    },
                ) { vm.player.jumpTo(i) }
            }
        }
    }
}
