package com.recordlabs.android

import android.app.PendingIntent
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.Process
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.recordlabs.desktop.data.AndroidPlaybackEngine

/**
 * Keeps the music playing in the background: the shared app's player in a
 * media session, which gives the notification, lock-screen and
 * Bluetooth/headset controls. Next/previous go to the app's own queue, like
 * the desktop media keys.
 */
@UnstableApi
class PlaybackService : MediaSessionService() {
    private var session: MediaSession? = null

    override fun onCreate() {
        super.onCreate()
        val openApp = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        session = MediaSession.Builder(this, QueueAwarePlayer(AndroidPlaybackEngine.instance()))
            .setSessionActivity(openApp)
            .build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    override fun onTaskRemoved(rootIntent: Intent?) {
        // Swiping the app away from recent apps closes it: the music stops too (it
        // used to keep playing with no app to come back to). The whole app ends, so
        // Connect or Listen Together cannot start the sound again in the background.
        runCatching {
            val player = AndroidPlaybackEngine.instance().player
            player.playWhenReady = false
            player.pause()
        }
        runCatching { session?.release() }
        session = null
        runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
        stopSelf()
        Handler(Looper.getMainLooper()).postDelayed({ Process.killProcess(Process.myPid()) }, 400L)
    }

    override fun onDestroy() {
        session?.release()
        session = null
        super.onDestroy()
    }
}

/**
 * The player as the system sees it: next/previous are always offered and go
 * to the shared app's queue (ExoPlayer itself only holds the current song,
 * whose stream URL is resolved when it starts). Play/pause also go through the
 * app, so its own state (Listen Together, Connect) stays in step.
 */
@UnstableApi
private class QueueAwarePlayer(private val engine: AndroidPlaybackEngine) :
    androidx.media3.common.ForwardingPlayer(engine.player) {
    override fun getAvailableCommands(): Player.Commands =
        super.getAvailableCommands().buildUpon()
            .add(Player.COMMAND_SEEK_TO_NEXT)
            .add(Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM)
            .add(Player.COMMAND_SEEK_TO_PREVIOUS)
            .add(Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM)
            .build()

    override fun isCommandAvailable(command: Int): Boolean = availableCommands.contains(command)

    private fun command(name: String): Boolean {
        val handler = engine.onMediaCommand ?: return false
        handler(name)
        return true
    }

    override fun seekToNext() { command("next") }
    override fun seekToNextMediaItem() { command("next") }
    override fun seekToPrevious() { command("previous") }
    override fun seekToPreviousMediaItem() { command("previous") }
    override fun play() { if (!command("play")) super.play() }
    override fun pause() { if (!command("pause")) super.pause() }
}
