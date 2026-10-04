package com.tune.music.data

/** TagLib (native) — reads and writes tags inside audio files. */
object TagLib {
    init {
        System.loadLibrary("tune_native")
    }

    /** Field order shared with taglib_jni.cpp. */
    const val TITLE = 0
    const val ARTIST = 1
    const val ALBUM = 2
    const val ALBUM_ARTIST = 3
    const val GENRE = 4
    const val DATE = 5
    const val TRACK = 6
    const val DISC = 7
    const val FIELD_COUNT = 8

    @JvmStatic external fun nativeRead(path: String): Array<String>?

    /** values[i] == null leaves a field unchanged, "" removes it. */
    @JvmStatic external fun nativeWrite(path: String, values: Array<String?>, art: ByteArray?, artMime: String?): Boolean
}
