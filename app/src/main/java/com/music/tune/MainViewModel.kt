package com.music.tune

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
import com.music.tune.data.AcoustId
import com.music.tune.data.Album
import com.music.tune.data.Chromaprint
import com.music.tune.data.Consolidator
import com.music.tune.data.Duplicates
import com.music.tune.data.SettingsBackup
import com.music.tune.data.CoverImage
import com.music.tune.data.Library
import com.music.tune.data.LibraryFolder
import com.music.tune.data.Organizer
import com.music.tune.data.ReleaseCandidate
import com.music.tune.data.TrackMatch
import com.music.tune.data.LoudnessStore
import com.music.tune.data.MatchCache
import com.music.tune.data.MediaRepository
import com.music.tune.data.MusicBrainz
import com.music.tune.data.Net
import com.music.tune.data.PlaylistStore
import com.music.tune.data.pipeline
import com.music.tune.data.Song
import com.music.tune.data.TagEdit
import com.music.tune.data.TagEditor
import com.music.tune.data.TagValues
import com.music.tune.playback.PlayerConnection
import com.music.tune.playback.VolumeNormalizer
import com.music.tune.ui.theme.DefaultAccent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import org.json.JSONObject
import kotlinx.coroutines.withContext
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
    /** AcoustID/MusicBrainz matching for one song, a whole album, or merged albums. */
    data class Identify(val songIds: List<Long>, val albumId: Long?) : Screen
    /** Choosing albums to merge, starting from one. */
    data class MergeAlbums(val firstAlbumId: Long) : Screen
    /** Editing several albums' songs as one album. */
    data class EditAsAlbum(val songIds: List<Long>) : Screen
    /** Scanning the collection and merging albums split across releases. */
    data object Consolidate : Screen
    /** Songs saved more than once: listen, tick, delete. */
    data object Duplicates : Screen
    /** The open-source licences page, and one component's licence text. */
    data object Licenses : Screen
    data class LicenseText(val name: String) : Screen
}

private const val TAG = "Tune"

class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val repo = MediaRepository(app)
    private val tags = TagEditor(app)
    private val organizer = Organizer(app, tags)
    /** Saved fingerprints and AcoustID answers, so songs are looked up once. */
    val matchCache = MatchCache(java.io.File(app.filesDir, "acoustid"))
    val acoustId = AcoustId(app, matchCache)
    private val prefs = app.getSharedPreferences("tune", Context.MODE_PRIVATE)

    val playlists = PlaylistStore(app)
    val player = PlayerConnection(app, viewModelScope)

    private val _library = MutableStateFlow(Library())
    val library: StateFlow<Library> = _library.asStateFlow()

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    private val _toast = MutableStateFlow<String?>(null)
    val toast: StateFlow<String?> = _toast.asStateFlow()

    private val _accent = MutableStateFlow(Color(prefs.getInt("accent", DefaultAccent.toArgb())))
    val accent: StateFlow<Color> = _accent.asStateFlow()

    private val _themeMode = MutableStateFlow(readThemeMode())
    /** Follow the system dark mode, or force dark / light. */
    val themeMode: StateFlow<ThemeMode> = _themeMode.asStateFlow()

    private val _offline = MutableStateFlow(prefs.getBoolean("offline", false).also { Net.blocked = it })
    /** The network kill switch: while on, Tune makes no web requests at all. */
    val offline: StateFlow<Boolean> = _offline.asStateFlow()

    fun setOffline(on: Boolean) {
        Net.blocked = on
        _offline.value = on
        prefs.edit().putBoolean("offline", on).apply()
        if (on) stopScan()
    }

    private val _apiKey = MutableStateFlow(prefs.getString("acoustid", "").orEmpty())
    val acoustIdKey: StateFlow<String> = _apiKey.asStateFlow()

    // Until the user decides, files are only moved after saving when they chose a music folder.
    private val _autoOrganize = MutableStateFlow(prefs.getBoolean("organize", prefs.contains("libraryFolder")))
    /** Move files into <music folder>/<album artist>/<album>/ after their info is saved. */
    val autoOrganize: StateFlow<Boolean> = _autoOrganize.asStateFlow()

    private val _accentTitles = MutableStateFlow(prefs.getBoolean("accentTitles", true))
    /** Draw page titles and headers in the accent colour. */
    val accentTitles: StateFlow<Boolean> = _accentTitles.asStateFlow()

    private val _libraryFolder = MutableStateFlow(
        prefs.getString("libraryFolder", null)?.let { LibraryFolder.fromPath(it, Organizer.primaryRoot) },
    )
    /** The folder the library is read from; null means the whole phone. */
    val libraryFolder: StateFlow<LibraryFolder?> = _libraryFolder.asStateFlow()

    private val _albumGrid = MutableStateFlow(prefs.getBoolean("albumGrid", true))
    /** Show albums as a grid of covers instead of a detailed list. */
    val albumGrid: StateFlow<Boolean> = _albumGrid.asStateFlow()

    fun setAlbumGrid(on: Boolean) {
        _albumGrid.value = on
        prefs.edit().putBoolean("albumGrid", on).apply()
    }

    private val _history = MutableStateFlow(readHistory())
    /** Recently played album ids, newest first. */
    val history: StateFlow<List<Long>> = _history.asStateFlow()

    val backStack = mutableStateListOf<Screen>(Screen.Hub)

    /**
     * A unique id per back stack entry, alongside [backStack]: each entry keeps
     * its own saved UI state (scroll positions, the pivot page) under it, so
     * going back returns to exactly where you were.
     */
    val backStackIds = mutableStateListOf(0L)
    private var nextEntryId = 1L

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
        if (backStack.lastOrNull() != s) {
            backStack.add(s)
            backStackIds.add(nextEntryId++)
        }
    }

    private var settingsPage: String? = null

    /** Opens settings on a page (as named on its pivot, e.g. "network"). */
    fun openSettings(page: String) {
        settingsPage = page
        // Already in settings: open it afresh on that page.
        if (backStack.lastOrNull() == Screen.Settings) back()
        navigate(Screen.Settings)
    }

    /** The page settings should open on (the first, unless [openSettings] asked for another). */
    fun takeSettingsPage(): String? = settingsPage.also { settingsPage = null }

    fun back(): Boolean {
        if (backStack.size <= 1) return false
        backStack.removeAt(backStack.lastIndex)
        backStackIds.removeAt(backStackIds.lastIndex)
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

    /**
     * Re-checks permissions the user may have changed in system settings (on
     * returning to the app). [granted] stands in for the system's answer in tests.
     */
    fun refreshPermissions(granted: Boolean = hasAllFilesAccess()) {
        _allFilesAccess.value = granted
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
        // The default follows the folder choice until the user sets it.
        if (!prefs.contains("organize")) _autoOrganize.value = folder != null
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

    /**
     * Releases for [songs], best first: the ones holding the most of these songs,
     * then the ones holding the most of the rest of the collection (from saved
     * lookups), so a song goes to the edition its companions are on rather than
     * to whichever release its own lookup happened to rank first.
     */
    suspend fun identifySongs(songs: List<Song>, progress: (Int, Int) -> Unit): List<ReleaseCandidate> {
        val key = _apiKey.value
        require(key.isNotEmpty()) { "set your AcoustID API key in settings" }
        val found = acoustId.identifyAlbum(songs, key, progress)
        val inLibrary = libraryReleaseCounts()
        return found.sortedWith(
            compareByDescending<ReleaseCandidate> { it.tracks.size }
                .thenByDescending { inLibrary[it.releaseId] ?: 0 }
                .thenByDescending { it.score },
        )
    }

    private var releaseCounts: Pair<Long, Map<String, Int>>? = null

    /** How many songs of the collection each release holds, from the saved lookups. */
    private suspend fun libraryReleaseCounts(): Map<String, Int> = withContext(Dispatchers.IO) {
        val version = matchCache.version + 31 * _library.value.version
        releaseCounts?.takeIf { it.first == version }?.second ?: run {
            val counts = HashMap<String, Int>()
            libraryMatches().values.forEach { list -> list.map { it.releaseId }.distinct().forEach { counts[it] = (counts[it] ?: 0) + 1 } }
            releaseCounts = version to counts
            counts
        }
    }

    /** Every saved AcoustID answer for the collection, by song id. */
    private fun libraryMatches(): Map<Long, List<TrackMatch>> =
        _library.value.songs.mapNotNull { s -> matchCache.lookup(s)?.let { s.id to AcoustId.parse(it) } }.toMap()

    // --- scanning the collection --------------------------------------------

    /**
     * A scan's progress: [done] of the [total] songs it set out to scan, after
     * [already] songs that were scanned before (and are skipped).
     */
    data class ScanState(
        val running: Boolean = false,
        val done: Int = 0,
        val total: Int = 0,
        val failed: Int = 0,
        val error: String? = null,
        val already: Int = 0,
        /** Changes when the saved answers are forgotten, so counts are redone. */
        val generation: Int = 0,
    )

    private val _scan = MutableStateFlow(ScanState())
    /** Progress of "scan collection": fingerprinting and looking up every song once. */
    val scan: StateFlow<ScanState> = _scan.asStateFlow()
    private var scanJob: kotlinx.coroutines.Job? = null

    /** Songs in the collection with a saved AcoustID answer. */
    suspend fun scannedCount(): Int = withContext(Dispatchers.IO) { matchCache.countLooked(_library.value.songs) }

    /**
     * Fingerprints and looks up every song that has no saved answer yet (in
     * path order), one at a time within the services' rate limits.
     */
    fun scanCollection() {
        if (scanJob?.isActive == true) return
        val key = _apiKey.value
        if (key.isEmpty()) return toast("set your AcoustID API key first")
        if (_offline.value) return toast("web requests are turned off")
        scanJob = viewModelScope.launch {
            // Only songs without a saved answer; the others are skipped.
            val songs = _library.value.songs
            val todo = withContext(Dispatchers.IO) { songs.sortedBy { it.path }.filter { matchCache.lookup(it) == null } }
            _scan.value = ScanState(running = true, total = todo.size, already = songs.size - todo.size, generation = _scan.value.generation)
            var done = 0
            var failed = 0
            var inARow = 0
            try {
                // Fingerprints are computed several at a time, ahead of the lookups.
                acoustId.lookupSongs(todo, key) { song, result ->
                    done++
                    result.onSuccess { inARow = 0 }.onFailure { e ->
                        android.util.Log.w(TAG, "can't identify ${song.path}", e)
                        failed++
                        // A file that can't be decoded is just skipped; network trouble counts.
                        if (e is java.io.IOException) inARow++
                        // AcoustID refusing (say, a bad key) or no network would fail every song: stop.
                        if (e is com.music.tune.data.OfflineException || e.message?.startsWith("acoustid:") == true || inARow >= 5) {
                            throw ScanStopped(e)
                        }
                    }
                    _scan.value = _scan.value.copy(done = done, failed = failed)
                }
            } catch (e: ScanStopped) {
                _scan.value = _scan.value.copy(running = false, done = done, failed = failed, error = e.cause?.message ?: e.toString())
                return@launch
            }
            _scan.value = _scan.value.copy(running = false)
        }
    }

    private class ScanStopped(cause: Throwable) : Exception(cause)

    fun stopScan() {
        scanJob?.cancel()
        _scan.value = _scan.value.copy(running = false)
    }

    /** Forgets every saved fingerprint and answer (the next scan starts over). */
    fun clearScan() {
        stopScan()
        viewModelScope.launch(Dispatchers.IO) {
            matchCache.clear()
            _scan.value = ScanState(generation = _scan.value.generation + 1)
        }
    }

    // --- volume normalization -------------------------------------------------

    private val loudness = (app as TuneApp).loudness

    private val _normalize = MutableStateFlow(prefs.getBoolean(VolumeNormalizer.PREF, true))
    /** Play every song at a similar loudness (the player reads the same setting). */
    val normalize: StateFlow<Boolean> = _normalize.asStateFlow()

    fun setNormalize(on: Boolean) {
        _normalize.value = on
        prefs.edit().putBoolean(VolumeNormalizer.PREF, on).apply()
    }

    private val _skipTails = MutableStateFlow(prefs.getBoolean(VolumeNormalizer.PREF_SKIP_TAILS, true))
    /** Move on when a song's audible part ends, skipping its quiet ending; the player reads the same setting. */
    val skipTails: StateFlow<Boolean> = _skipTails.asStateFlow()

    fun setSkipTails(on: Boolean) {
        _skipTails.value = on
        prefs.edit().putBoolean(VolumeNormalizer.PREF_SKIP_TAILS, on).apply()
    }

    data class MeasureState(val running: Boolean = false, val done: Int = 0, val total: Int = 0, val already: Int = 0, val failed: Int = 0)

    private val _measure = MutableStateFlow(MeasureState())
    /** Progress of "measure all songs". */
    val measure: StateFlow<MeasureState> = _measure.asStateFlow()
    private var measureJob: kotlinx.coroutines.Job? = null

    /** Songs in the collection whose loudness is measured. */
    suspend fun measuredCount(): Int = withContext(Dispatchers.IO) { loudness.count(_library.value.songs) }

    /** The measured loudness of [song], if any. */
    fun loudnessOf(song: Song): com.music.tune.data.Loudness? = loudness.get(song)

    /**
     * Measures the loudness of every song not measured yet, several at a time.
     * No web requests: it all happens on the phone.
     */
    fun measureCollection() {
        if (measureJob?.isActive == true) return
        measureJob = viewModelScope.launch {
            val songs = _library.value.songs
            val todo = withContext(Dispatchers.IO) { songs.filter { loudness.get(it) == null } }
            _measure.value = MeasureState(running = true, total = todo.size, already = songs.size - todo.size)
            var done = 0
            var failed = 0
            try {
                com.music.tune.data.pipeline(
                    todo, AcoustId.PARALLEL_FINGERPRINTS, Dispatchers.Default,
                    prepare = { LoudnessStore.measure(getApplication(), it.uri) },
                    finish = { song, result ->
                        done++
                        result.onSuccess { withContext(Dispatchers.IO) { loudness.put(song, it) } }
                            .onFailure { failed++; android.util.Log.w(TAG, "can't measure ${song.path}", it) }
                        _measure.value = _measure.value.copy(done = done, failed = failed)
                    },
                )
            } finally {
                // Mark it stopped first: after a stop (a cancellation), returning
                // from withContext below would throw before getting further.
                _measure.value = _measure.value.copy(running = false)
                withContext(kotlinx.coroutines.NonCancellable + Dispatchers.IO) { loudness.flush() }
            }
        }
    }

    fun stopMeasuring() {
        measureJob?.cancel()
    }

    // --- exporting and importing settings ------------------------------------

    /**
     * Writes every setting, the playlists, measured loudness and saved scans to
     * [uri] as one JSON file (see [SettingsBackup]), e.g. to move to another install.
     */
    suspend fun exportSettings(uri: android.net.Uri) = withContext(Dispatchers.IO) {
        val json = SettingsBackup.toJson(
            SettingsBackup.Contents(prefs.all, playlists.toJson(), loudness.toJson(), matchCache.all()),
            System.currentTimeMillis(),
        )
        getApplication<Application>().contentResolver.openOutputStream(uri, "wt")!!.use {
            it.write(json.toString(1).toByteArray())
        }
    }

    /** Replaces settings and data with an export from [uri], and applies them now. */
    suspend fun importSettings(uri: android.net.Uri) {
        val contents = withContext(Dispatchers.IO) {
            val text = getApplication<Application>().contentResolver.openInputStream(uri)!!.use { it.readBytes().decodeToString() }
            SettingsBackup.fromJson(JSONObject(text))
        }
        stopScan()
        stopMeasuring()
        withContext(Dispatchers.IO) {
            prefs.edit().clear().apply {
                contents.settings.forEach { (key, v) ->
                    @Suppress("UNCHECKED_CAST")
                    when (v) {
                        is Boolean -> putBoolean(key, v)
                        is Int -> putInt(key, v)
                        is Long -> putLong(key, v)
                        is Float -> putFloat(key, v)
                        is String -> putString(key, v)
                        is Set<*> -> putStringSet(key, v as Set<String>)
                    }
                }
            }.commit()
            contents.playlists?.let { playlists.restore(it) }
            contents.loudness?.let { loudness.restore(it) }
            matchCache.restore(contents.scans)
        }
        reapplySettings()
    }

    /** Re-reads every setting into the app's state (after an import). */
    private fun reapplySettings() {
        _accent.value = Color(prefs.getInt("accent", DefaultAccent.toArgb()))
        _themeMode.value = readThemeMode()
        prefs.getBoolean("offline", false).let { Net.blocked = it; _offline.value = it }
        _apiKey.value = prefs.getString("acoustid", "").orEmpty()
        _autoOrganize.value = prefs.getBoolean("organize", prefs.contains("libraryFolder"))
        _accentTitles.value = prefs.getBoolean("accentTitles", true)
        _albumGrid.value = prefs.getBoolean("albumGrid", true)
        _normalize.value = prefs.getBoolean(VolumeNormalizer.PREF, true)
        _skipTails.value = prefs.getBoolean(VolumeNormalizer.PREF_SKIP_TAILS, true)
        _history.value = readHistory()
        _libraryFolder.value = prefs.getString("libraryFolder", null)?.let { LibraryFolder.fromPath(it, Organizer.primaryRoot) }
        _scan.value = ScanState(generation = _scan.value.generation + 1)
        reload()
    }

    /** Albums split across one release, from the saved answers; biggest first. */
    suspend fun consolidationProposals(): List<Consolidator.Proposal> = withContext(Dispatchers.IO) {
        val lib = _library.value
        Consolidator.propose(lib.songs, libraryMatches(), { it.albumId }, { lib.album(it)?.title ?: "?" })
    }

    // --- duplicates -------------------------------------------------------------

    /**
     * Finding copies: first the songs without a saved fingerprint are
     * fingerprinted ([done] of [total]), then all are compared. [groups] is
     * null until a search has finished.
     */
    data class DuplicatesState(
        val running: Boolean = false,
        val done: Int = 0,
        val total: Int = 0,
        val groups: List<List<Song>>? = null,
    )

    private val _duplicates = MutableStateFlow(DuplicatesState())
    val duplicates: StateFlow<DuplicatesState> = _duplicates.asStateFlow()
    private var duplicatesJob: kotlinx.coroutines.Job? = null

    /**
     * Looks for copies across the collection. Fingerprints are computed on the
     * phone (no web requests) and saved like a scan's, so the next search and
     * scan reuse them. Stopping compares what is fingerprinted so far.
     */
    fun findDuplicates() {
        if (duplicatesJob?.isActive == true) return
        duplicatesJob = viewModelScope.launch {
            val songs = _library.value.songs
            val todo = withContext(Dispatchers.IO) { songs.filter { matchCache.get(it)?.fingerprint == null } }
            _duplicates.value = DuplicatesState(running = true, total = todo.size)
            var done = 0
            try {
                pipeline(
                    todo, AcoustId.PARALLEL_FINGERPRINTS, Dispatchers.Default,
                    prepare = { song -> Chromaprint.fingerprint(getApplication(), song.uri) },
                    finish = { song, result ->
                        result.onSuccess { fp -> withContext(Dispatchers.IO) { matchCache.put(song, fp, null) } }
                            .onFailure { android.util.Log.w(TAG, "can't fingerprint ${song.path}", it) }
                        _duplicates.value = _duplicates.value.copy(done = ++done)
                    },
                )
            } finally {
                // Also when stopped: compare what there is.
                withContext(NonCancellable) {
                    val groups = withContext(Dispatchers.Default) { duplicateGroups(songs) }
                    _duplicates.value = _duplicates.value.copy(running = false, groups = groups)
                }
            }
        }
    }

    fun stopDuplicates() {
        duplicatesJob?.cancel()
    }

    /** Drops [deleted] songs from the groups found, and groups left with one song. */
    fun forgetDuplicates(deleted: Set<Long>) {
        val groups = _duplicates.value.groups ?: return
        _duplicates.value = _duplicates.value.copy(
            groups = groups.map { g -> g.filter { it.id !in deleted } }.filter { it.size > 1 },
        )
    }

    private fun duplicateGroups(songs: List<Song>): List<List<Song>> {
        val fingerprints = songs.mapNotNull { s ->
            matchCache.get(s)?.fingerprint?.let { Chromaprint.decode(it) }?.let { s.id to it }
        }.toMap()
        return Duplicates.find(songs, fingerprints)
    }

    // --- MusicBrainz search by name -----------------------------------------

    suspend fun searchReleases(album: String, artist: String): List<MusicBrainz.Found> = MusicBrainz.search(album, artist)

    /** The release's tracks, paired with [songs] by title, length and position. */
    suspend fun matchRelease(found: MusicBrainz.Found, songs: List<Song>): ReleaseCandidate =
        MusicBrainz.match(songs, MusicBrainz.release(found.id))

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

