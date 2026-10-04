package com.tune.music.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.TravelExplore
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.tune.music.MainViewModel
import com.tune.music.Screen
import com.tune.music.data.Song
import com.tune.music.data.TagEdit
import com.tune.music.data.TagValues
import com.tune.music.ui.components.AlbumArt
import com.tune.music.ui.components.AppBarButton
import com.tune.music.ui.components.MText
import com.tune.music.ui.components.MenuItem
import com.tune.music.ui.components.MetroTextBox
import com.tune.music.ui.components.PageHeader
import com.tune.music.ui.components.ProgressDots
import com.tune.music.ui.components.VSpace
import com.tune.music.ui.components.metroClick
import com.tune.music.ui.theme.Metro
import com.tune.music.ui.theme.MetroType

/** Returns the new value only if it differs from the original (null = unchanged). */
private fun changed(new: String, old: String): String? = new.trim().takeIf { it != old.trim() }

@Composable
fun EditSongScreen(vm: MainViewModel, actions: Actions, id: Long) {
    val lib by vm.library.collectAsState()
    val organize by vm.autoOrganize.collectAsState()
    val song = lib.song(id) ?: return
    var orig by remember { mutableStateOf<TagValues?>(null) }
    var v by remember { mutableStateOf(TagValues()) }
    LaunchedEffect(id) {
        val t = vm.readTags(song)
        orig = t
        v = t
    }

    BarPage(
        listOf(
            AppBarButton(Icons.Filled.Check, "save") {
                val o = orig ?: return@AppBarButton
                val edit = TagEdit(
                    title = changed(v.title, o.title),
                    artist = changed(v.artist, o.artist),
                    album = changed(v.album, o.album),
                    albumArtist = changed(v.albumArtist, o.albumArtist),
                    genre = changed(v.genre, o.genre),
                    year = changed(v.year, o.year),
                    track = changed(v.track, o.track),
                    disc = changed(v.disc, o.disc),
                )
                if (edit == TagEdit() && !organize) vm.back()
                else vm.saveTags(mapOf(song to edit)) { vm.back() }
            },
            AppBarButton(Icons.Filled.TravelExplore, "find online") { vm.navigate(Screen.Identify(listOf(song.id), null)) },
            AppBarButton(Icons.Filled.Close, "cancel") { vm.back() },
        ),
        listOf(MenuItem("organize file") { actions.organize(listOf(song)) }),
    ) {
        Column(Modifier.fillMaxSize().statusBarsPadding()) {
            PageHeader("edit song info", song.title)
            if (orig == null) {
                ProgressDots()
                return@Column
            }
            LazyColumn(
                Modifier.fillMaxSize().imePadding(),
                contentPadding = PaddingValues(start = 24.dp, end = 24.dp, bottom = 48.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                item { MetroTextBox("title", v.title, { v = v.copy(title = it) }) }
                item { MetroTextBox("artist", v.artist, { v = v.copy(artist = it) }) }
                item { MetroTextBox("album", v.album, { v = v.copy(album = it) }) }
                item { MetroTextBox("album artist", v.albumArtist, { v = v.copy(albumArtist = it) }) }
                item { MetroTextBox("genre", v.genre, { v = v.copy(genre = it) }) }
                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        MetroTextBox("year", v.year, { v = v.copy(year = it) }, Modifier.weight(1f), numeric = true)
                        MetroTextBox("track", v.track, { v = v.copy(track = it) }, Modifier.weight(1f), numeric = true)
                        MetroTextBox("disc", v.disc, { v = v.copy(disc = it) }, Modifier.weight(1f), numeric = true)
                    }
                }
                item {
                    MText("file", MetroType.small, color = Metro.colors.subtle)
                    MText(song.path, MetroType.small, maxLines = 3)
                    val preview = if (organize) vm.organizeTarget(song.copy(
                            title = v.title.ifBlank { song.title },
                            albumArtist = v.albumArtist.ifBlank { v.artist.ifBlank { song.albumArtist } },
                            album = v.album.ifBlank { song.album },
                            track = v.track.substringBefore('/').toIntOrNull() ?: song.track,
                        )) else null
                    if (preview != null) {
                        VSpace(8)
                        MText("will be moved to", MetroType.small, color = Metro.colors.subtle)
                        MText(preview, MetroType.small, maxLines = 3)
                    }
                }
            }
        }
    }
}

private class TrackRow(val song: Song, title: String, track: String) {
    var title by mutableStateOf(title)
    var track by mutableStateOf(track)
}

@Composable
fun EditAlbumScreen(vm: MainViewModel, actions: Actions, id: Long) {
    val lib by vm.library.collectAsState()
    val album = lib.album(id) ?: return
    val album0 = remember(album.id) { album.title }
    var title by remember { mutableStateOf(album.title) }
    var artist by remember { mutableStateOf(album.artist) }
    val genre0 = album.songs.first().genre.takeIf { it != "unknown" }.orEmpty()
    var genre by remember { mutableStateOf(genre0) }
    val year0 = album.year.takeIf { it > 0 }?.toString().orEmpty()
    var year by remember { mutableStateOf(year0) }
    var art by remember { mutableStateOf<Pair<ByteArray, String>?>(null) }
    val rows = remember(album.id) {
        mutableStateListOf<TrackRow>().apply {
            album.songs.forEach { add(TrackRow(it, it.title, if (it.track > 0) it.track.toString() else "")) }
        }
    }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) art = vm.readImage(uri)
    }

    BarPage(
        listOf(
            AppBarButton(Icons.Filled.Check, "save") {
                val edits = rows.associate { r ->
                    val s = r.song
                    r.song to TagEdit(
                        title = changed(r.title, s.title),
                        track = changed(r.track, if (s.track > 0) s.track.toString() else ""),
                        album = changed(title, album0),
                        albumArtist = changed(artist, s.albumArtist),
                        genre = changed(genre, genre0),
                        year = changed(year, year0),
                        artwork = art?.first,
                        artworkMime = art?.second,
                    )
                }.filterValues { it != TagEdit() }
                if (edits.isEmpty()) vm.back() else vm.saveTags(edits) { vm.back() }
            },
            AppBarButton(Icons.Filled.TravelExplore, "find online") {
                vm.navigate(Screen.Identify(album.songs.map { it.id }, album.id))
            },
            AppBarButton(Icons.Filled.Close, "cancel") { vm.back() },
        ),
        listOf(MenuItem("organize files") { actions.organize(album.songs) }),
    ) {
        Column(Modifier.fillMaxSize().statusBarsPadding()) {
            PageHeader("edit album info", album.title)
            LazyColumn(
                Modifier.fillMaxSize().imePadding(),
                contentPadding = PaddingValues(start = 24.dp, end = 24.dp, bottom = 48.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        Box(Modifier.size(140.dp).metroClick {
                            picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                        }) {
                            val a = art
                            if (a != null) AsyncImage(a.first, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                            else AlbumArt(album.id, album.songs.first().uri, Modifier.fillMaxSize())
                        }
                        Column {
                            MText("cover", MetroType.small, color = Metro.colors.subtle)
                            MText(if (art != null) "new cover chosen" else "tap to choose\na new cover", MetroType.normal, maxLines = 2)
                            if (art != null) {
                                VSpace(8)
                                MText("undo", MetroType.normal, color = Metro.colors.accent, modifier = Modifier.metroClick { art = null })
                            }
                        }
                    }
                }
                item { MetroTextBox("album", title, { title = it }) }
                item { MetroTextBox("album artist", artist, { artist = it }) }
                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        MetroTextBox("genre", genre, { genre = it }, Modifier.weight(2f))
                        MetroTextBox("year", year, { year = it }, Modifier.weight(1f), numeric = true)
                    }
                }
                item { MText("songs", MetroType.large, modifier = Modifier.padding(top = 8.dp)) }
                itemsIndexed(rows, key = { _, r -> r.song.id }) { _, r ->
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        MetroTextBox(null, r.track, { r.track = it }, Modifier.width(64.dp), numeric = true, tag = "rowtrack:${r.song.title}")
                        MetroTextBox(null, r.title, { r.title = it }, Modifier.weight(1f), tag = "rowtitle:${r.song.title}")
                    }
                }
            }
        }
    }
}
