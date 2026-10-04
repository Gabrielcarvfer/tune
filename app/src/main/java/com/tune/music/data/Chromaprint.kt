package com.tune.music.data

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.nio.ByteOrder
import kotlin.coroutines.coroutineContext

/** Decodes the start of a song with MediaCodec and fingerprints it with Chromaprint (native). */
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

    suspend fun fingerprint(context: Context, uri: Uri): String = withContext(Dispatchers.Default) {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        var handle = 0L
        try {
            extractor.setDataSource(context, uri, null)
            val track = (0 until extractor.trackCount).firstOrNull {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: error("no audio track")
            extractor.selectTrack(track)
            val inFormat = extractor.getTrackFormat(track)
            codec = MediaCodec.createDecoderByType(inFormat.getString(MediaFormat.KEY_MIME)!!)
            codec.configure(inFormat, null, null, 0)
            codec.start()

            val info = MediaCodec.BufferInfo()
            var sampleRate = inFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var channels = inFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            var isFloat = false
            var fed = 0L
            var limit = Long.MAX_VALUE
            var inputDone = false
            var buf = ShortArray(0)

            while (true) {
                coroutineContext.ensureActive()
                if (!inputDone) {
                    val inIdx = codec.dequeueInputBuffer(10_000)
                    if (inIdx >= 0) {
                        val ib = codec.getInputBuffer(inIdx)!!
                        val n = extractor.readSampleData(ib, 0)
                        if (n < 0) {
                            codec.queueInputBuffer(inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            codec.queueInputBuffer(inIdx, 0, n, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }
                val outIdx = codec.dequeueOutputBuffer(info, 10_000)
                when {
                    outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val f = codec.outputFormat
                        sampleRate = f.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        channels = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                        isFloat = f.containsKey(MediaFormat.KEY_PCM_ENCODING) &&
                            f.getInteger(MediaFormat.KEY_PCM_ENCODING) == AudioFormat.ENCODING_PCM_FLOAT
                    }
                    outIdx >= 0 -> {
                        if (handle == 0L) {
                            handle = nativeStart(sampleRate, channels)
                            if (handle == 0L) error("chromaprint init failed")
                            limit = MAX_SECONDS.toLong() * sampleRate * channels
                        }
                        val ob = codec.getOutputBuffer(outIdx)!!
                        ob.position(info.offset)
                        ob.limit(info.offset + info.size)
                        ob.order(ByteOrder.nativeOrder())
                        val count: Int
                        if (isFloat) {
                            val fb = ob.asFloatBuffer()
                            count = fb.remaining()
                            if (buf.size < count) buf = ShortArray(count)
                            for (i in 0 until count) {
                                buf[i] = (fb.get(i).coerceIn(-1f, 1f) * Short.MAX_VALUE).toInt().toShort()
                            }
                        } else {
                            val sb = ob.asShortBuffer()
                            count = sb.remaining()
                            if (buf.size < count) buf = ShortArray(count)
                            sb.get(buf, 0, count)
                        }
                        val take = minOf(count.toLong(), limit - fed).toInt()
                        if (take > 0) nativeFeed(handle, buf, take)
                        fed += take
                        codec.releaseOutputBuffer(outIdx, false)
                        if (fed >= limit || info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                    }
                }
            }
            if (handle == 0L) error("no audio decoded")
            nativeFinish(handle) ?: error("fingerprint failed")
        } finally {
            if (handle != 0L) nativeFree(handle)
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            extractor.release()
        }
    }
}
