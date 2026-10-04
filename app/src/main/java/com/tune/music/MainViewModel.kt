package com.tune.music

import android.app.Application
import android.app.RecoverableSecurityException
import android.content.Context
import android.content.IntentSender
import android.database.ContentObserver
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.DocumentsContract
import android.provider.MediaStore
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.tune.music.data.AcoustId
import com.tune.music.data.Album
import com.tune.music.data.CoverImage
import com.tune.music.data.Library
import com.tune.music.data.LibraryFolder
import com.tune.music.data.Organizer
import com.tune.music.data.ReleaseCandidate
import com.tune.music.data.TrackMatch
import com.tune.music.data.MediaRepository
import com.tune.music.data.PlaylistStore
import com.tune.music.data.Song
import com.tune.music.data.TagEdit
import com.tune.music.data.TagEditor
import com.tune.music.data.TagValues
import com.tune.music.playback.PlayerConnection
import com.tune.music.ui.theme.Accents
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import java.io.File

/** App navigation destinations. */
enum class ThemeMode { SYSTEM, DARK, LIGHT }

sealed interface Screen {
    data object Hub : Screen
    data class Collection(val pivot: Int) : Screen
    data class ArtistPage(val name: String) : Screen
    data class AlbumPage(val id: Long) : Screen
    data class GenrePage(val name: String) : Screen
    data class PlaylistPage(val id: String) : Screen
    data object NowPlaying : Screen
    data object Queue : Screen
    data class EditSong(val id: Long) : Screen
    data class EditAlbum(val id: Long) : Screen
    data object Search : Screen
    data object Settings : Screen
    /** AcoustID/MusicBrainz matching for one song, or a whole album. */
    data class Identify(val songIds: List<Long>, val albumId: Long?) : Screen
}

private const val TAG = "Tune"

class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val repo = MediaRepository(app)
    private val tags = TagEditor(app)
    private val organizer = Organizer(app, tags)
    val acoustId = AcoustId(app)
    private val prefs = app.getSharedPreferences("tune", Context.MODE_PRIVATE)

    val playlists = PlaylistStore(app)
    val player = PlayerConnection(app, viewModelScope)

    private val _library = MutableStateFlow(Library())
    val library: StateFlow<Library> = _library.asStateFlow()

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    private val _toast = MutableStateFlow<String?>(null)
    val toast: StateFlow<String?> = _toast.asStateFlow()

    private val _accent = MutableStateFlow(Color(prefs.getInt("accent", Accents.first().second.toArgb())))
    val accent: StateFlow<Color> = _accent.asStateFlow()

    private val _themeMode = MutableStateFlow(readThemeMode())
    /** Follow the system dark mode, or force dark / light. */
    val themeMode: StateFlow<ThemeMode> = _themeMode.asStateFlow()

    private val _apiKey = MutableStateFlow(prefs.getString("acoustid", "").orEmpty())
    val acoustIdKey: StateFlow<String> = _apiKey.asStateFlow()

    private val _autoOrganize = MutableStateFlow(prefs.getBoolean("organize", true))
    /** Move files into Music/<album artist>/<album>/ after their info is saved. */
    val autoOrganize: StateFlow<Boolean> = _autoOrganize.asStateFlow()

    private val _accentTitles = MutableStateFlow(prefs.getBoolean("accentTitles", false))
    /** Draw page titles and headers in the accent colour. */
    val accentTitles: StateFlow<Boolean> = _accentTitles.asStateFlow()

    private val _libraryFolder = MutableStateFlow(
        prefs.getString("libraryFolder", null)?.let { LibraryFolder.fromPath(it, Organizer.primaryRoot) },
    )
    /** The folder the library is read from; null means the whole phone. */
    val libraryFolder: StateFlow<LibraryFolder?> = _libraryFolder.asStateFlow()

    private val _history = MutableStateFlow(readHistory())
    /** Recently played album ids, newest first. */
    val history: StateFlow<List<Long>> = _history.asStateFlow()

    val backStack = mutableStateListOf<Screen>(Screen.Hub)

    /** System consent dialogs (scoped-storage write/delete) for the activity to launch. */
    private val _consent = Channel<IntentSender>(Channel.BUFFERED)
    val consentRequests = _consent.receiveAsFlow()
    private var afterConsent: (suspend () -> Unit)? = null

    private var reloadJob: Job? = null
    private var hasPermission = false
    private val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean) = scheduleReload()
    }

    init {
        player.connect()
        viewModelScope.launch {
            var last: Long? = null
            player.state.collect { st ->
                if (st.currentId != null && st.currentId != last) {
                    last = st.currentId
                    _library.value.song(st.currentId)?.let { addHistory(it.albumId) }
                }
            }
        }
    }

    fun onPermissionGranted() {
        if (hasPermission) return
        hasPermission = true
        getApplication<Application>().contentResolver.registerContentObserver(
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, true, observer,
        )
        reload()
    }

    fun reload() {
        reloadJob?.cancel()
        reloadJob = viewModelScope.launch { reloadNow() }
    }

    private suspend fun reloadNow() {
        val lib = runCatching { repo.load(_libraryFolder.value?.absolutePath) }.getOrElse { Library(loaded = true) }
        _library.value = lib
        playlists.prune(lib.songs.map { it.id }.toSet())
        player.updateSongs(lib.songs.associateBy { it.id })
    }

    private fun scheduleReload() {
        reloadJob?.cancel()
        reloadJob = viewModelScope.launch {
            delay(800) // MediaStore fires bursts of notifications
            _library.value = runCatching { repo.load(_libraryFolder.value?.absolutePath) }.getOrElse { _library.value }
            player.updateSongs(_library.value.songs.associateBy { it.id })
        }
    }

    // --- navigation -------------------------------------------------------

    fun navigate(s: Screen) {
        if (backStack.lastOrNull() != s) backStack.add(s)
    }

    fun back(): Boolean {
        if (backStack.size <= 1) return false
        backStack.removeAt(backStack.lastIndex)
        return true
    }

    // --- settings ---------------------------------------------------------

    fun setAccent(c: Color) {
        _accent.value = c
        prefs.edit().putInt("accent", c.toArgb()).apply()
    }

    fun setThemeMode(mode: ThemeMode) {
        _themeMode.value = mode
        prefs.edit().putString("theme", mode.name).remove("light").apply()
    }

    private fun readThemeMode(): ThemeMode {
        prefs.getString("theme", null)?.let { name -> ThemeMode.entries.firstOrNull { it.name == name }?.let { return it } }
        // Older versions stored a plain light/dark switch.
        if (prefs.contains("light")) return if (prefs.getBoolean("light", false)) ThemeMode.LIGHT else ThemeMode.DARK
        return ThemeMode.SYSTEM
    }

    fun setAccentTitles(on: Boolean) {
        _accentTitles.value = on
        prefs.edit().putBoolean("accentTitles", on).apply()
    }

    private val _allFilesAccess = MutableStateFlow(hasAllFilesAccess())
    /** Whether the user granted "All files access" (needed to organize outside Music/). */
    val allFilesAccess: StateFlow<Boolean> = _allFilesAccess.asStateFlow()

    private fun hasAllFilesAccess() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && Environment.isExternalStorageManager()

    /** Re-checks permissions the user may have changed in system settings. */
    fun refreshPermissions() {
        _allFilesAccess.value = hasAllFilesAccess()
    }

    /**
     * Where organized files go, relative to the phone's storage; null when the
     * music folder can't receive them (files are then never moved).
     */
    val organizeRoot: String?
        get() = Organizer.rootFor(_libraryFolder.value, _allFilesAccess.value)

    /** Sets the library folder from the system folder picker's result. */
    fun setLibraryFolder(treeUri: Uri) {
        val folder = runCatching {
            if (treeUri.authority != "com.android.externalstorage.documents") null
            else LibraryFolder.fromTreeDocumentId(DocumentsContract.getTreeDocumentId(treeUri), Organizer.primaryRoot)
        }.getOrNull()
        if (folder == null) return toast("pick a folder on the phone or SD card")
        setLibraryFolder(folder)
    }

    /** Sets (or with null, clears) the library folder and reloads the library. */
    fun setLibraryFolder(folder: LibraryFolder?) {
        _libraryFolder.value = folder
        prefs.edit().apply { if (folder == null) remove("libraryFolder") else putString("libraryFolder", folder.absolutePath) }.apply()
        reload()
    }

    fun setAcoustIdKey(key: String) {
        _apiKey.value = key.trim()
        prefs.edit().putString("acoustid", key.trim()).apply()
    }

    fun setAutoOrganize(on: Boolean) {
        _autoOrganize.value = on
        prefs.edit().putBoolean("organize", on).apply()
    }

    private fun readHistory(): List<Long> =
        prefs.getString("history", "").orEmpty().split(',').mapNotNull { it.toLongOrNull() }

    private fun addHistory(albumId: Long) {
        val next = (listOf(albumId) + _history.value.filter { it != albumId }).take(12)
        _history.value = next
        prefs.edit().putString("history", next.joinToString(",")).apply()
    }

    fun toast(msg: String) {
        _toast.value = msg
        viewModelScope.launch {
            delay(2500)
            if (_toast.value == msg) _toast.value = null
        }
    }

    // --- metadata ---------------------------------------------------------

    suspend fun readTags(song: Song): TagValues = tags.read(song)

    fun readImage(uri: Uri) = tags.readImage(uri)

    /**
     * Writes tag edits to the files. [edits] maps each song to what changes on it.
     * Asks the user for write consent first where Android requires it.
     */
    fun saveTags(edits: Map<Song, TagEdit>, organize: Boolean = _autoOrganize.value, onDone: () -> Unit = {}) {
        if (edits.isEmpty()) return onDone()
        withWriteAccess(edits.keys) { writeAll(edits.toList(), organize, onDone) }
    }

    /** Runs [work] once the user has granted write access to [songs] (Android 11+ asks once for all). */
    private fun withWriteAccess(songs: Collection<Song>, work: suspend () -> Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && !_allFilesAccess.value) {
            val req = MediaStore.createWriteRequest(
                getApplication<Application>().contentResolver, songs.map { it.uri },
            )
            afterConsent = work
            _consent.trySend(req.intentSender)
        } else {
            viewModelScope.launch { work() }
        }
    }

    private suspend fun writeAll(items: List<Pair<Song, TagEdit>>, organize: Boolean, onDone: () -> Unit) {
        _busy.value = true
        var failed = 0
        try {
            for ((i, item) in items.withIndex()) {
                try {
                    tags.write(item.first, item.second)
                } catch (e: SecurityException) {
                    // Android 10: one consent dialog per file; resume from here once granted.
                    if (Build.VERSION.SDK_INT == Build.VERSION_CODES.Q && e is RecoverableSecurityException) {
                        afterConsent = { writeAll(items.drop(i), organize, onDone) }
                        _consent.trySend(e.userAction.actionIntent.intentSender)
                        return
                    }
                    android.util.Log.w(TAG, "can't write ${item.first.path}", e)
                    failed++
                } catch (e: Exception) {
                    android.util.Log.w(TAG, "can't write ${item.first.path}", e)
                    failed++
                }
            }
        } finally {
            _busy.value = false
        }
        reloadNow()
        var moveFailed = 0
        val root = organizeRoot
        if (organize && root != null) {
            _busy.value = true
            val ids = items.map { it.first.id }.toSet()
            val fresh = _library.value.songs.filter { it.id in ids }
            moveFailed = organizer.organize(fresh, root, _allFilesAccess.value) { _library.value.album(it.albumId) }
            _busy.value = false
            reloadNow()
        }
        toast(
            when {
                failed > 0 -> "couldn't save $failed of ${items.size} songs"
                moveFailed > 0 -> "info saved, couldn't move $moveFailed files"
                else -> "info saved"
            },
        )
        onDone()
    }

    /** Moves songs into Music/<album artist>/<album>/<NN>-<title>.<ext>. */
    /** The songs that aren't where the organizer would put them yet. */
    fun songsToOrganize(songs: List<Song>): List<Song> {
        val root = organizeRoot ?: return emptyList()
        val lib = _library.value
        return songs.filter { s ->
            val multi = (lib.album(s.albumId)?.songs?.maxOfOrNull { it.disc } ?: 1) > 1
            !Organizer.isOrganized(s, Organizer.target(s, multi, root))
        }
    }

    fun organize(songs: List<Song>) {
        val root = organizeRoot ?: return toast("organizing isn't available for this music folder")
        val todo = songsToOrganize(songs)
        if (todo.isEmpty()) return toast("files are already organized")
        withWriteAccess(todo) {
            _busy.value = true
            val failed = try {
                organizer.organize(todo, root, _allFilesAccess.value) { _library.value.album(it.albumId) }
            } finally {
                _busy.value = false
            }
            reloadNow()
            toast(if (failed == 0) "moved ${todo.size} files" else "couldn't move $failed of ${todo.size} files")
        }
    }

    fun organizeTarget(song: Song): String? {
        val root = organizeRoot ?: return null
        val multi = (_library.value.album(song.albumId)?.songs?.maxOfOrNull { it.disc } ?: 1) > 1
        val t = Organizer.target(song, multi, root)
        return t.relativePath + t.name
    }

    // --- identification (AcoustID + MusicBrainz) --------------------------

    suspend fun identifySongs(songs: List<Song>, progress: (Int, Int) -> Unit): List<ReleaseCandidate> {
        val key = _apiKey.value
        require(key.isNotEmpty()) { "set your AcoustID API key in settings" }
        return acoustId.identifyAlbum(songs, key, progress)
    }

    suspend fun covers(c: ReleaseCandidate): List<CoverImage> = acoustId.covers(c.releaseId, c.releaseGroupId)

    /**
     * Writes a chosen release onto the songs: per-track title/artist/number from
     * the match, album-wide fields on every song, and the chosen cover (if any).
     */
    fun applyRelease(
        songs: List<Song>,
        release: ReleaseCandidate,
        cover: CoverImage?,
        onDone: () -> Unit,
    ) {
        viewModelScope.launch {
            val art = cover?.let {
                _busy.value = true
                try {
                    runCatching { TagEditor.prepareCover(acoustId.download(it.full), "image/jpeg") }.getOrNull()
                } finally {
                    _busy.value = false
                }
            }
            if (cover != null && art == null) toast("couldn't download the cover")
            val edits = songs.associateWith { s ->
                val m: TrackMatch? = release.tracks[s.id]
                TagEdit(
                    title = m?.title,
                    artist = m?.artist,
                    album = release.album,
                    albumArtist = release.albumArtist,
                    year = release.year.takeIf { it > 0 }?.toString(),
                    track = m?.track?.toString(),
                    disc = m?.disc?.toString(),
                    artwork = art?.first,
                    artworkMime = art?.second,
                )
            }
            saveTags(edits, onDone = onDone)
        }
    }

    fun album(id: Long): Album? = _library.value.album(id)

    fun deleteSongs(songs: List<Song>, onDone: () -> Unit = {}) {
        if (songs.isEmpty()) return
        val cr = getApplication<Application>().contentResolver
        val finish = {
            player.removeSongs(songs.map { it.id }.toSet())
            viewModelScope.launch(Dispatchers.IO) {
                organizer.removeEmptyDirs(
                    songs.mapNotNull { File(it.path).parentFile },
                    File(Organizer.primaryRoot, organizeRoot ?: Organizer.MUSIC_DIR),
                )
            }
            reload()
            toast(if (songs.size == 1) "song deleted" else "${songs.size} songs deleted")
            onDone()
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            afterConsent = { finish() } // the system performs the delete itself
            _consent.trySend(MediaStore.createDeleteRequest(cr, songs.map { it.uri }).intentSender)
            return
        }
        viewModelScope.launch {
            for ((i, s) in songs.withIndex()) {
                try {
                    @Suppress("DEPRECATION")
                    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) File(s.path).delete()
                    cr.delete(s.uri, null, null)
                } catch (e: RecoverableSecurityException) {
                    afterConsent = { deleteSongs(songs.drop(i), onDone) }
                    _consent.trySend(e.userAction.actionIntent.intentSender)
                    return@launch
                }
            }
            finish()
        }
    }

    fun onConsentResult(granted: Boolean) {
        val next = afterConsent
        afterConsent = null
        if (granted && next != null) viewModelScope.launch { next() }
        else if (!granted) toast("permission denied")
    }

    // --- playlists --------------------------------------------------------

    fun addToPlaylist(playlistId: String, songs: List<Song>) {
        playlists.addSongs(playlistId, songs.map { it.id })
        toast("added to playlist")
    }

    fun newPlaylist(name: String, songs: List<Song>) {
        playlists.create(name.trim().ifEmpty { "new playlist" }, songs.map { it.id })
        toast("playlist created")
    }

    fun playlistSongs(id: String): List<Song> {
        val lib = _library.value
        return playlists.playlists.value.firstOrNull { it.id == id }
            ?.songIds?.mapNotNull { lib.song(it) }.orEmpty()
    }

    override fun onCleared() {
        if (hasPermission) getApplication<Application>().contentResolver.unregisterContentObserver(observer)
        player.release()
    }
}

