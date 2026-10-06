package com.music.tune.ui.screens

import android.content.Intent
import android.os.Build
import android.provider.Settings
import android.net.Uri
import androidx.compose.foundation.background
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
import com.music.tune.ui.components.Pivot
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import kotlinx.coroutines.launch
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.music.tune.data.LibraryFolder
import com.music.tune.data.Organizer
import android.provider.DocumentsContract
import com.music.tune.MainViewModel
import com.music.tune.Screen
import com.music.tune.data.Normalization
import com.music.tune.ThemeMode
import com.music.tune.ui.components.MText
import com.music.tune.ui.components.MetroButton
import com.music.tune.ui.components.MetroTextBox
import com.music.tune.ui.components.ProgressDots
import com.music.tune.ui.components.PageHeader
import com.music.tune.ui.components.VSpace
import com.music.tune.ui.components.metroClick
import com.music.tune.ui.theme.Accents
import com.music.tune.ui.theme.Metro
import com.music.tune.ui.theme.MetroType

/** The settings pages, in pivot order. */
val SETTINGS_PAGES = listOf("playback", "collection", "network", "appearance", "about")

/**
 * Settings as a pivot: one page per area (the biggest titles), sections
 * within a page (smaller titles), then the settings themselves.
 */
@Composable
fun SettingsScreen(vm: MainViewModel, actions: Actions) {
    val initial = remember { vm.takeSettingsPage()?.let { SETTINGS_PAGES.indexOf(it) }?.takeIf { it >= 0 } ?: 0 }
    Box(Modifier.statusBarsPadding()) {
        Pivot("settings", SETTINGS_PAGES, initial) { page ->
            SettingsPage(SETTINGS_PAGES[page]) {
                when (SETTINGS_PAGES[page]) {
                    "playback" -> playbackSettings(vm)
                    "collection" -> collectionSettings(vm, actions)
                    "network" -> networkSettings(vm)
                    "appearance" -> appearanceSettings(vm)
                    else -> aboutSettings(vm, actions)
                }
            }
        }
    }
}

/** One settings page: a list tagged "settings:<page>" (tests scroll it). */
@Composable
private fun SettingsPage(name: String, content: LazyListScope.() -> Unit) {
    LazyColumn(
        Modifier.fillMaxSize().imePadding().testTag("settings:$name"),
        contentPadding = PaddingValues(bottom = 64.dp),
        content = content,
    )
}

private fun LazyListScope.playbackSettings(vm: MainViewModel) {
    item { Section("volume") }
    item {
        val c = Metro.colors
        val lib by vm.library.collectAsState()
        val normalize by vm.normalize.collectAsState()
        val m by vm.measure.collectAsState()
        var measured by remember { mutableStateOf(0) }
        LaunchedEffect(lib.version, m.running) { measured = vm.measuredCount() }
        Column(Modifier.padding(horizontal = 24.dp)) {
            Toggle("normalize volume", normalize) { vm.setNormalize(it) }
            Note(
                "Plays every song at a similar loudness, without changing your files: loud songs are turned " +
                    "down to ${Normalization.TARGET_LUFS.toInt()} LUFS, the ReplayGain level. Songs are measured " +
                    "as they come up, or all at once here.",
            )
            VSpace(8)
            MText(
                if (m.running) "${m.already + m.done} of ${m.already + m.total} songs measured..."
                else "$measured of ${lib.songs.size} songs measured",
                MetroType.normal, modifier = Modifier.testTag("measure:status"),
            )
            if (m.failed > 0) MText("${m.failed} songs couldn't be measured", MetroType.small, color = c.subtle)
            if (m.running) {
                VSpace(8)
                ProgressDots()
            }
            VSpace(10)
            if (m.running) MetroButton("stop measuring") { vm.stopMeasuring() }
            else MetroButton("measure all songs", enabled = measured < lib.songs.size) { vm.measureCollection() }
        }
    }
}

private fun LazyListScope.collectionSettings(vm: MainViewModel, actions: Actions) {
    item { Section("music folder") }
    item { LibraryFolderSettings(vm) }

    item { Section("organizing files") }
    item {
        val lib by vm.library.collectAsState()
        val organize by vm.autoOrganize.collectAsState()
        Column(Modifier.padding(horizontal = 24.dp)) {
            Toggle("move files after editing info", organize) { vm.setAutoOrganize(it) }
            Note(
                vm.organizeRoot?.let { "$it/<album artist>/<album>/<number>-<title>.<ext>" }
                    ?: "Unavailable for this music folder: files stay where they are.",
            )
            VSpace(12)
            MetroButton("organize whole collection", enabled = lib.songs.isNotEmpty() && vm.organizeRoot != null) {
                actions.organize(lib.songs)
            }
        }
    }

    item { Section("identifying songs") }
    item {
        val lib by vm.library.collectAsState()
        val key by vm.acoustIdKey.collectAsState()
        Column(Modifier.padding(horizontal = 24.dp)) {
            if (key.isEmpty()) {
                Note("Scanning and consolidating identify songs with AcoustID: add a free API key on the network page first.")
                VSpace(10)
                MetroButton("go to network") { vm.openSettings("network") }
            } else {
                Note(
                    "Scanning fingerprints and looks up every song once and saves the answers, " +
                        "so finding info and consolidating albums don't ask again.",
                )
                VSpace(8)
                var scanned by remember { mutableStateOf(0) }
                val scan by vm.scan.collectAsState()
                LaunchedEffect(lib.version, scan.running, scan.generation) { scanned = vm.scannedCount() }
                ScanControls(vm, scanned, lib.songs.size)
                VSpace(10)
                MetroButton("consolidate albums") { vm.navigate(Screen.Consolidate) }
            }
        }
    }

    item { Section("library") }
    item {
        val lib by vm.library.collectAsState()
        Column(Modifier.padding(horizontal = 24.dp)) {
            MText("${lib.artists.size} artists • ${lib.albums.size} albums • ${lib.songs.size} songs", MetroType.normal, color = Metro.colors.subtle)
            VSpace(10)
            MetroButton("refresh collection") { vm.reload(); vm.toast("refreshing") }
        }
    }
}

private fun LazyListScope.networkSettings(vm: MainViewModel) {
    item { Section("kill switch") }
    item {
        val offline by vm.offline.collectAsState()
        Column(Modifier.padding(horizontal = 24.dp)) {
            Toggle("network kill switch", offline) { vm.setOffline(it) }
            Note(
                if (offline) "On: Tune makes no web requests at all. Finding info online, scanning and remote covers are off."
                else "Tune only goes online when you use finding info online. Turn this on to make sure it never does.",
            )
        }
    }

    item { Section("acoustid") }
    item {
        val ctx = LocalContext.current
        val key by vm.acoustIdKey.collectAsState()
        var keyText by remember(key) { mutableStateOf(key) }
        Column(Modifier.padding(horizontal = 24.dp)) {
            Note(
                "Songs are identified by their sound with AcoustID and MusicBrainz, like Picard does. " +
                    "You need a free AcoustID application API key.",
            )
            VSpace(10)
            MetroTextBox("acoustid api key", keyText, { keyText = it })
            VSpace(10)
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                MetroButton("save key", enabled = keyText.trim() != key) {
                    vm.setAcoustIdKey(keyText)
                    vm.toast("key saved")
                }
                MetroButton("get a key") {
                    ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://acoustid.org/new-application")))
                }
            }
        }
    }
}

private fun LazyListScope.appearanceSettings(vm: MainViewModel) {
    item { Section("background") }
    item {
        val mode by vm.themeMode.collectAsState()
        Row(Modifier.padding(horizontal = 24.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Choice("system", mode == ThemeMode.SYSTEM) { vm.setThemeMode(ThemeMode.SYSTEM) }
            Choice("dark", mode == ThemeMode.DARK) { vm.setThemeMode(ThemeMode.DARK) }
            Choice("light", mode == ThemeMode.LIGHT) { vm.setThemeMode(ThemeMode.LIGHT) }
        }
    }

    item { Section("accent colour") }
    items(Accents.chunked(4)) { row ->
        val c = Metro.colors
        val accent by vm.accent.collectAsState()
        Row(Modifier.padding(horizontal = 24.dp, vertical = 5.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            row.forEach { (name, color) ->
                Column(Modifier.weight(1f).metroClick { vm.setAccent(color) }) {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .aspectRatio(1f)
                            .background(color)
                            .border(if (color == accent) 3.dp else 0.dp, c.foreground),
                    )
                    MText(name, MetroType.small, color = if (color == accent) c.foreground else c.subtle)
                }
            }
            repeat(4 - row.size) { Box(Modifier.weight(1f)) }
        }
    }
    item {
        val accentTitles by vm.accentTitles.collectAsState()
        Column(Modifier.padding(horizontal = 24.dp, vertical = 8.dp)) {
            Toggle("accent colour for titles", accentTitles) { vm.setAccentTitles(it) }
        }
    }

    item { Section("albums") }
    item {
        val albumGrid by vm.albumGrid.collectAsState()
        Column(Modifier.padding(horizontal = 24.dp)) {
            Toggle("show albums as a grid", albumGrid) { vm.setAlbumGrid(it) }
        }
    }
}

private fun LazyListScope.aboutSettings(vm: MainViewModel, actions: Actions) {
    item { Section("backup") }
    item { BackupSettings(vm, actions) }

    item { Section("tune") }
    item {
        val ctx = LocalContext.current
        val version = remember {
            runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName }.getOrNull().orEmpty()
        }
        Column(Modifier.padding(horizontal = 24.dp)) {
            MText("Tune $version", MetroType.normal)
            VSpace(8)
            Note(
                "Finding info online uses three free web services: AcoustID identifies recordings from audio " +
                    "fingerprints, MusicBrainz provides song and album information, and the Cover Art Archive " +
                    "provides album covers. Tune contacts them only when you use those features; otherwise it " +
                    "makes no web requests. No data is collected.",
            )
            VSpace(10)
            MetroButton("open-source licences") { vm.navigate(Screen.Licenses) }
            VSpace(10)
            MetroButton("privacy policy") {
                ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/Gabrielcarvfer/tune/blob/master/PRIVACY.md")))
            }
        }
    }
}

/** Export and import of every setting as one JSON file, e.g. to move to another install. */
@Composable
private fun BackupSettings(vm: MainViewModel, actions: Actions) {
    val scope = rememberCoroutineScope()
    fun run(done: String, work: suspend () -> Unit) {
        scope.launch {
            runCatching { work() }
                .onSuccess { vm.toast(done) }
                .onFailure { vm.toast("couldn't: ${it.message ?: it}") }
        }
    }
    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) run("settings exported") { vm.exportSettings(uri) }
    }
    val import = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) actions.overlays.confirm(
            "import settings?",
            "Your settings, playlists, history, measured loudness and saved scans are replaced by the file's.",
            "import",
        ) { run("settings imported") { vm.importSettings(uri) } }
    }
    Column(Modifier.padding(horizontal = 24.dp)) {
        Note(
            "Saves every setting (including your AcoustID key and music folder), playlists, history, measured " +
                "loudness and saved scans to one JSON file, and reads it back, e.g. to move to another install.",
        )
        VSpace(10)
        MetroButton("export settings") { export.launch("tune-settings.json") }
        VSpace(10)
        MetroButton("import settings") { import.launch(arrayOf("application/json", "text/plain", "application/octet-stream")) }
    }
}

/** A setting's explanation: small and grey, below it. */
@Composable
private fun Note(text: String) {
    MText(text, MetroType.small, color = Metro.colors.subtle, maxLines = 8)
}

@Composable
private fun Section(t: String) {
    MText(t, MetroType.large, modifier = Modifier.padding(start = 24.dp, top = 24.dp, bottom = 10.dp))
}

@Composable
private fun Choice(label: String, selected: Boolean, onClick: () -> Unit) {
    val c = Metro.colors
    Box(
        Modifier
            .background(if (selected) c.accent else androidx.compose.ui.graphics.Color.Transparent)
            .border(2.dp, if (selected) c.accent else c.foreground)
            .metroClick(onClick = onClick)
            .padding(horizontal = 22.dp, vertical = 12.dp),
    ) { MText(label, MetroType.normal, color = if (selected) androidx.compose.ui.graphics.Color.White else c.foreground) }
}

/** WP toggle switch: label above, "On/Off" text and a rectangular switch. */
@Composable
fun Toggle(label: String, on: Boolean, onChange: (Boolean) -> Unit) {
    val c = Metro.colors
    Column(Modifier.fillMaxWidth().metroClick { onChange(!on) }.padding(vertical = 6.dp)) {
        MText(label, MetroType.small, color = c.subtle)
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            MText(if (on) "On" else "Off", MetroType.large, modifier = Modifier.weight(1f))
            Box(Modifier.size(width = 76.dp, height = 30.dp).border(2.dp, c.foreground).padding(4.dp)) {
                Box(
                    Modifier
                        .size(width = 44.dp, height = 18.dp)
                        .align(Alignment.CenterStart)
                        .background(if (on) c.accent else androidx.compose.ui.graphics.Color.Transparent),
                )
                Box(
                    Modifier
                        .size(width = 14.dp, height = 30.dp)
                        .align(if (on) Alignment.CenterEnd else Alignment.CenterStart)
                        .background(c.foreground),
                )
            }
        }
    }
}

@Composable
fun SearchScreen(vm: MainViewModel, actions: Actions) {
    val lib by vm.library.collectAsState()
    var q by remember { mutableStateOf("") }
    val needle = q.trim().lowercase()
    val artists = if (needle.isEmpty()) emptyList() else lib.artists.filter { needle in it.name.lowercase() }.take(5)
    val albums = if (needle.isEmpty()) emptyList() else lib.albums.filter { needle in it.title.lowercase() || needle in it.artist.lowercase() }.take(10)
    val songs = if (needle.isEmpty()) emptyList() else lib.songs.filter { needle in it.title.lowercase() || needle in it.artist.lowercase() }.take(50)

    Column(Modifier.fillMaxSize().statusBarsPadding().imePadding()) {
        PageHeader("music", "search")
        val focus = LocalFocusManager.current
        MetroTextBox(
            null, q, { q = it }, Modifier.padding(horizontal = 24.dp),
            imeAction = ImeAction.Search, onSubmit = { focus.clearFocus() }, tag = "field:search",
        )
        VSpace(8)
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 48.dp)) {
            if (artists.isNotEmpty()) {
                item { Section("artists") }
                items(artists, key = { "a-" + it.name }) { a ->
                    ArtistRow(a, onLongClick = { actions.artistMenu(a) }) { vm.navigate(Screen.ArtistPage(a.name)) }
                }
            }
            if (albums.isNotEmpty()) {
                item { Section("albums") }
                items(albums, key = { "al-" + it.id }) { a ->
                    AlbumRow(a, onLongClick = { actions.albumMenu(a) }) { vm.navigate(Screen.AlbumPage(a.id)) }
                }
            }
            if (songs.isNotEmpty()) {
                item { Section("songs") }
                items(songs, key = { "s-" + it.id }) { s ->
                    SongRow(s, onLongClick = { actions.songMenu(s) }) { actions.play(listOf(s)) }
                }
            }
            if (needle.isNotEmpty() && artists.isEmpty() && albums.isEmpty() && songs.isEmpty()) {
                item { EmptyNote("No results.") }
            }
        }
    }
}


/** Which folder the library is read from (and organized into). */
@Composable
private fun LibraryFolderSettings(vm: MainViewModel) {
    val c = Metro.colors
    val folder by vm.libraryFolder.collectAsState()
    val allFiles by vm.allFilesAccess.collectAsState()
    val ctx = LocalContext.current
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) vm.setLibraryFolder(uri)
    }
    Column(Modifier.padding(horizontal = 24.dp)) {
        MText(folder?.label ?: "whole phone", MetroType.large, maxLines = 2, modifier = Modifier.testTag("library:folder"))
        MText(
            when {
                folder == null -> "Songs anywhere on the phone are in your collection. Organized files are moved into Music."
                vm.organizeRoot != null ->
                    "Only songs in this folder and its subfolders are in your collection. Organized files stay inside it."
                else -> "Only songs in this folder and its subfolders are in your collection. " +
                    "Files here are never moved: to organize inside this folder, allow all files access."
            },
            MetroType.small, color = c.subtle, maxLines = 4,
        )
        VSpace(12)
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            MetroButton("choose folder") {
                val start = folder?.takeIf { it.isPrimary }?.relativePath ?: Organizer.MUSIC_DIR
                picker.launch(DocumentsContract.buildDocumentUri("com.android.externalstorage.documents", "primary:$start"))
            }
            MetroButton("whole phone", enabled = folder != null) { vm.setLibraryFolder(null as LibraryFolder?) }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && folder != null && !folder!!.canOrganizeInto) {
            VSpace(12)
            if (allFiles) {
                MText("All files access: allowed", MetroType.small, color = c.subtle)
            } else {
                MetroButton("allow all files access") {
                    ctx.startActivity(
                        Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:${ctx.packageName}")),
                    )
                }
            }
        }
    }
}
