package com.music.tune.data

import android.content.Context
import android.net.Uri

/** Fingerprints the start of a song with Chromaprint (native), decoded by [AudioDecoder]. */
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
