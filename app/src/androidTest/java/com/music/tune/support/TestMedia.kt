package com.music.tune.support

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import android.os.Environment
import android.os.SystemClock
import android.provider.MediaStore
import com.music.tune.data.TagLib
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.sin

/**
 * Creates real, tagged audio files in shared storage for the tests: AAC in an
 * MP4 container, encoded on the device, tagged with TagLib and inserted through
 * MediaStore (so the app owns them and can delete them without prompts).
 */
object TestMedia {
    const val IN_DIR = "TuneTestIn"
    const val BAND = "Tune Test Band"
    const val ALBUM = "Tune Test Album"
    const val SOLO = "Tune Test Solo"
    const val SINGLE = "Tune Test Single"

    data class Spec(
        val title: String,
        val artist: String,
        val album: String,
        val track: Int,
        val seconds: Int,
        /** Base frequency of the generated melody; different songs, different sound. */
        val baseHz: Double,
        val albumArtist: String = artist,
        val genre: String = "Electronic",
        val year: String = "2020",
        val disc: Int = 1,
        val cover: Int? = null,
        /** Embedded cover image (JPEG); overrides [cover]. */
        val art: ByteArray? = null,
        /** Loudness: 1 is the usual level, 0.5 is 6 dB quieter. */
        val level: Double = 1.0,
    )

    val ALPHA = Spec("Alpha Song", BAND, ALBUM, 1, 7, 440.0, cover = Color.RED)
    val BETA = Spec("Beta Song", BAND, ALBUM, 2, 7, 523.25, cover = Color.RED)
    val GAMMA = Spec("Gamma Song", BAND, ALBUM, 3, 7, 587.33, cover = Color.RED)
    val DELTA = Spec("Delta Tune", SOLO, SINGLE, 1, 6, 329.63, genre = "Ambient", cover = Color.BLUE)

    val DEFAULT = listOf(ALPHA, BETA, GAMMA, DELTA)

    fun folderOf(spec: Spec) = "Music/$IN_DIR/${spec.album}/"

    fun musicDir(): File =
        @Suppress("DEPRECATION")
        Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC)

    /** Encodes [spec] as a tagged AAC file at [out]. */
    fun encodeTagged(ctx: Context, spec: Spec, out: File) {
        encodeAac(out, spec.seconds, spec.baseHz, spec.level)
        val values = arrayOf<String?>(
            spec.title, spec.artist, spec.album, spec.albumArtist, spec.genre, spec.year,
            spec.track.toString(), spec.disc.toString(),
        )
        val art = spec.art ?: spec.cover?.let { jpeg(it) }
        check(TagLib.nativeWrite(out.path, values, art, art?.let { "image/jpeg" })) { "tagging ${spec.title}" }
    }

    /** Encodes, tags and publishes [spec]; returns its MediaStore Uri once scanned. */
    fun create(ctx: Context, spec: Spec): Uri {
        val tmp = File(ctx.cacheDir, "gen_${spec.title.replace(' ', '_')}.m4a")
        encodeTagged(ctx, spec, tmp)

        val cr = ctx.contentResolver
        val uri = cr.insert(
            MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY),
            ContentValues().apply {
                put(MediaStore.Audio.Media.DISPLAY_NAME, "${spec.title}.m4a")
                put(MediaStore.Audio.Media.MIME_TYPE, "audio/mp4")
                put(MediaStore.Audio.Media.RELATIVE_PATH, folderOf(spec))
                put(MediaStore.Audio.Media.IS_PENDING, 1)
            },
        ) ?: error("insert ${spec.title}")
        cr.openOutputStream(uri)!!.use { out -> tmp.inputStream().use { it.copyTo(out) } }
        cr.update(uri, ContentValues().apply { put(MediaStore.Audio.Media.IS_PENDING, 0) }, null, null)
        tmp.delete()
        waitForScan(ctx, uri, spec.title)
        return uri
    }

    private fun waitForScan(ctx: Context, uri: Uri, title: String) {
        val deadline = SystemClock.uptimeMillis() + 15_000
        while (SystemClock.uptimeMillis() < deadline) {
            ctx.contentResolver.query(uri, arrayOf(MediaStore.Audio.Media.TITLE, MediaStore.Audio.Media.DURATION), null, null, null)?.use {
                if (it.moveToFirst() && it.getString(0) == title && it.getLong(1) > 0) return
            }
            SystemClock.sleep(100)
        }
        error("MediaStore never scanned $title")
    }

    /** Deletes every audio file this app owns and the test folders. */
    fun cleanup(ctx: Context) {
        val cr = ctx.contentResolver
        val owned = ArrayList<Uri>()
        cr.query(
            MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY),
            arrayOf(MediaStore.Audio.Media._ID),
            "${MediaStore.Audio.Media.OWNER_PACKAGE_NAME} = ?", arrayOf(ctx.packageName), null,
        )?.use { c ->
            while (c.moveToNext()) {
                owned += android.content.ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, c.getLong(0))
            }
        }
        owned.forEach { runCatching { cr.delete(it, null, null) } }
        musicDir().listFiles()
            ?.filter { it.isDirectory && (it.name.startsWith("Tune Test") || it.name.startsWith("TuneTest")) }
            ?.forEach { removeEmptyTree(it) }
    }

    private fun removeEmptyTree(f: File) {
        f.listFiles()?.forEach { if (it.isDirectory) removeEmptyTree(it) }
        f.delete()
    }

    /** Path of the file behind [uri], as MediaStore reports it. */
    fun pathOf(ctx: Context, uri: Uri): String? =
        ctx.contentResolver.query(uri, arrayOf(MediaStore.Audio.Media.DATA), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0) else null
        }

    fun jpeg(color: Int, size: Int = 64): ByteArray {
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        Canvas(bmp).drawColor(color)
        return ByteArrayOutputStream().also { bmp.compress(Bitmap.CompressFormat.JPEG, 90, it) }.toByteArray()
    }

    /** A little melody (so Chromaprint has something to chew on), AAC-LC mono. */
    fun encodeAac(out: File, seconds: Int, baseHz: Double, level: Double = 1.0) {
        val rate = 44100
        val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, rate, 1).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, 96_000)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16_384)
        }
        val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        codec.start()
        val muxer = MediaMuxer(out.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        val steps = doubleArrayOf(1.0, 1.25, 1.5, 2.0, 1.5, 1.25, 1.125, 1.0)
        val total = rate.toLong() * seconds
        var written = 0L
        var inputDone = false
        var track = -1
        val info = MediaCodec.BufferInfo()
        try {
            while (true) {
                if (!inputDone) {
                    val i = codec.dequeueInputBuffer(10_000)
                    if (i >= 0) {
                        val buf = codec.getInputBuffer(i)!!
                        buf.clear()
                        buf.order(ByteOrder.LITTLE_ENDIAN)
                        val n = minOf((buf.capacity() / 2).toLong(), total - written).toInt()
                        val pts = written * 1_000_000 / rate
                        if (n <= 0) {
                            codec.queueInputBuffer(i, 0, 0, pts, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            for (k in 0 until n) {
                                val t = (written + k).toDouble() / rate
                                val f = baseHz * steps[((t * 3).toInt()) % steps.size]
                                val v = sin(2 * PI * f * t) * 0.5 + sin(2 * PI * f * 2 * t) * 0.2
                                buf.putShort((v * 22_000 * level).toInt().toShort())
                            }
                            codec.queueInputBuffer(i, 0, n * 2, pts, 0)
                            written += n
                        }
                    }
                }
                val o = codec.dequeueOutputBuffer(info, 10_000)
                if (o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    track = muxer.addTrack(codec.outputFormat)
                    muxer.start()
                } else if (o >= 0) {
                    val ob = codec.getOutputBuffer(o)!!
                    if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0 && info.size > 0 && track >= 0) {
                        ob.position(info.offset)
                        ob.limit(info.offset + info.size)
                        muxer.writeSampleData(track, ob, info)
                    }
                    codec.releaseOutputBuffer(o, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                }
            }
        } finally {
            codec.stop()
            codec.release()
            runCatching { muxer.stop() }
            muxer.release()
        }
    }
}
