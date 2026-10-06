package com.music.tune.playback

import android.content.ComponentName
import android.content.Context
import android.os.Bundle
import androidx.core.net.toUri
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.session.MediaController
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionToken
import com.music.tune.data.Song
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.guava.await
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

enum class RepeatMode { OFF, ALL, ONE }

data class PlayerState(
    val connected: Boolean = false,
    val currentId: Long? = null,
    val isPlaying: Boolean = false,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    val shuffle: Boolean = false,
    val repeat: RepeatMode = RepeatMode.OFF,
    /** Song ids in the order they will play (respects shuffle), and where we are in it. */
    val queue: List<Long> = emptyList(),
    val queuePosition: Int = -1,
) {
    val upcoming: List<Long> get() = if (queuePosition < 0) emptyList() else queue.drop(queuePosition + 1)
}

/** Talks to [PlaybackService] through a Media3 [MediaController]. */
class PlayerConnection(private val context: Context, private val scope: CoroutineScope) {
    private var controller: MediaController? = null

    /** The player's output volume (set by volume normalization). */
    val volume: Float get() = controller?.volume ?: 1f
    private val _state = MutableStateFlow(PlayerState())
    val state: StateFlow<PlayerState> = _state.asStateFlow()
    private var ticker: Job? = null

    /** Commands issued before the controller connected. */
    private val pending = ArrayList<MediaController.() -> Unit>()

    fun connect() {
        if (controller != null) return
        val token = SessionToken(context, ComponentName(context, PlaybackService::class.java))
        scope.launch {
            val c = MediaController.Builder(context, token).buildAsync().await()
            controller = c
            c.addListener(object : Player.Listener {
                override fun onEvents(player: Player, events: Player.Events) = refresh()
            })
            pending.forEach { it(c) }
            pending.clear()
            refresh()
        }
    }

    fun release() {
        ticker?.cancel()
        controller?.release()
        controller = null
    }

    private fun run(block: MediaController.() -> Unit) {
        val c = controller
        if (c == null) pending += block else c.block()
    }

    fun play(songs: List<Song>, startIndex: Int = 0, shuffle: Boolean = false) = run {
        if (songs.isEmpty()) return@run
        // "shuffle all" starts on a random song; everything else follows it.
        val start = if (shuffle) songs.indices.random() else startIndex
        setMediaItems(songs.map { it.toMediaItem() }, start, 0)
        shuffleModeEnabled = shuffle
        if (shuffle) sendShuffleOrder(listOf(start) + (songs.indices - start).shuffled())
        prepare()
        play()
    }

    /**
     * Sets the order songs play in while shuffling (indices into the playlist).
     * The player would otherwise pick its own order, in which the current song
     * can land anywhere, e.g. last.
     */
    private fun MediaController.sendShuffleOrder(order: List<Int>) {
        sendCustomCommand(
            SessionCommand(PlaybackService.CMD_SHUFFLE_ORDER, Bundle.EMPTY),
            Bundle().apply { putIntArray(PlaybackService.KEY_ORDER, order.toIntArray()) },
        )
    }

    private fun MediaController.shuffleRestAfterCurrent() {
        val cur = currentMediaItemIndex
        sendShuffleOrder(listOf(cur) + ((0 until mediaItemCount) - cur).shuffled())
    }

    /** Stops and empties the queue (used by tests between cases). */
    fun reset() = run {
        stop()
        clearMediaItems()
        shuffleModeEnabled = false
        repeatMode = Player.REPEAT_MODE_OFF
    }

    fun togglePlay() = run {
        if (isPlaying) pause() else {
            if (playbackState == Player.STATE_ENDED) seekTo(0, 0)
            play()
        }
    }

    fun next() = run { if (hasNextMediaItem()) seekToNextMediaItem() }

    /** Windows Phone behaviour: restart the song if we're more than 3s in, else go back. */
    fun previous() = run {
        if (currentPosition > 3000 || !hasPreviousMediaItem()) seekTo(0L) else seekToPreviousMediaItem()
    }

    fun seekTo(ms: Long) = run { seekTo(ms) }

    fun toggleShuffle() = run {
        val on = !shuffleModeEnabled
        if (on) shuffleRestAfterCurrent()
        shuffleModeEnabled = on
    }

    fun cycleRepeat() = run {
        repeatMode = when (repeatMode) {
            Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
            Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
            else -> Player.REPEAT_MODE_OFF
        }
    }

    fun playNext(songs: List<Song>) = run {
        if (mediaItemCount == 0) return@run play(songs)
        val at = currentMediaItemIndex + 1
        val order = shuffledOrder()
        addMediaItems(at, songs.map { it.toMediaItem() })
        if (order != null) {
            // Old indices at/after the insertion point moved up by songs.size.
            val shifted = order.map { if (it >= at) it + songs.size else it }
            val pos = shifted.indexOf(currentMediaItemIndex) + 1
            sendShuffleOrder(shifted.take(pos) + (at until at + songs.size) + shifted.drop(pos))
        }
    }

    fun enqueue(songs: List<Song>) = run {
        if (mediaItemCount == 0) return@run play(songs)
        val order = shuffledOrder()
        val first = mediaItemCount
        addMediaItems(songs.map { it.toMediaItem() })
        if (order != null) sendShuffleOrder(order + (first until first + songs.size))
    }

    /** The current shuffle order, or null when not shuffling. */
    private fun MediaController.shuffledOrder(): List<Int>? =
        if (shuffleModeEnabled) playOrder(currentTimeline, true) else null

    /** Jumps to a position of [PlayerState.queue]. */
    fun jumpTo(queuePosition: Int) = run {
        val window = playOrder(currentTimeline, shuffleModeEnabled).getOrNull(queuePosition) ?: return@run
        seekTo(window, 0)
        play()
    }

    fun removeFromQueue(queuePosition: Int) = run {
        val window = playOrder(currentTimeline, shuffleModeEnabled).getOrNull(queuePosition) ?: return@run
        removeMediaItem(window)
    }

    /** Removes songs (e.g. deleted from the device) from the queue. */
    fun removeSongs(ids: Set<Long>) = run {
        for (i in mediaItemCount - 1 downTo 0) {
            if (getMediaItemAt(i).mediaId.toLongOrNull() in ids) removeMediaItem(i)
        }
    }

    /** Refreshes queue items after tags changed, keeping position. */
    fun updateSongs(songs: Map<Long, Song>) = run {
        for (i in 0 until mediaItemCount) {
            val id = getMediaItemAt(i).mediaId.toLongOrNull() ?: continue
            val s = songs[id] ?: continue
            if (getMediaItemAt(i).mediaMetadata.title != s.title ||
                getMediaItemAt(i).mediaMetadata.artist != s.artist ||
                getMediaItemAt(i).mediaMetadata.albumTitle != s.album
            ) {
                replaceMediaItem(i, s.toMediaItem())
            }
        }
    }

    private fun refresh() {
        val c = controller ?: return
        val order = playOrder(c.currentTimeline, c.shuffleModeEnabled)
        _state.value = PlayerState(
            connected = true,
            currentId = c.currentMediaItem?.mediaId?.toLongOrNull(),
            isPlaying = c.isPlaying,
            positionMs = c.currentPosition,
            durationMs = c.duration.takeIf { it > 0 } ?: 0,
            shuffle = c.shuffleModeEnabled,
            repeat = when (c.repeatMode) {
                Player.REPEAT_MODE_ALL -> RepeatMode.ALL
                Player.REPEAT_MODE_ONE -> RepeatMode.ONE
                else -> RepeatMode.OFF
            },
            queue = order.map { c.getMediaItemAt(it).mediaId.toLongOrNull() ?: -1 },
            queuePosition = order.indexOf(c.currentMediaItemIndex),
        )
        if (c.isPlaying && ticker?.isActive != true) {
            ticker = scope.launch {
                while (isActive) {
                    delay(500)
                    val ctl = controller ?: break
                    _state.value = _state.value.copy(
                        positionMs = ctl.currentPosition,
                        durationMs = ctl.duration.takeIf { it > 0 } ?: _state.value.durationMs,
                    )
                    if (!ctl.isPlaying) break
                }
            }
        }
    }

    /** Window indices in the order they will play. */
    private fun playOrder(t: Timeline, shuffle: Boolean): List<Int> {
        if (t.isEmpty) return emptyList()
        val out = ArrayList<Int>(t.windowCount)
        var i = t.getFirstWindowIndex(shuffle)
        while (i != C_INDEX_UNSET && out.size < t.windowCount) {
            out += i
            i = t.getNextWindowIndex(i, Player.REPEAT_MODE_OFF, shuffle)
        }
        return out
    }

    private companion object {
        const val C_INDEX_UNSET = androidx.media3.common.C.INDEX_UNSET
    }
}

fun Song.toMediaItem(): MediaItem = MediaItem.Builder()
    .setMediaId(id.toString())
    .setUri(uri)
    .setMediaMetadata(
        MediaMetadata.Builder()
            .setTitle(title)
            .setArtist(artist)
            .setAlbumTitle(album)
            .setAlbumArtist(albumArtist)
            .setGenre(genre)
            .setTrackNumber(track)
            .setArtworkUri(artUri.toString().toUri())
            .setIsPlayable(true)
            .setIsBrowsable(false)
            // The player's volume normalization keys its loudness table by id and length.
            .setExtras(Bundle().apply { putLong(EXTRA_DURATION_MS, durationMs) })
            .build(),
    )
    .build()

/** MediaMetadata extra: the song's length, for the loudness table. */
const val EXTRA_DURATION_MS = "com.music.tune.durationMs"
