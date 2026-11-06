package com.recordlabs.desktop.data

import kotlinx.coroutines.runBlocking
import org.junit.Test
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PlaybackTest {
    @Test
    fun testPlaybackWithResume() = runBlocking {
        val repo = WindowsMusicRepository()
        val track = Track("fuBMrVffb84", "petal", "Ariana Grande", durationMs = 180_000L)
        val player = WindowsMediaPlayer()
        try {
            val proxyUrl = repo.streamUrl(track) ?: error("streamUrl returned null")
            player.play(track, proxyUrl)

            val startDeadline = System.currentTimeMillis() + 15_000L
            while (player.positionMs < 750L && player.lastError == null && System.currentTimeMillis() < startDeadline) {
                Thread.sleep(200L)
            }
            assertNull(player.lastError, "Native player failed while opening the proxy stream")
            assertTrue(player.positionMs >= 750L, "Playback did not start within 15 seconds")

            player.pause()
            Thread.sleep(500L)
            val pausedAt = player.positionMs
            Thread.sleep(900L)
            assertTrue(player.positionMs - pausedAt < 400L, "Playback position kept advancing while paused")

            player.resume()
            val resumeDeadline = System.currentTimeMillis() + 5_000L
            while (player.positionMs < pausedAt + 750L && player.lastError == null && System.currentTimeMillis() < resumeDeadline) {
                Thread.sleep(200L)
            }
            assertNull(player.lastError, "Native player failed after resume")
            assertTrue(player.positionMs >= pausedAt + 750L, "Playback did not resume within 5 seconds")
        } finally {
            player.release()
            repo.close()
            WindowsAudioProxy.stop()
        }
    }
}
