package com.tune.music.data

/**
 * The folder the music library lives in: [volumeRoot] is the storage volume
 * ("/storage/emulated/0", "/storage/1234-ABCD"), [relativePath] the folder on
 * it ("Music/Mine", or "" for the whole volume).
 */
data class LibraryFolder(val volumeRoot: String, val relativePath: String) {

    val absolutePath: String get() = if (relativePath.isEmpty()) volumeRoot else "$volumeRoot/$relativePath"

    val isPrimary: Boolean get() = volumeRoot.endsWith("/emulated/0")

    /** What to show the user, e.g. "Music/Mine" or "Music (SD card)". */
    val label: String
        get() {
            val folder = relativePath.ifEmpty { "whole storage" }
            return if (isPrimary) folder else "$folder (SD card)"
        }

    /**
     * Whether files can be organized into this folder: MediaStore only lets
     * apps move audio into a few standard top-level folders.
     */
    val canOrganizeInto: Boolean get() = relativePath.substringBefore('/') in MOVABLE_ROOTS

    /** True if [path] (a file) is inside this folder or its subfolders. */
    fun contains(path: String): Boolean = path.startsWith("$absolutePath/")

    companion object {
        val MOVABLE_ROOTS = setOf(
            "Music", "Download", "Podcasts", "Audiobooks", "Recordings",
            "Alarms", "Notifications", "Ringtones",
        )

        /**
         * From the document id of a folder picked with the system folder
         * picker (ExternalStorageProvider): "primary:Music/Mine" or
         * "1234-ABCD:Music". Returns null for anything else.
         */
        fun fromTreeDocumentId(docId: String, primaryRoot: String): LibraryFolder? {
            val volume = docId.substringBefore(':', "")
            if (volume.isEmpty() || !docId.contains(':')) return null
            val rel = docId.substringAfter(':').trim('/')
            val root = if (volume == "primary") primaryRoot else "/storage/$volume"
            return LibraryFolder(root.trimEnd('/'), rel)
        }

        /** From a stored absolute path. */
        fun fromPath(path: String, primaryRoot: String): LibraryFolder? {
            val p = path.trimEnd('/')
            val primary = primaryRoot.trimEnd('/')
            if (p == primary || p.startsWith("$primary/")) return LibraryFolder(primary, p.removePrefix(primary).trim('/'))
            val m = Regex("^(/storage/[^/]+)(/.*)?$").find(p) ?: return null
            return LibraryFolder(m.groupValues[1], m.groupValues[2].trim('/'))
        }
    }
}
