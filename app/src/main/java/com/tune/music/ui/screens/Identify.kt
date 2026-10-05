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
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.input.ImeAction
import kotlinx.coroutines.launch
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.tune.music.MainViewModel
import com.tune.music.Screen
import com.tune.music.data.CoverImage
import com.tune.music.data.MusicBrainz
import com.tune.music.data.ReleaseCandidate
import com.tune.music.ui.components.AlbumArt
import com.tune.music.ui.components.AppBarButton
import com.tune.music.ui.components.MText
import com.tune.music.ui.components.MTextEllipsis
import com.tune.music.ui.components.MetroButton
import com.tune.music.ui.components.MetroTextBox
import com.tune.music.ui.components.PageHeader
import com.tune.music.ui.components.ProgressDots
import com.tune.music.ui.components.RemoteArt
import com.tune.music.ui.components.VSpace
import com.tune.music.ui.components.metroClick
import com.tune.music.ui.theme.Metro
import com.tune.music.ui.theme.MetroType

/**
 * Picard-style matching: fingerprint the songs, list the MusicBrainz releases
 * they appear on, then let the user pick the release and its cover. Releases
 * can also be searched by name (no AcoustID key needed for that).
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
    var byName by remember { mutableStateOf(key.isEmpty()) }

    val title = if (albumId != null || songIds.size > 1) "find album info" else "find song info"

    val c = chosen
    if (c != null) {
        BackHandler { chosen = null }
        ReleaseDetail(vm, songs, c, albumId, onBack = { chosen = null })
        return
    }

    if (byName) {
        if (key.isNotEmpty()) BackHandler { byName = false }
        NameSearch(vm, songs, title, noKey = key.isEmpty(), onListen = if (key.isEmpty()) null else ({ byName = false })) { chosen = it }
        return
    }

    LaunchedEffect(songIds, key) {
        if (results != null) return@LaunchedEffect
        error = null
        results = runCatching {
            vm.identifySongs(songs) { d, _ -> done = d }
        }.onFailure { error = it.message ?: it.toString() }.getOrNull()
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
            }
        }
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 48.dp)) {
            items(r.orEmpty(), key = { it.releaseId }) { rel ->
                ReleaseRow(rel.coverThumb, rel.album, rel.albumArtist, releaseFacts(rel.year, rel.country, rel.format, rel.type, rel.trackCount, rel.discCount),
                    if (songs.size > 1) rel.tracks.size to songs.size else null) { chosen = rel }
            }
            item {
                VSpace(16)
                MText("Not the right edition?", MetroType.small, color = Metro.colors.subtle, modifier = Modifier.padding(horizontal = 24.dp))
                VSpace(8)
                MetroButton("search by name", Modifier.padding(horizontal = 24.dp)) { byName = true }
            }
        }
    }
}

/** Searching MusicBrainz releases by album and artist name. */
@Composable
private fun NameSearch(
    vm: MainViewModel,
    songs: List<com.tune.music.data.Song>,
    title: String,
    noKey: Boolean,
    onListen: (() -> Unit)?,
    onChoose: (ReleaseCandidate) -> Unit,
) {
    val first = songs.firstOrNull()
    var album by remember { mutableStateOf(songs.groupingBy { it.album }.eachCount().maxByOrNull { it.value }?.key ?: first?.album.orEmpty()) }
    var artist by remember { mutableStateOf(songs.groupingBy { it.albumArtist }.eachCount().maxByOrNull { it.value }?.key ?: first?.albumArtist.orEmpty()) }
    var results by remember { mutableStateOf<List<MusicBrainz.Found>?>(null) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val list = rememberLazyListState()

    fun search() {
        if (busy || album.isBlank()) return
        scope.launch {
            busy = true
            error = null
            results = runCatching { vm.searchReleases(album, artist) }.onFailure { error = it.message ?: it.toString() }.getOrNull()
            busy = false
            // Bring the form and the results under it into view.
            list.animateScrollToItem(if (noKey) 1 else 0)
        }
    }

    fun open(found: MusicBrainz.Found) {
        if (busy) return
        scope.launch {
            busy = true
            error = null
            runCatching { vm.matchRelease(found, songs) }
                .onSuccess(onChoose)
                .onFailure { error = it.message ?: it.toString() }
            busy = false
        }
    }

    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        PageHeader("music", title)
        LazyColumn(Modifier.fillMaxSize().imePadding(), state = list, contentPadding = PaddingValues(bottom = 48.dp)) {
            if (noKey) item {
                EmptyNote("Identifying music by its sound uses AcoustID. Get a free API key at acoustid.org/new-application and enter it in settings.")
                MetroButton("open settings", Modifier.padding(horizontal = 24.dp)) { vm.navigate(Screen.Settings) }
                VSpace(16)
                MText("Or search MusicBrainz by name:", MetroType.normal, color = Metro.colors.subtle, modifier = Modifier.padding(horizontal = 24.dp))
                VSpace(8)
            }
            item {
                Column(Modifier.padding(horizontal = 24.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    MetroTextBox("album", album, { album = it })
                    MetroTextBox("artist", artist, { artist = it }, imeAction = ImeAction.Search, onSubmit = { search() })
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        // The main action last, where the eye ends up.
                        if (onListen != null) MetroButton("listen instead") { onListen() }
                        MetroButton("search", enabled = album.isNotBlank() && !busy) { search() }
                    }
                }
                VSpace(12)
                val r = results
                when {
                    busy -> ProgressDots()
                    error != null -> EmptyNote("Couldn't search: $error")
                    r == null -> {}
                    r.isEmpty() -> EmptyNote("No releases found on MusicBrainz.")
                    else -> MText("${r.size} releases found. Pick the one you own.", MetroType.small, color = Metro.colors.subtle,
                        modifier = Modifier.padding(horizontal = 24.dp))
                }
            }
            items(results.orEmpty(), key = { it.id }) { f ->
                ReleaseRow(f.coverThumb, f.title, f.artist, releaseFacts(f.year, f.country, f.format, f.type, f.trackCount, f.discCount), null) { open(f) }
            }
        }
    }
}

fun releaseFacts(year: Int, country: String, format: String, type: String, trackCount: Int, discCount: Int): String =
    listOf(
        year.takeIf { it > 0 }?.toString(),
        country.ifEmpty { null },
        format.ifEmpty { null },
        type.ifEmpty { null }?.lowercase(),
        "$trackCount tracks" + if (discCount > 1) " / $discCount discs" else "",
    ).filterNotNull().joinToString(" • ")

@Composable
fun ReleaseRow(cover: String, album: String, artist: String, facts: String, matched: Pair<Int, Int>?, onClick: () -> Unit) {
    val c = Metro.colors
    Row(
        Modifier
            .fillMaxWidth()
            .metroClick(onClick = onClick)
            .padding(horizontal = 24.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        RemoteArt(cover, Modifier.size(84.dp))
        Column(Modifier.weight(1f)) {
            MTextEllipsis(album, MetroType.medium)
            MTextEllipsis(artist, MetroType.normal, color = c.subtle)
            MTextEllipsis(facts, MetroType.small, color = c.subtle)
            if (matched != null) {
                val (n, of) = matched
                MText("matches $n of $of songs", MetroType.small, color = if (n == of) c.accent else c.subtle)
            }
        }
    }
}

@Composable
fun ReleaseDetail(
    vm: MainViewModel,
    songs: List<com.tune.music.data.Song>,
    rel: ReleaseCandidate,
    albumId: Long?,
    onBack: () -> Unit,
    // Album may have been re-keyed by MediaStore; by default go back past the editor.
    onApplied: () -> Unit = { vm.back() },
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
                vm.applyRelease(songs, rel, cover) { onApplied() }
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
