package com.music.tune.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.music.tune.MainViewModel
import com.music.tune.Screen
import com.music.tune.data.Album
import com.music.tune.ui.components.AlbumArt
import com.music.tune.ui.components.MText
import com.music.tune.ui.components.MTextEllipsis
import com.music.tune.ui.components.Panorama
import com.music.tune.ui.components.VSpace
import com.music.tune.ui.components.metroClick
import com.music.tune.ui.theme.Metro
import com.music.tune.ui.theme.MetroType

/** The "music+videos" style hub: collection, history, new. */
@Composable
fun HubScreen(vm: MainViewModel, actions: Actions) {
    val lib by vm.library.collectAsState()
    val history by vm.history.collectAsState()
    val st by vm.player.state.collectAsState()

    Panorama(
        "music",
        listOf(
            "collection" to @Composable {
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                    val now = st.currentId?.let { lib.song(it) }
                    if (now != null) {
                        NowPlayingTile(now.albumId, now.uri, now.title, now.artist) { vm.navigate(Screen.NowPlaying) }
                        VSpace(16)
                    }
                    listOf("artists", "albums", "songs", "playlists", "genres").forEachIndexed { i, l ->
                        BigLink(l) { vm.navigate(Screen.Collection(i)) }
                    }
                    VSpace(16)
                    if (lib.songs.isNotEmpty()) {
                        MText("shuffle all", MetroType.medium, color = Metro.colors.accent,
                            modifier = Modifier.metroClick { actions.shuffle(lib.songs) }.padding(vertical = 8.dp))
                    }
                    MText("search", MetroType.medium, color = Metro.colors.subtle,
                        modifier = Modifier.metroClick { vm.navigate(Screen.Search) }.padding(vertical = 8.dp))
                    MText("settings", MetroType.medium, color = Metro.colors.subtle,
                        modifier = Modifier.metroClick { vm.navigate(Screen.Settings) }.padding(vertical = 8.dp))
                    VSpace(96)
                }
            },
            "history" to @Composable {
                val albums = history.mapNotNull { lib.album(it) }
                if (albums.isEmpty()) EmptyNote("Music you play will show up here.", inset = false)
                else AlbumTiles(albums, actions)
            },
            "new" to @Composable {
                val recent = lib.albums.sortedByDescending { a -> a.songs.maxOf { it.dateAdded } }.take(12)
                if (recent.isEmpty()) EmptyNote(if (lib.loaded) "No music found on this phone." else "", inset = false)
                else LazyColumn {
                    items(recent, key = { it.id }) { a ->
                        AlbumRow(a, inset = false, onLongClick = { actions.albumMenu(a) }) { vm.navigate(Screen.AlbumPage(a.id)) }
                    }
                }
            },
        ),
    )
}

@Composable
private fun NowPlayingTile(albumId: Long, uri: android.net.Uri, title: String, artist: String, onClick: () -> Unit) {
    Box(Modifier.size(200.dp).metroClick(onClick = onClick)) {
        AlbumArt(albumId, uri, Modifier.fillMaxSize())
        Column(
            Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth()
                .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.75f))))
                .padding(8.dp),
        ) {
            MText("now playing".uppercase(), MetroType.small.copy(fontSize = MetroType.small.fontSize * 0.8f), color = Color.White)
            MTextEllipsis(title, MetroType.normal, color = Color.White)
            MTextEllipsis(artist, MetroType.small, color = Color.White.copy(alpha = 0.7f))
        }
    }
}

@Composable
private fun AlbumTiles(albums: List<Album>, actions: Actions) {
    LazyVerticalGrid(
        GridCells.Fixed(2),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.padding(end = 12.dp),
    ) {
        items(albums, key = { it.id }) { a ->
            AlbumTile(a, onLongClick = { actions.albumMenu(a) }) { actions.vm.navigate(Screen.AlbumPage(a.id)) }
        }
    }
}
