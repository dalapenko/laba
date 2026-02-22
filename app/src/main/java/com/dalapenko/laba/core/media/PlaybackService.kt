package com.dalapenko.laba.core.media

import android.app.PendingIntent
import android.content.Intent
import android.os.Bundle
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.CommandButton
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import com.dalapenko.laba.MainActivity
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture

class PlaybackService : MediaSessionService() {

    companion object {
        const val ACTION_SEEK_BACK = "com.dalapenko.laba.SEEK_BACK"
        const val ACTION_SEEK_FORWARD = "com.dalapenko.laba.SEEK_FORWARD"
        const val SEEK_INCREMENT_MS = 10_000L
    }

    private var mediaSession: MediaSession? = null

    @OptIn(UnstableApi::class)
    override fun onCreate() {
        super.onCreate()
        val player = ExoPlayer.Builder(this)
            .setAudioAttributes(AudioAttributes.DEFAULT, true)
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_LOCAL)
            .setSeekBackIncrementMs(10_000L)
            .setSeekForwardIncrementMs(10_000L)
            .build()

        val sessionActivityIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        mediaSession = MediaSession.Builder(this, player)
            .setSessionActivity(sessionActivityIntent)
            .setCallback(PlaybackSessionCallback())
            .build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? =
        mediaSession

    override fun onDestroy() {
        mediaSession?.run {
            player.release()
            release()
        }
        mediaSession = null
        super.onDestroy()
    }

    @OptIn(UnstableApi::class)
    private class PlaybackSessionCallback : MediaSession.Callback {

        override fun onConnect(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
        ): MediaSession.ConnectionResult {
            if (session.isMediaNotificationController(controller)) {
                val playerCommands = MediaSession.ConnectionResult.DEFAULT_PLAYER_COMMANDS.buildUpon()
                    .remove(Player.COMMAND_SEEK_TO_PREVIOUS)
                    .remove(Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM)
                    .remove(Player.COMMAND_SEEK_TO_NEXT)
                    .remove(Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM)
                    .build()

                val sessionCommands = MediaSession.ConnectionResult.DEFAULT_SESSION_COMMANDS.buildUpon()
                    .add(SessionCommand(ACTION_SEEK_BACK, Bundle.EMPTY))
                    .add(SessionCommand(ACTION_SEEK_FORWARD, Bundle.EMPTY))
                    .build()

                val mediaButtonPreferences = ImmutableList.of(
                    CommandButton.Builder(CommandButton.ICON_SKIP_BACK_10)
                        .setSessionCommand(SessionCommand(ACTION_SEEK_BACK, Bundle.EMPTY))
                        .setDisplayName("Rewind 10 seconds")
                        .build(),
                    CommandButton.Builder(CommandButton.ICON_SKIP_FORWARD_10)
                        .setSessionCommand(SessionCommand(ACTION_SEEK_FORWARD, Bundle.EMPTY))
                        .setDisplayName("Forward 10 seconds")
                        .build(),
                )

                return MediaSession.ConnectionResult.AcceptedResultBuilder(session)
                    .setAvailablePlayerCommands(playerCommands)
                    .setAvailableSessionCommands(sessionCommands)
                    .setMediaButtonPreferences(mediaButtonPreferences)
                    .build()
            }
            return super.onConnect(session, controller)
        }

        override fun onCustomCommand(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
            customCommand: SessionCommand,
            args: Bundle,
        ): ListenableFuture<SessionResult> {
            when (customCommand.customAction) {
                ACTION_SEEK_BACK -> {
                    session.player.seekTo(
                        (session.player.currentPosition - SEEK_INCREMENT_MS).coerceAtLeast(0L)
                    )
                }
                ACTION_SEEK_FORWARD -> {
                    val dur = session.player.duration
                    session.player.seekTo(
                        if (dur > 0) {
                            (session.player.currentPosition + SEEK_INCREMENT_MS).coerceAtMost(dur)
                        } else {
                            session.player.currentPosition + SEEK_INCREMENT_MS
                        }
                    )
                }
            }
            return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
        }
    }
}
