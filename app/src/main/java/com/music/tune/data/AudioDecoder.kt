package com.music.tune.data

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

/**
 * Decodes a song to 16-bit interleaved PCM with MediaCodec, for analysis
 * (fingerprinting, loudness). Stops after [maxSeconds] of audio, if given.
 */
object AudioDecoder {

    interface Sink {
        /** The decoded format; called before the first samples. */
        fun start(sampleRate: Int, channels: Int)

        /** [count] interleaved samples in [samples]. */
        fun feed(samples: ShortArray, count: Int)
    }

    suspend fun decode(context: Context, uri: Uri, maxSeconds: Int? = null, sink: Sink) = withContext(Dispatchers.Default) {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
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
            var started = false
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
                        if (!started) {
                            sink.start(sampleRate, channels)
                            started = true
                            if (maxSeconds != null) limit = maxSeconds.toLong() * sampleRate * channels
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
                        if (take > 0) sink.feed(buf, take)
                        fed += take
                        codec.releaseOutputBuffer(outIdx, false)
                        if (fed >= limit || info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                    }
                }
            }
            if (!started) error("no audio decoded")
        } finally {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            extractor.release()
        }
    }
}
