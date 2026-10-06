package com.music.tune.playback

import android.app.PendingIntent
import android.content.Intent
import android.os.Bundle
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.source.ShuffleOrder
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionError
import androidx.media3.session.SessionResult
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.music.tune.MainActivity
import com.music.tune.TuneApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

/**
 * Background playback. Media3 takes care of the media notification, lock screen
 * controls, headset buttons and audio focus.
 */
@androidx.annotation.OptIn(UnstableApi::class) // ExoPlayer.setShuffleOrder
class PlaybackService : MediaSessionService() {
    private var session: MediaSession? = null
    private var pendingOrder: IntArray? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var normalizer: VolumeNormalizer? = null

    override fun onCreate() {
        super.onCreate()
        val player = ExoPlayer.Builder(this)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .build(),
                /* handleAudioFocus = */ true,
            )
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_LOCAL)
            .build()

        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).putExtra(MainActivity.EXTRA_NOW_PLAYING, true),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        player.addListener(object : Player.Listener {
            override fun onTimelineChanged(timeline: Timeline, reason: Int) = applyPendingOrder(player)
        })
        normalizer = VolumeNormalizer(this, player, (application as TuneApp).loudness, scope).also { it.start() }
        session = MediaSession.Builder(this, player)
            .setSessionActivity(open)
            .setCallback(object : MediaSession.Callback {
                override fun onConnect(
                    session: MediaSession,
                    controller: MediaSession.ControllerInfo,
                ): MediaSession.ConnectionResult {
                    // Only our own app may set the shuffle order.
                    if (controller.packageName != packageName) return super.onConnect(session, controller)
                    val commands = MediaSession.ConnectionResult.DEFAULT_SESSION_COMMANDS.buildUpon()
                        .add(SessionCommand(CMD_SHUFFLE_ORDER, Bundle.EMPTY))
                        .build()
                    return MediaSession.ConnectionResult.AcceptedResultBuilder(session)
                        .setAvailableSessionCommands(commands)
                        .build()
                }

                override fun onCustomCommand(
                    session: MediaSession,
                    controller: MediaSession.ControllerInfo,
                    customCommand: SessionCommand,
                    args: Bundle,
                ): ListenableFuture<SessionResult> {
                    val order = args.getIntArray(KEY_ORDER)
                    if (customCommand.customAction == CMD_SHUFFLE_ORDER && order != null) {
                        // Custom commands can overtake the playlist change they belong
                        // to, so hold the order until the playlist has the right size.
                        pendingOrder = order
                        applyPendingOrder(player)
                        return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
                    }
                    return Futures.immediateFuture(SessionResult(SessionError.ERROR_BAD_VALUE))
                }
            })
            .build()
    }

    private fun applyPendingOrder(player: ExoPlayer) {
        val order = pendingOrder ?: return
        if (order.size != player.mediaItemCount) return
        pendingOrder = null
        player.setShuffleOrder(ShuffleOrder.DefaultShuffleOrder(order, System.nanoTime()))
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    override fun onTaskRemoved(rootIntent: Intent?) {
        val player = session?.player
        if (player == null || !player.playWhenReady || player.mediaItemCount == 0) stopSelf()
    }

    companion object {
        const val CMD_SHUFFLE_ORDER = "com.music.tune.SHUFFLE_ORDER"
        const val KEY_ORDER = "order"
    }

    override fun onDestroy() {
        normalizer?.stop()
        scope.cancel()
        session?.run {
            player.release()
            release()
        }
        session = null
        super.onDestroy()
    }
}
