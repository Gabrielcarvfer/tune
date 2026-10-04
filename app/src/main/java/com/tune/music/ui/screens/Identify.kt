package com.tune.music.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.tune.music.MainViewModel
import com.tune.music.Screen
import com.tune.music.data.CoverImage
import com.tune.music.data.ReleaseCandidate
import com.tune.music.ui.components.AlbumArt
import com.tune.music.ui.components.AppBarButton
import com.tune.music.ui.components.MText
import com.tune.music.ui.components.MTextEllipsis
import com.tune.music.ui.components.MetroButton
import com.tune.music.ui.components.PageHeader
import com.tune.music.ui.components.ProgressDots
import com.tune.music.ui.components.RemoteArt
import com.tune.music.ui.components.VSpace
import com.tune.music.ui.components.metroClick
import com.tune.music.ui.theme.Metro
import com.tune.music.ui.theme.MetroType

/**
 * Picard-style matching: fingerprint the songs, list the MusicBrainz releases
 * they appear on, then let the user pick the release and its cover.
 */
@Composable
fun IdentifyScreen(vm: MainViewModel, songIds: List<Long>, albumId: Long?) {
    val lib by vm.library.collectAsState()
    val key by vm.acoustIdKey.collectAsState()
    val songs = remember(songIds) { songIds.mapNotNull { lib.song(it) } }
    var done by remember { mutableIntStateOf(0) }
    var error by remember { mutableStateOf<String?>(null) }
    var results by remember { mutableStateOf<List<ReleaseCandidate>?>(null) }
    var chosen by remember { mutableStateOf<ReleaseCandidate?>(null) }

    val title = if (albumId != null) "find album info" else "find song info"

    if (key.isEmpty()) {
        Column(Modifier.fillMaxSize().statusBarsPadding()) {
            PageHeader("music", title)
            EmptyNote("Identifying music uses AcoustID. Get a free API key at acoustid.org/new-application and enter it in settings.")
            MetroButton("open settings", Modifier.padding(horizontal = 24.dp)) { vm.navigate(Screen.Settings) }
        }
        return
    }

    LaunchedEffect(songIds, key) {
        error = null
        results = runCatching {
            vm.identifySongs(songs) { d, _ -> done = d }
        }.onFailure { error = it.message ?: it.toString() }.getOrNull()
    }

    val c = chosen
    if (c != null) {
        BackHandler { chosen = null }
        ReleaseDetail(vm, songs, c, albumId) { chosen = null }
        return
    }

    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        PageHeader("music", title)
        val r = results
        when {
            error != null -> EmptyNote("Couldn't identify: $error")
            r == null -> {
                MText(
                    if (songs.size == 1) "listening to the song..." else "listening to song ${minOf(done + 1, songs.size)} of ${songs.size}...",
                    MetroType.normal, color = Metro.colors.subtle, modifier = Modifier.padding(horizontal = 24.dp),
                )
                VSpace(12)
                ProgressDots()
            }
            r.isEmpty() -> EmptyNote("No matches found on AcoustID.")
            else -> {
                MText("${r.size} releases found. Pick the one you own.", MetroType.small, color = Metro.colors.subtle,
                    modifier = Modifier.padding(horizontal = 24.dp))
                VSpace(8)
                LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 48.dp)) {
                    items(r, key = { it.releaseId }) { rel ->
                        ReleaseRow(rel, songs.size) { chosen = rel }
                    }
                }
            }
        }
    }
}

@Composable
private fun ReleaseRow(rel: ReleaseCandidate, songCount: Int, onClick: () -> Unit) {
    val c = Metro.colors
    Row(
        Modifier
            .fillMaxWidth()
            .metroClick(onClick = onClick)
            .padding(horizontal = 24.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        RemoteArt(rel.coverThumb, Modifier.size(84.dp))
        Column(Modifier.weight(1f)) {
            MTextEllipsis(rel.album, MetroType.medium)
            MTextEllipsis(rel.albumArtist, MetroType.normal, color = c.subtle)
            MTextEllipsis(
                listOf(
                    rel.year.takeIf { it > 0 }?.toString(),
                    rel.country.ifEmpty { null },
                    rel.format.ifEmpty { null },
                    rel.type.ifEmpty { null }?.lowercase(),
                    "${rel.trackCount} tracks" + if (rel.discCount > 1) " / ${rel.discCount} discs" else "",
                ).filterNotNull().joinToString(" • "),
                MetroType.small, color = c.subtle,
            )
            if (songCount > 1) {
                MText("matches ${rel.tracks.size} of $songCount songs", MetroType.small,
                    color = if (rel.tracks.size == songCount) c.accent else c.subtle)
            }
        }
    }
}

@Composable
private fun ReleaseDetail(
    vm: MainViewModel,
    songs: List<com.tune.music.data.Song>,
    rel: ReleaseCandidate,
    albumId: Long?,
    onBack: () -> Unit,
) {
    var covers by remember(rel.releaseId) { mutableStateOf<List<CoverImage>?>(null) }
    // null = keep the current cover
    var cover by remember(rel.releaseId) { mutableStateOf<CoverImage?>(null) }
    LaunchedEffect(rel.releaseId) {
        covers = runCatching { vm.covers(rel) }.getOrDefault(emptyList())
        cover = covers?.firstOrNull { it.front }
    }
    val c = Metro.colors

    BarPage(
        listOf(
            AppBarButton(Icons.Filled.Check, "apply") {
                vm.applyRelease(songs, rel, cover) {
                    // Album may have been re-keyed by MediaStore; go back past the editor.
                    vm.back()
                }
            },
            AppBarButton(Icons.Filled.Close, "back") { onBack() },
        ),
    ) {
        LazyVerticalGrid(
            GridCells.Fixed(3),
            modifier = Modifier.fillMaxSize().statusBarsPadding(),
            contentPadding = PaddingValues(start = 24.dp, end = 24.dp, bottom = 48.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Column(Modifier.padding(top = 16.dp)) {
                    MText(rel.albumArtist.uppercase(), MetroType.appTitle)
                    MText(rel.album.lowercase(), MetroType.pivot, maxLines = 2, color = c.title)
                    MText(
                        listOfNotNull(rel.year.takeIf { it > 0 }?.toString(), rel.country.ifEmpty { null }, rel.format.ifEmpty { null })
                            .joinToString(" • "),
                        MetroType.normal, color = c.subtle,
                    )
                    VSpace(16)
                    MText("songs", MetroType.large)
                    VSpace(4)
                    songs.forEach { s ->
                        val m = rel.tracks[s.id]
                        Column(Modifier.padding(vertical = 4.dp)) {
                            MTextEllipsis(
                                if (m != null) "%02d  %s".format(m.track, m.title) else s.title,
                                MetroType.normal,
                                color = if (m != null) c.foreground else c.subtle,
                            )
                            MTextEllipsis(
                                if (m == null) "not on this release — only album info will change"
                                else if (m.title != s.title || m.artist != s.artist) "was: ${s.title} — ${s.artist}"
                                else m.artist,
                                MetroType.small, color = c.subtle,
                            )
                        }
                    }
                    VSpace(16)
                    MText("cover", MetroType.large)
                    if (covers == null) {
                        VSpace(8)
                        ProgressDots()
                    }
                    VSpace(8)
                }
            }
            item {
                CoverTile(selected = cover == null, label = "keep current", onClick = { cover = null }) {
                    val first = songs.first()
                    AlbumArt(albumId ?: first.albumId, first.uri, Modifier.fillMaxSize())
                }
            }
            items(covers.orEmpty(), key = { it.full }) { img ->
                CoverTile(
                    selected = cover == img,
                    label = img.types.joinToString(", ").lowercase().ifEmpty { if (img.front) "front" else "" },
                    onClick = { cover = img },
                ) { RemoteArt(img.thumb, Modifier.fillMaxSize()) }
            }
            if (covers?.isEmpty() == true) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    MText("The Cover Art Archive has no images for this release.", MetroType.small, color = c.subtle, maxLines = 2)
                }
            }
        }
    }
}

@Composable
private fun CoverTile(selected: Boolean, label: String, onClick: () -> Unit, image: @Composable () -> Unit) {
    val c = Metro.colors
    Column(Modifier.metroClick(onClick = onClick)) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .border(if (selected) 4.dp else 0.dp, if (selected) c.accent else Color.Transparent),
        ) {
            Box(Modifier.fillMaxSize().padding(if (selected) 4.dp else 0.dp)) { image() }
        }
        MTextEllipsis(label, MetroType.small, color = if (selected) c.accent else c.subtle)
    }
}
