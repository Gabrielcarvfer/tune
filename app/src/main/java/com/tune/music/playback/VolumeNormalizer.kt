package com.tune.music.playback

import android.content.Context
import android.content.SharedPreferences
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.tune.music.data.LoudnessStore
import com.tune.music.data.Normalization
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Plays every song at a similar loudness without changing the files: when a
 * song starts, the player's volume is set from its measured loudness (loud
 * songs turned down to the target). Songs not measured yet are measured when
 * they come up, and the next one in the queue ahead of time.
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
        if (key == PREF) apply()
    }

    private val playerListener = object : Player.Listener {
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) = apply()
        override fun onTimelineChanged(timeline: androidx.media3.common.Timeline, reason: Int) = apply()
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
        if (!enabled) {
            player.volume = 1f
            return
        }
        val item = player.currentMediaItem ?: return
        val id = item.mediaId.toLongOrNull() ?: return
        val loudness = store[id]
        player.volume = Normalization.volume(Normalization.gainDb(loudness))
        if (loudness == null) measure(item)
        val next = player.nextMediaItemIndex
        if (next != androidx.media3.common.C.INDEX_UNSET) {
            val nextItem = player.getMediaItemAt(next)
            if (nextItem.mediaId.toLongOrNull()?.let { store[it] } == null) measure(nextItem)
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
    }
}
