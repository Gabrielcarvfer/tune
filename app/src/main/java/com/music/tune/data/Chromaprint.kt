package com.music.tune.data

import android.content.Context
import android.net.Uri

/** Fingerprints the start of a song with Chromaprint (native), decoded by [AudioDecoder], and decodes saved fingerprints. */
object Chromaprint {
    init {
        System.loadLibrary("tune_native")
    }

    /** Same as fpcalc: the first two minutes are enough for AcoustID. */
    private const val MAX_SECONDS = 120

    @JvmStatic private external fun nativeStart(sampleRate: Int, channels: Int): Long
    @JvmStatic private external fun nativeFeed(handle: Long, samples: ShortArray, count: Int): Boolean
    @JvmStatic private external fun nativeFinish(handle: Long): String?
    @JvmStatic private external fun nativeFree(handle: Long)
    @JvmStatic private external fun nativeDecode(encoded: String): IntArray?

    /** The raw fingerprint (one 32-bit item per ~0.12 s of audio) behind a compressed one, or null if it isn't one. */
    fun decode(encoded: String): IntArray? = nativeDecode(encoded)

    suspend fun fingerprint(context: Context, uri: Uri): String {
        var handle = 0L
        try {
            AudioDecoder.decode(context, uri, MAX_SECONDS, object : AudioDecoder.Sink {
                override fun start(sampleRate: Int, channels: Int) {
                    handle = nativeStart(sampleRate, channels)
                    if (handle == 0L) error("chromaprint init failed")
                }

                override fun feed(samples: ShortArray, count: Int) {
                    nativeFeed(handle, samples, count)
                }
            })
            return nativeFinish(handle) ?: error("fingerprint failed")
        } finally {
            if (handle != 0L) nativeFree(handle)
        }
    }
}
