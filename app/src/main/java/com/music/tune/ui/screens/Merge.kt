package com.music.tune.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.TravelExplore
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.music.tune.MainViewModel
import com.music.tune.Screen
import com.music.tune.data.Album
import com.music.tune.data.Consolidator
import com.music.tune.data.sortKey
import com.music.tune.ui.components.AppBarButton
import com.music.tune.ui.components.MText
import com.music.tune.ui.components.MTextEllipsis
import com.music.tune.ui.components.MetroButton
import com.music.tune.ui.components.PageHeader
import com.music.tune.ui.components.ProgressDots
import com.music.tune.ui.components.RemoteArt
import com.music.tune.ui.components.VSpace
import com.music.tune.ui.components.metroClick
import com.music.tune.ui.theme.Metro
import com.music.tune.ui.theme.MetroType

/**
 * Picking albums to merge, starting from one. Likely companions (same album
 * artist, or a title sharing words) are listed first.
 */
@Composable
fun MergeAlbumsScreen(vm: MainViewModel, firstAlbumId: Long) {
    val lib by vm.library.collectAsState()
    val first = lib.album(firstAlbumId) ?: return
    val selected = remember(firstAlbumId) { mutableStateListOf(firstAlbumId) }
    val ordered = remember(lib.version, firstAlbumId) {
        val words = words(first.title)
        val others = lib.albums.filter { it.id != first.id }
        val (likely, rest) = others.partition { it.artist.equals(first.artist, true) || words(it.title).any { w -> w in words } }
        listOf(first) + likely.sortedBy { sortKey(it.title) } + rest
    }
    val songs = { lib.albums.filter { it.id in selected }.flatMap { it.songs }.map { it.id } }

    fun need2(go: () -> Unit) = if (selected.size < 2) vm.toast("pick at least two albums") else go()

    BarPage(
        listOf(
            AppBarButton(Icons.Filled.Edit, "edit as one") { need2 { vm.navigate(Screen.EditAsAlbum(songs())) } },
            AppBarButton(Icons.Filled.TravelExplore, "find online") { need2 { vm.navigate(Screen.Identify(songs(), null)) } },
            AppBarButton(Icons.Filled.Close, "cancel") { vm.back() },
        ),
    ) {
        Column(Modifier.fillMaxSize().statusBarsPadding()) {
            PageHeader("merge albums", "${selected.size} selected")
            MText(
                "Pick the albums that belong together, then edit them as one album, or find the release that has the most of their songs.",
                MetroType.small, color = Metro.colors.subtle, maxLines = 3, modifier = Modifier.padding(horizontal = 24.dp),
            )
            VSpace(8)
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 48.dp)) {
                items(ordered, key = { it.id }) { a ->
                    SelectableAlbum(a, a.id in selected) {
                        if (a.id in selected) selected.remove(a.id) else selected.add(a.id)
                    }
                }
            }
        }
    }
}

@Composable
private fun SelectableAlbum(album: Album, selected: Boolean, onClick: () -> Unit) {
    Row(Modifier.height(IntrinsicSize.Min).testTag("merge:${album.title}").metroClick(onClick = onClick)) {
        // An accent bar marks the chosen albums.
        Box(Modifier.width(6.dp).fillMaxHeight().background(if (selected) Metro.colors.accent else Color.Transparent))
        Box(Modifier.weight(1f)) {
            AlbumRow(album, inset = true, onClick = onClick)
        }
    }
}

private fun words(s: String) = s.lowercase().split(Regex("[^\\p{L}\\p{N}]+")).filter { it.length > 2 }.toSet()

/**
 * Scanning the collection (fingerprint + AcoustID once per song, saved), then
 * proposing releases that gather songs now split across albums.
 */
@Composable
fun ConsolidateScreen(vm: MainViewModel) {
    val lib by vm.library.collectAsState()
    val key by vm.acoustIdKey.collectAsState()
    val scan by vm.scan.collectAsState()
    var scanned by remember { mutableIntStateOf(0) }
    var proposals by remember { mutableStateOf<List<Consolidator.Proposal>?>(null) }
    var chosen by remember { mutableStateOf<Consolidator.Proposal?>(null) }
    var refresh by remember { mutableIntStateOf(0) }

    LaunchedEffect(lib.version, scan.running, scan.generation, refresh) {
        scanned = vm.scannedCount()
        if (!scan.running) proposals = vm.consolidationProposals()
    }

    val c = chosen
    if (c != null) {
        BackHandler { chosen = null }
        ReleaseDetail(vm, c.songs, c.release, null, onBack = { chosen = null }, onApplied = {
            chosen = null
            refresh++
        })
        return
    }

    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        PageHeader("music", "consolidate albums")
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 48.dp)) {
            item {
                Column(Modifier.padding(horizontal = 24.dp)) {
                    MText(
                        "Finds songs that are on one release but split across albums here (say, an anniversary " +
                            "edition whose tracks were matched to each original soundtrack) and gathers them on the " +
                            "release that has the most of them.",
                        MetroType.small, color = Metro.colors.subtle, maxLines = 5,
                    )
                    VSpace(12)
                    if (key.isEmpty()) {
                        MText("This needs an AcoustID API key (settings → network).", MetroType.normal, maxLines = 3)
                        VSpace(8)
                        MetroButton("open settings") { vm.openSettings("network") }
                    } else {
                        ScanControls(vm, scanned, lib.songs.size)
                    }
                    VSpace(16)
                }
            }
            val p = proposals
            when {
                scan.running -> {}
                p == null -> item { ProgressDots() }
                p.isEmpty() -> item {
                    EmptyNote(if (scanned == 0) "Scan the collection first." else "No albums to merge: no release gathers songs from different albums.")
                }
                else -> {
                    item {
                        MText(if (p.size == 1) "1 release gathers split albums. Tap it to review it." else "${p.size} releases gather split albums. Tap one to review it.", MetroType.small,
                            color = Metro.colors.subtle, modifier = Modifier.padding(horizontal = 24.dp))
                        VSpace(8)
                    }
                    items(p, key = { it.release.releaseId }) { pr ->
                        ProposalRow(pr) { chosen = pr }
                    }
                }
            }
        }
    }
}

/** "N of M songs scanned" with the scan / stop buttons; shared with settings. */
@Composable
fun ScanControls(vm: MainViewModel, scanned: Int, total: Int) {
    val scan by vm.scan.collectAsState()
    val c = Metro.colors
    MText(
        when {
            // Counted over the whole collection: songs scanned before are skipped, not redone.
            scan.running -> "${scan.already + scan.done} of ${scan.already + scan.total} songs scanned..."
            else -> "$scanned of $total songs scanned"
        },
        MetroType.normal, modifier = Modifier.testTag("scan:status"),
    )
    if (scan.failed > 0) MText("${scan.failed} songs couldn't be identified", MetroType.small, color = c.subtle)
    scan.error?.let { MText("Stopped: $it", MetroType.small, color = c.subtle, maxLines = 3) }
    if (scan.running) {
        VSpace(8)
        ProgressDots()
    }
    VSpace(10)
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        if (scan.running) MetroButton("stop") { vm.stopScan() }
        else MetroButton("scan collection", enabled = scanned < total) { vm.scanCollection() }
        if (!scan.running && scanned > 0) MetroButton("forget scan") { vm.clearScan() }
    }
}

@Composable
private fun ProposalRow(p: Consolidator.Proposal, onClick: () -> Unit) {
    val c = Metro.colors
    Row(
        Modifier.metroClick(onClick = onClick).padding(horizontal = 24.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        RemoteArt(p.release.coverThumb, Modifier.size(84.dp))
        Column(Modifier.weight(1f)) {
            MTextEllipsis(p.release.album, MetroType.medium)
            MTextEllipsis(p.release.albumArtist, MetroType.normal, color = c.subtle)
            MText("${p.songs.size} songs from: " + p.fromAlbums.joinToString(", "), MetroType.small, color = c.accent, maxLines = 3)
        }
    }
}
