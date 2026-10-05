package com.tune.music.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.tune.music.MainViewModel
import com.tune.music.Screen
import com.tune.music.data.Library
import com.tune.music.ui.components.JumpGrid
import com.tune.music.ui.components.JumpList
import com.tune.music.ui.components.MText
import com.tune.music.ui.components.MTextEllipsis
import com.tune.music.ui.components.MenuItem
import com.tune.music.ui.components.Overlay
import com.tune.music.ui.components.Pivot
import com.tune.music.ui.components.metroClick
import com.tune.music.ui.theme.Metro
import com.tune.music.ui.theme.MetroType

val COLLECTION_PIVOTS = listOf("artists", "albums", "songs", "playlists", "genres")

@Composable
fun CollectionScreen(vm: MainViewModel, actions: Actions, initial: Int) {
    val lib by vm.library.collectAsState()
    Pivot("music", COLLECTION_PIVOTS, initial) { page ->
        when (page) {
            0 -> Artists(lib, vm, actions)
            1 -> Albums(lib, vm, actions)
            2 -> Songs(lib, actions)
            3 -> Playlists(vm, actions)
            else -> Genres(lib, vm)
        }
    }
}

@Composable
private fun Artists(lib: Library, vm: MainViewModel, actions: Actions) {
    if (lib.loaded && lib.artists.isEmpty()) return EmptyNote("No artists.")
    JumpList(lib.artists, { it.name }, { "ar-" + it.name }) { a ->
        ArtistRow(a, onLongClick = { actions.artistMenu(a) }) { vm.navigate(Screen.ArtistPage(a.name)) }
    }
}

@Composable
private fun Albums(lib: Library, vm: MainViewModel, actions: Actions) {
    if (lib.loaded && lib.albums.isEmpty()) return EmptyNote("No albums.")
    val grid by vm.albumGrid.collectAsState()
    if (grid) {
        JumpGrid(lib.albums, { it.title }, { "al-" + it.id }, Modifier.testTag("albums:grid")) { a ->
            AlbumTile(a, onLongClick = { actions.albumMenu(a) }) { vm.navigate(Screen.AlbumPage(a.id)) }
        }
    } else {
        JumpList(lib.albums, { it.title }, { "al-" + it.id }, Modifier.testTag("albums:list")) { a ->
            AlbumRow(a, onLongClick = { actions.albumMenu(a) }) { vm.navigate(Screen.AlbumPage(a.id)) }
        }
    }
}

@Composable
private fun Songs(lib: Library, actions: Actions) {
    if (lib.loaded && lib.songs.isEmpty()) return EmptyNote("No songs. Copy music into your phone's Music folder.")
    val index = remember(lib.songs) { lib.songs.withIndex().associate { it.value.id to it.index } }
    JumpList(
        lib.songs, { it.title }, { "s-" + it.id },
        header = {
            item(key = "shuffle") {
                MText("shuffle all", MetroType.medium, color = Metro.colors.accent,
                    modifier = Modifier.fillMaxWidth().metroClick { actions.shuffle(lib.songs) }.padding(horizontal = 24.dp, vertical = 10.dp))
            }
        },
        headerCount = 1,
    ) { s ->
        SongRow(s, onLongClick = { actions.songMenu(s) }) { actions.play(lib.songs, index[s.id] ?: 0) }
    }
}

@Composable
private fun Playlists(vm: MainViewModel, actions: Actions) {
    val lists by vm.playlists.playlists.collectAsState()
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 96.dp)) {
        item {
            MText("new playlist", MetroType.medium, color = Metro.colors.accent,
                modifier = Modifier.fillMaxWidth().metroClick { actions.newPlaylist() }.padding(horizontal = 24.dp, vertical = 10.dp))
        }
        items(lists, key = { it.id }) { p ->
            Column(
                Modifier
                    .fillMaxWidth()
                    .metroClick(onLongClick = {
                        actions.overlays.show(
                            Overlay.Menu(
                                listOf(
                                    MenuItem("play") { actions.play(vm.playlistSongs(p.id)) },
                                    MenuItem("rename") { actions.overlays.input("rename playlist", p.name) { vm.playlists.rename(p.id, it) } },
                                    MenuItem("delete") {
                                        actions.overlays.confirm("delete playlist?", "\"${p.name}\" will be deleted. The songs stay on your phone.", "delete") {
                                            vm.playlists.delete(p.id)
                                        }
                                    },
                                ),
                            ),
                        )
                    }) { vm.navigate(Screen.PlaylistPage(p.id)) }
                    .padding(horizontal = 24.dp, vertical = 4.dp),
            ) {
                MTextEllipsis(p.name.lowercase(), MetroType.extraLarge)
                MText(songCount(p.songIds.size), MetroType.small, color = Metro.colors.subtle)
            }
        }
    }
}

@Composable
private fun Genres(lib: Library, vm: MainViewModel) {
    if (lib.loaded && lib.genres.isEmpty()) return EmptyNote("No genres.")
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 96.dp)) {
        items(lib.genres, key = { it.name }) { g ->
            Column(
                Modifier
                    .fillMaxWidth()
                    .metroClick { vm.navigate(Screen.GenrePage(g.name)) }
                    .padding(horizontal = 24.dp, vertical = 4.dp),
            ) {
                MTextEllipsis(g.name.lowercase(), MetroType.extraLarge)
                MText(songCount(g.songs.size), MetroType.small, color = Metro.colors.subtle)
            }
        }
    }
}
