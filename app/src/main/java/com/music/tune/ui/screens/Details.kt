package com.music.tune.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.music.tune.MainViewModel
import com.music.tune.Screen
import com.music.tune.data.Song
import com.music.tune.data.formatDuration
import com.music.tune.ui.components.AlbumArt
import com.music.tune.ui.components.AppBar
import com.music.tune.ui.components.AppBarButton
import com.music.tune.ui.components.MText
import com.music.tune.ui.components.MTextEllipsis
import com.music.tune.ui.components.MenuItem
import com.music.tune.ui.components.PageHeader
import com.music.tune.ui.components.Pivot
import com.music.tune.ui.theme.Metro
import com.music.tune.ui.theme.MetroType

/** Page with content and an app bar pinned to the bottom. */
@Composable
fun BarPage(
    buttons: List<AppBarButton>,
    menu: List<MenuItem> = emptyList(),
    content: @Composable () -> Unit,
) {
    Column(Modifier.fillMaxSize()) {
        Box(Modifier.weight(1f)) { content() }
        AppBar(buttons, menu)
    }
}

@Composable
fun ArtistScreen(vm: MainViewModel, actions: Actions, name: String) {
    val lib by vm.library.collectAsState()
    val grid by vm.albumGrid.collectAsState()
    val artist = lib.artist(name)
    if (artist == null) {
        LaunchedEffect(lib.version) { if (lib.loaded) vm.back() }
        return
    }
    BarPage(
        listOf(
            AppBarButton(Icons.Filled.PlayArrow, "play") { actions.play(artist.songs) },
            AppBarButton(Icons.Filled.Shuffle, "shuffle") { actions.shuffle(artist.songs) },
        ),
        listOf(
            MenuItem("add to now playing") { vm.player.enqueue(artist.songs) },
            MenuItem("add to playlist") { actions.addToPlaylist(artist.songs) },
            MenuItem("organize files") { actions.organize(artist.songs) },
        ),
    ) {
        Pivot(artist.name, listOf("albums", "songs")) { page ->
            if (page == 0 && grid) {
                LazyVerticalGrid(
                    GridCells.Fixed(2),
                    Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(start = 24.dp, end = 24.dp, top = 8.dp, bottom = 24.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    items(artist.albums, key = { it.id }) { a ->
                        AlbumTile(a, showArtist = false, onLongClick = { actions.albumMenu(a) }) { vm.navigate(Screen.AlbumPage(a.id)) }
                    }
                }
            } else if (page == 0) {
                LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(top = 8.dp, bottom = 24.dp)) {
                    itemsIndexed(artist.albums, key = { _, a -> a.id }) { _, a ->
                        AlbumRow(a, showArtist = false, onLongClick = { actions.albumMenu(a) }) { vm.navigate(Screen.AlbumPage(a.id)) }
                    }
                }
            } else {
                SongList(artist.songs, actions, showAlbum = true)
            }
        }
    }
}

@Composable
fun AlbumScreen(vm: MainViewModel, actions: Actions, id: Long) {
    val lib by vm.library.collectAsState()
    val st by vm.player.state.collectAsState()
    val album = lib.album(id)
    if (album == null) {
        LaunchedEffect(lib.version) { if (lib.loaded) vm.back() }
        return
    }
    BarPage(
        listOf(
            AppBarButton(Icons.Filled.PlayArrow, "play") { actions.play(album.songs) },
            AppBarButton(Icons.Filled.Shuffle, "shuffle") { actions.shuffle(album.songs) },
        ),
        listOf(
            MenuItem("edit album info") { vm.navigate(Screen.EditAlbum(album.id)) },
            MenuItem("find album info online") { vm.navigate(Screen.Identify(album.songs.map { it.id }, album.id)) },
            MenuItem("merge with other albums") { vm.navigate(Screen.MergeAlbums(album.id)) },
            MenuItem("organize files") { actions.organize(album.songs) },
            MenuItem("add to now playing") { vm.player.enqueue(album.songs) },
            MenuItem("add to playlist") { actions.addToPlaylist(album.songs) },
            MenuItem("go to artist") { vm.navigate(Screen.ArtistPage(album.artist)) },
            MenuItem("delete") { actions.delete(album.songs, "album") { vm.back() } },
        ),
    ) {
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
            item {
                Column(Modifier.padding(start = 24.dp, top = 16.dp, end = 24.dp)) {
                    MText(album.artist.uppercase(), MetroType.appTitle)
                    MText(album.title.lowercase(), MetroType.pivot, maxLines = 2, color = Metro.colors.title)
                }
                Row(Modifier.padding(24.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    AlbumArt(album.id, album.songs.firstOrNull()?.uri, Modifier.size(160.dp))
                    Column {
                        if (album.year > 0) MText(album.year.toString(), MetroType.normal, color = Metro.colors.subtle)
                        MText(songCount(album.songs.size), MetroType.normal, color = Metro.colors.subtle)
                        MText(formatDuration(album.durationMs), MetroType.normal, color = Metro.colors.subtle)
                        album.songs.firstOrNull()?.genre?.takeIf { it != "unknown" }?.let {
                            MText(it.lowercase(), MetroType.normal, color = Metro.colors.subtle)
                        }
                    }
                }
            }
            val multiDisc = album.songs.maxOf { it.disc } > 1
            itemsIndexed(album.songs, key = { _, s -> s.id }) { i, s ->
                val num = when {
                    s.track <= 0 -> "${i + 1}"
                    multiDisc -> "${s.disc}.${s.track}"
                    else -> "${s.track}"
                }
                SongRow(
                    s, number = num, showAlbum = false, current = st.currentId == s.id,
                    onLongClick = { actions.songMenu(s) },
                ) { actions.play(album.songs, i) }
            }
        }
    }
}

@Composable
fun GenreScreen(vm: MainViewModel, actions: Actions, name: String) {
    val lib by vm.library.collectAsState()
    val genre = lib.genre(name) ?: return
    BarPage(
        listOf(
            AppBarButton(Icons.Filled.PlayArrow, "play") { actions.play(genre.songs) },
            AppBarButton(Icons.Filled.Shuffle, "shuffle") { actions.shuffle(genre.songs) },
        ),
        listOf(MenuItem("add to playlist") { actions.addToPlaylist(genre.songs) }),
    ) {
        Column {
            PageHeader("genre", genre.name)
            SongList(genre.songs, actions, showAlbum = true)
        }
    }
}

@Composable
fun PlaylistScreen(vm: MainViewModel, actions: Actions, id: String) {
    val lists by vm.playlists.playlists.collectAsState()
    val lib by vm.library.collectAsState()
    val p = lists.firstOrNull { it.id == id }
    if (p == null) {
        LaunchedEffect(Unit) { vm.back() }
        return
    }
    val songs = p.songIds.mapNotNull { lib.song(it) }
    BarPage(
        listOf(
            AppBarButton(Icons.Filled.PlayArrow, "play") { actions.play(songs) },
            AppBarButton(Icons.Filled.Shuffle, "shuffle") { actions.shuffle(songs) },
        ),
        listOf(
            MenuItem("rename") { actions.overlays.input("rename playlist", p.name) { vm.playlists.rename(p.id, it) } },
            MenuItem("delete playlist") {
                actions.overlays.confirm("delete playlist?", "\"${p.name}\" will be deleted. The songs stay on your phone.", "delete") {
                    vm.playlists.delete(p.id)
                }
            },
        ),
    ) {
        Column {
            PageHeader("playlist", p.name)
            if (songs.isEmpty()) EmptyNote("This playlist is empty. Long press any song and choose \"add to playlist\".")
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
                itemsIndexed(songs, key = { i, s -> "$i-${s.id}" }) { i, s ->
                    SongRow(
                        s,
                        onLongClick = {
                            actions.songMenu(
                                s,
                                listOfNotNull(
                                    MenuItem("remove from playlist") { vm.playlists.removeAt(p.id, i) },
                                    if (i > 0) MenuItem("move up") { vm.playlists.move(p.id, i, i - 1) } else null,
                                    if (i < songs.lastIndex) MenuItem("move down") { vm.playlists.move(p.id, i, i + 1) } else null,
                                ),
                            )
                        },
                    ) { actions.play(songs, i) }
                }
            }
        }
    }
}

@Composable
fun SongList(songs: List<Song>, actions: Actions, showAlbum: Boolean) {
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(top = 8.dp, bottom = 24.dp)) {
        itemsIndexed(songs, key = { _, s -> s.id }) { i, s ->
            SongRow(s, showAlbum = showAlbum, onLongClick = { actions.songMenu(s) }) { actions.play(songs, i) }
        }
    }
}
