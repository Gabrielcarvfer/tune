package com.music.tune

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.music.tune.ui.components.AlbumArt
import com.music.tune.ui.components.LocalArtVersion
import com.music.tune.ui.components.LocalOverlays
import com.music.tune.ui.components.MText
import com.music.tune.ui.components.MTextEllipsis
import com.music.tune.ui.components.MetroButton
import com.music.tune.ui.components.OverlayHost
import com.music.tune.ui.components.Overlays
import com.music.tune.ui.components.PageHeader
import com.music.tune.ui.components.ProgressDots
import com.music.tune.ui.components.RoundButton
import com.music.tune.ui.components.Toast
import com.music.tune.ui.components.VSpace
import com.music.tune.ui.components.metroClick
import com.music.tune.ui.components.topDivider
import com.music.tune.ui.screens.Actions
import com.music.tune.ui.screens.ConsolidateScreen
import com.music.tune.ui.screens.DuplicatesScreen
import com.music.tune.ui.screens.LicenseTextScreen
import com.music.tune.ui.screens.LicensesScreen
import com.music.tune.ui.screens.EditAsAlbumScreen
import com.music.tune.ui.screens.MergeAlbumsScreen
import com.music.tune.ui.screens.AlbumScreen
import com.music.tune.ui.screens.ArtistScreen
import com.music.tune.ui.screens.CollectionScreen
import com.music.tune.ui.screens.EditAlbumScreen
import com.music.tune.ui.screens.EditSongScreen
import com.music.tune.ui.screens.GenreScreen
import com.music.tune.ui.screens.HubScreen
import com.music.tune.ui.screens.IdentifyScreen
import com.music.tune.ui.screens.NowPlayingScreen
import com.music.tune.ui.screens.PlaylistScreen
import com.music.tune.ui.screens.QueueScreen
import com.music.tune.ui.screens.SearchScreen
import com.music.tune.ui.screens.SettingsScreen
import com.music.tune.ui.theme.Metro
import com.music.tune.ui.theme.MetroType
import com.music.tune.ui.theme.TuneTheme

class MainActivity : ComponentActivity() {
    internal val vm: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handleIntent(intent)
        setContent {
            val accent by vm.accent.collectAsState()
            val mode by vm.themeMode.collectAsState()
            val light = when (mode) {
                ThemeMode.SYSTEM -> !isSystemInDarkTheme()
                ThemeMode.DARK -> false
                ThemeMode.LIGHT -> true
            }
            LaunchedEffect(light) {
                val style = if (light) SystemBarStyle.light(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT)
                else SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
                enableEdgeToEdge(style, style)
            }
            val accentTitles by vm.accentTitles.collectAsState()
            TuneTheme(accent, light, accentTitles) { App(vm) }
        }
    }

    override fun onResume() {
        super.onResume()
        vm.refreshPermissions()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        if (intent?.getBooleanExtra(EXTRA_NOW_PLAYING, false) == true) vm.navigate(Screen.NowPlaying)
    }

    companion object {
        const val EXTRA_NOW_PLAYING = "now_playing"
    }
}

private fun requiredPermissions(): Array<String> = buildList {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        add(Manifest.permission.READ_MEDIA_AUDIO)
        add(Manifest.permission.POST_NOTIFICATIONS)
    } else {
        add(Manifest.permission.READ_EXTERNAL_STORAGE)
    }
    if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
}.toTypedArray()

private val readPermission =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) Manifest.permission.READ_MEDIA_AUDIO
    else Manifest.permission.READ_EXTERNAL_STORAGE

@Composable
private fun App(vm: MainViewModel) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val overlays = remember { Overlays() }
    val actions = remember(vm, overlays) { Actions(vm, overlays) }
    val lib by vm.library.collectAsState()
    val busy by vm.busy.collectAsState()
    val toast by vm.toast.collectAsState()
    val c = Metro.colors

    var granted by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(ctx, readPermission) == PackageManager.PERMISSION_GRANTED)
    }
    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { res ->
        granted = res[readPermission] == true ||
            ContextCompat.checkSelfPermission(ctx, readPermission) == PackageManager.PERMISSION_GRANTED
    }
    LaunchedEffect(Unit) { if (!granted) permLauncher.launch(requiredPermissions()) }
    LaunchedEffect(granted) { if (granted) vm.onPermissionGranted() }

    // System consent dialogs for editing/moving/deleting files.
    val consent = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { r ->
        vm.onConsentResult(r.resultCode == android.app.Activity.RESULT_OK)
    }
    LaunchedEffect(Unit) {
        vm.consentRequests.collect { consent.launch(IntentSenderRequest.Builder(it).build()) }
    }

    BackHandler(enabled = vm.backStack.size > 1 && overlays.current == null) { vm.back() }

    CompositionLocalProvider(LocalOverlays provides overlays, LocalArtVersion provides lib.version) {
        Box(
            Modifier
                .fillMaxSize()
                .background(c.background)
                .onPreviewKeyEvent { e ->
                    // Escape on a hardware keyboard closes menus and goes back.
                    if (e.type != KeyEventType.KeyUp || e.key != Key.Escape) return@onPreviewKeyEvent false
                    when {
                        overlays.current != null -> overlays.dismiss()
                        else -> vm.back()
                    }
                    true
                },
        ) {
            if (!granted) {
                Column(Modifier.statusBarsPadding()) {
                    PageHeader("music", "welcome")
                    MText(
                        "To play your music, allow access to the audio files on this phone.",
                        MetroType.medium, maxLines = 4, modifier = Modifier.padding(horizontal = 24.dp),
                    )
                    VSpace(24)
                    MetroButton("allow", Modifier.padding(horizontal = 24.dp)) { permLauncher.launch(requiredPermissions()) }
                }
            } else {
                val screen = vm.backStack.last()
                val entry = vm.backStackIds.last() to screen
                // Each back stack entry keeps its scroll positions and pivot page.
                val saved = rememberSaveableStateHolder()
                val live = vm.backStackIds.toList()
                val known = remember { mutableSetOf<Long>() }
                LaunchedEffect(live) {
                    (known - live.toSet()).forEach { saved.removeState(it) }
                    known.retainAll(live.toSet())
                    known.addAll(live)
                }
                Column(Modifier.fillMaxSize()) {
                    AnimatedContent(
                        targetState = entry,
                        transitionSpec = {
                            // Approximates the WP "turnstile": new page swings in from the left edge.
                            (fadeIn(tween(220)) + slideInVertically(tween(260)) { it / 14 }) togetherWith fadeOut(tween(120))
                        },
                        modifier = Modifier.weight(1f),
                        label = "nav",
                    ) { (id, s) ->
                        saved.SaveableStateProvider(id) {
                            Box(Modifier.fillMaxSize().then(if (s is Screen.Hub || s is Screen.Collection) Modifier.statusBarsPadding() else Modifier)) {
                                ScreenContent(s, vm, actions)
                            }
                        }
                    }
                    if (screen is Screen.Hub || screen is Screen.Collection) MiniPlayer(vm)
                }
            }

            if (busy) ProgressDots(Modifier.statusBarsPadding().padding(top = 2.dp))
            AnimatedVisibility(
                toast != null,
                enter = slideInVertically { -it },
                exit = slideOutVertically { -it },
            ) {
                Toast(toast.orEmpty(), Modifier.statusBarsPadding())
            }
            OverlayHost(overlays)
        }
    }
}

@Composable
private fun ScreenContent(s: Screen, vm: MainViewModel, actions: Actions) {
    when (s) {
        Screen.Hub -> HubScreen(vm, actions)
        is Screen.Collection -> CollectionScreen(vm, actions, s.pivot)
        is Screen.ArtistPage -> Box(Modifier.statusBarsPadding()) { ArtistScreen(vm, actions, s.name) }
        is Screen.AlbumPage -> Box(Modifier.statusBarsPadding()) { AlbumScreen(vm, actions, s.id) }
        is Screen.GenrePage -> Box(Modifier.statusBarsPadding()) { GenreScreen(vm, actions, s.name) }
        is Screen.PlaylistPage -> Box(Modifier.statusBarsPadding()) { PlaylistScreen(vm, actions, s.id) }
        Screen.NowPlaying -> NowPlayingScreen(vm, actions)
        Screen.Queue -> QueueScreen(vm, actions)
        is Screen.EditSong -> EditSongScreen(vm, actions, s.id)
        is Screen.EditAlbum -> EditAlbumScreen(vm, actions, s.id)
        is Screen.Identify -> IdentifyScreen(vm, s.songIds, s.albumId)
        is Screen.MergeAlbums -> MergeAlbumsScreen(vm, s.firstAlbumId)
        is Screen.EditAsAlbum -> EditAsAlbumScreen(vm, actions, s.songIds)
        Screen.Consolidate -> ConsolidateScreen(vm)
        Screen.Duplicates -> DuplicatesScreen(vm, actions)
        Screen.Licenses -> LicensesScreen(vm)
        is Screen.LicenseText -> LicenseTextScreen(s.name)
        Screen.Search -> SearchScreen(vm, actions)
        Screen.Settings -> SettingsScreen(vm, actions)
    }
}

/** Slim now-playing strip for the hub and collection pages. */
@Composable
private fun MiniPlayer(vm: MainViewModel) {
    val st by vm.player.state.collectAsState()
    val lib by vm.library.collectAsState()
    val song = st.currentId?.let { lib.song(it) } ?: return
    val c = Metro.colors
    Row(
        Modifier
            .fillMaxWidth()
            .background(c.chrome)
            .topDivider(c.divider)
            .navigationBarsPadding()
            .testTag("miniplayer")
            .metroClick { vm.navigate(Screen.NowPlaying) }
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        AlbumArt(song.albumId, song.uri, Modifier.size(48.dp))
        Column(Modifier.weight(1f)) {
            MTextEllipsis(song.title, MetroType.normal)
            MTextEllipsis(song.artist, MetroType.small, color = c.subtle)
        }
        RoundButton(if (st.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow, "play/pause", size = 40) {
            vm.player.togglePlay()
        }
    }
}
