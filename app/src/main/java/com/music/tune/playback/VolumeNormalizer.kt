package com.music.tune.playback

import android.content.Context
import android.content.SharedPreferences
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.music.tune.data.LoudnessStore
import com.music.tune.data.Normalization
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The player's volume: every song at a similar loudness without changing the
 * files (loud songs turned down to the target, from their measured loudness;
 * songs not measured yet are measured when they come up, and the next one in
 * the queue ahead of time), and skipping a song's quiet ending: when playback
 * reaches the end of its audible part (measured with its loudness), it moves
 * on to the next song.
 */
class VolumeNormalizer(
    private val context: Context,
    private val player: ExoPlayer,
    private val store: LoudnessStore,
    private val scope: CoroutineScope,
) {
    private val prefs = context.getSharedPreferences("tune", Context.MODE_PRIVATE)
    private val measuring = HashSet<Long>()

    private val prefListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == PREF || key == PREF_SKIP_TAILS) apply()
    }

    private val playerListener = object : Player.Listener {
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) = apply()
        override fun onTimelineChanged(timeline: androidx.media3.common.Timeline, reason: Int) = apply()
    }

    /** The current song's volume: its normalized level, or full. */
    private fun target(): Float {
        if (!enabled) return 1f
        val id = player.currentMediaItem?.mediaId?.toLongOrNull() ?: return 1f
        return Normalization.volume(Normalization.gainDb(store[id]))
    }

    fun start() {
        prefs.registerOnSharedPreferenceChangeListener(prefListener)
        player.addListener(playerListener)
        apply()
    }

    fun stop() {
        prefs.unregisterOnSharedPreferenceChangeListener(prefListener)
        player.removeListener(playerListener)
    }

    private val enabled get() = prefs.getBoolean(PREF, true)

    /** Sets the volume for the current song, and measures what's missing. */
    fun apply() {
        player.volume = target()
        scheduleTailSkip()
        if (!enabled && !skipTails) return
        val item = player.currentMediaItem ?: return
        if (needsMeasuring(item)) measure(item)
        val next = player.nextMediaItemIndex
        if (next != androidx.media3.common.C.INDEX_UNSET) {
            val nextItem = player.getMediaItemAt(next)
            if (needsMeasuring(nextItem)) measure(nextItem)
        }
    }

    // Older measurements lack the audible end: measure those again.
    private fun needsMeasuring(item: MediaItem) = item.mediaId.toLongOrNull()?.let { store[it]?.endMs } == null

    private val skipTails get() = prefs.getBoolean(PREF_SKIP_TAILS, true)
    private var tailSkip: androidx.media3.exoplayer.PlayerMessage? = null

    /**
     * Arranges for the current song to end where its audible part does, if
     * that's at least [MIN_TAIL_MS] before its real end.
     */
    private fun scheduleTailSkip() {
        tailSkip?.cancel()
        tailSkip = null
        if (!skipTails) return
        val item = player.currentMediaItem ?: return
        val end = item.mediaId.toLongOrNull()?.let { store[it]?.endMs } ?: return
        val durationMs = item.mediaMetadata.extras?.getLong(EXTRA_DURATION_MS) ?: return
        if (end <= 0 || durationMs - end < MIN_TAIL_MS) return
        val index = player.currentMediaItemIndex
        tailSkip = player.createMessage { _, _ -> skipTail(index, item.mediaId) }
            .setLooper(android.os.Looper.getMainLooper())
            .setPosition(index, end)
            .setDeleteAfterDelivery(true)
            .send()
    }

    /** The song's audible part is over: on to the next one, as if it had ended. */
    private fun skipTail(index: Int, mediaId: String) {
        if (player.currentMediaItemIndex != index || player.currentMediaItem?.mediaId != mediaId) return
        when {
            player.repeatMode == Player.REPEAT_MODE_ONE -> player.seekTo(index, 0)
            player.hasNextMediaItem() -> player.seekToNextMediaItem()
            else -> Unit // the last song: let it play out
        }
    }

    private fun measure(item: MediaItem) {
        val id = item.mediaId.toLongOrNull() ?: return
        val uri = item.localConfiguration?.uri ?: return
        if (!measuring.add(id)) return
        scope.launch {
            try {
                val loudness = withContext(Dispatchers.Default) { LoudnessStore.measure(context, uri) }
                val durationMs = item.mediaMetadata.extras?.getLong(EXTRA_DURATION_MS) ?: 0L
                withContext(Dispatchers.IO) {
                    store.put(id, durationMs, loudness)
                    store.flush()
                }
                // Still playing it? Then turn it to the right level now.
                if (player.currentMediaItem?.mediaId == item.mediaId) apply()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                android.util.Log.w("Tune", "can't measure loudness of $uri", e)
            } finally {
                measuring.remove(id)
            }
        }
    }

    companion object {
        /** Settings key: normalize volume (on by default). */
        const val PREF = "normalize"

        /** Settings key: skip songs' quiet endings (on by default). */
        const val PREF_SKIP_TAILS = "skipTails"

        /** Endings shorter than this aren't worth skipping. */
        const val MIN_TAIL_MS = 1_000L
    }
}
