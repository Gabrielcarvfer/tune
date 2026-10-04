package com.tune.music.data

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Moves songs to <root>/<album artist>/<album>/<NN>-<title>.<ext>, where root
 * is the library folder (or Music); multi-disc albums get <disc>-<NN>-<title>.
 */
class Organizer(private val context: Context, private val tags: TagEditor) {

    data class Target(val dir: String, val name: String, val root: String = MUSIC_DIR) {
        val relativePath: String get() = "$root/$dir/"
    }

    /**
     * Moves the songs under [root] (relative to the phone's storage). With
     * [direct], files are renamed with plain file operations (needs All files
     * access); otherwise through MediaStore. Returns how many failed.
     */
    suspend fun organize(
        songs: List<Song>,
        root: String,
        direct: Boolean,
        albumOf: (Song) -> Album?,
    ): Int = withContext(Dispatchers.IO) {
        var failed = 0
        val scan = ArrayList<String>()
        val emptied = LinkedHashSet<File>()
        for (s in songs) {
            val multi = (albumOf(s)?.songs?.maxOfOrNull { it.disc } ?: 1) > 1
            val t = target(s, multi, root)
            if (isOrganized(s, t)) continue
            val ok = runCatching {
                when {
                    direct -> moveDirect(s, t)
                    Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q -> moveScoped(s, t)
                    else -> moveLegacy(s, t, scan)
                }
            }.onFailure { Log.w("Tune", "can't move ${s.path} to ${t.relativePath}${t.name}", it) }
                .getOrDefault(false)
            if (!ok) failed++ else File(s.path).parentFile?.let { emptied += it }
        }
        tags.rescan(scan)
        removeEmptyDirs(emptied, File(primaryRoot, root))
        failed
    }

    /**
     * Removes folders left empty by moves, walking up to (not including)
     * [stopAt]. rmdir only ever succeeds on an empty folder, so anything still
     * holding files, including ones this app can't see, is left alone.
     */
    fun removeEmptyDirs(dirs: Collection<File>, stopAt: File) {
        val root = stopAt.canonicalFile
        for (start in dirs) {
            var dir: File? = runCatching { start.canonicalFile }.getOrNull()
            while (dir != null && dir != root && dir.path.startsWith(root.path + File.separator)) {
                if (!dir.exists()) {
                    dir = dir.parentFile
                    continue
                }
                if (!dir.delete()) break
                dir = dir.parentFile
            }
        }
    }

    private fun moveScoped(s: Song, t: Target): Boolean {
        val cr = context.contentResolver
        val base = t.name.substringBeforeLast('.')
        val ext = t.name.substringAfterLast('.')
        for (attempt in 0 until 10) {
            val name = if (attempt == 0) t.name else "$base ($attempt).$ext"
            val values = ContentValues().apply {
                put(MediaStore.Audio.Media.RELATIVE_PATH, t.relativePath)
                put(MediaStore.Audio.Media.DISPLAY_NAME, name)
            }
            val ok = try {
                (cr.update(s.uri, values, null, null) > 0).also {
                    if (!it) Log.w("Tune", "MediaStore didn't move ${s.uri} to ${t.relativePath}$name")
                }
            } catch (e: IllegalStateException) {
                false // name clash: try the next suffix
            }
            if (ok) return true
        }
        return false
    }

    /**
     * A plain rename. On Android 11+ it goes through the media provider, which
     * updates the song's existing MediaStore row, so its id (playlists, queue)
     * survives the move.
     */
    private fun moveDirect(s: Song, t: Target): Boolean {
        val src = File(s.path)
        val dir = File(primaryRoot, t.relativePath)
        if (!dir.exists() && !dir.mkdirs()) return false
        var dst = File(dir, t.name)
        var n = 1
        while (dst.exists()) dst = File(dir, t.name.substringBeforeLast('.') + " (${n++})." + t.name.substringAfterLast('.'))
        return src.renameTo(dst)
    }

    @Suppress("DEPRECATION")
    private fun moveLegacy(s: Song, t: Target, scan: MutableList<String>): Boolean {
        val src = File(s.path)
        val dir = File(primaryRoot, t.relativePath)
        if (!dir.exists() && !dir.mkdirs()) return false
        var dst = File(dir, t.name)
        var n = 1
        while (dst.exists()) dst = File(dir, t.name.substringBeforeLast('.') + " (${n++})." + t.name.substringAfterLast('.'))
        if (!src.renameTo(dst)) {
            src.copyTo(dst)
            if (!src.delete()) return false
        }
        context.contentResolver.update(
            s.uri,
            ContentValues().apply { put(MediaStore.Audio.Media.DATA, dst.absolutePath) },
            null, null,
        )
        scan += src.absolutePath
        scan += dst.absolutePath
        return true
    }

    companion object {
        /** Same as Environment.DIRECTORY_MUSIC, which isn't a compile-time constant. */
        const val MUSIC_DIR = "Music"

        @Suppress("DEPRECATION")
        val primaryRoot: String get() = Environment.getExternalStorageDirectory().path

        /**
         * Where organized files go (relative to the phone's storage), or null if
         * they can't be organized without leaving the library: no folder means the
         * whole phone, organized into Music; a folder is organized in place if
         * MediaStore may move audio there or the app has All files access.
         */
        fun rootFor(folder: LibraryFolder?, allFilesAccess: Boolean): String? = when {
            folder == null -> MUSIC_DIR
            !folder.isPrimary || folder.relativePath.isEmpty() -> null
            allFilesAccess || folder.canOrganizeInto -> folder.relativePath
            else -> null
        }

        fun target(song: Song, multiDisc: Boolean, root: String = MUSIC_DIR): Target {
            val ext = song.path.substringAfterLast('.', "mp3").lowercase()
            val artist = clean(song.albumArtist, "unknown artist")
            val album = clean(song.album, "unknown album")
            val title = clean(song.title, "unknown")
            // No track number: just the title, rather than a meaningless "00-".
            if (song.track <= 0) return Target("$artist/$album", "$title.$ext", root)
            val num = "%02d".format(song.track)
            val prefix = if (multiDisc) "${song.disc}-$num" else num
            return Target("$artist/$album", "$prefix-$title.$ext", root)
        }

        /**
         * True when the song already lives where [t] says, including the
         * "Name (2).ext" copies made when a file with that name already existed.
         */
        fun isOrganized(song: Song, t: Target): Boolean {
            val path = song.path.replace('\\', '/')
            if (!path.substringBeforeLast('/').endsWith("/" + t.relativePath.trimEnd('/'))) return false
            val name = path.substringAfterLast('/')
            val base = Regex.escape(t.name.substringBeforeLast('.'))
            val ext = Regex.escape(t.name.substringAfterLast('.'))
            return Regex("""$base( \(\d+\))?\.$ext""").matches(name)
        }

        private val BAD = Regex("""[\\/:*?"<>|\u0000-\u001f]""")

        fun clean(s: String, fallback: String): String {
            var v = s.takeIf { it != UNKNOWN }.orEmpty().replace(BAD, "_").trim().trimEnd('.', ' ').take(100)
            // A leading dot hides the file or folder, and Android's media scanner skips
            // hidden folders, so "...And Justice" becomes "_..And Justice" (as Picard does).
            if (v.startsWith('.')) v = "_" + v.drop(1)
            return v.ifEmpty { fallback }
        }
    }
}
