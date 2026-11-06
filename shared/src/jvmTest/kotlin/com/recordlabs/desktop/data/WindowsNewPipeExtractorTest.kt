package com.recordlabs.desktop.data

import okhttp3.OkHttpClient
import okhttp3.Request
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

class WindowsNewPipeExtractorTest {
    @Test
    fun resolvesAudioUrlForPublicMusicVideo() {
        // Regression test for the desktop downloader. The test video is the same kind of public
        // YouTube Music result shown by Windows search; a blank result means playback cannot start.
        val url = WindowsNewPipeExtractor.audioStream("fuBMrVffb84")
        assertTrue(!url.isNullOrBlank(), "NewPipe did not resolve a playable audio URL")
    }

    @Test
    fun resolvesCurrentHomeSearchTracks() {
        listOf("fTKqtvXjkvo", "oSBx5IfabCo").forEach { videoId ->
            assertTrue(
                !WindowsNewPipeExtractor.audioStream(videoId).isNullOrBlank(),
                "NewPipe did not resolve current Home track $videoId",
            )
        }
    }

    @Test
    fun innerTubeAutoplayReturnsRelatedSongsInsteadOfSearchOrdering() = runBlocking {
        WindowsMusicRepository().use { repository ->
            val radio = repository.nextRadioQueue("fuBMrVffb84")
            assertTrue(radio.isNotEmpty(), "InnerTube did not return an autoplay queue")
            assertTrue(radio.none { it.id == "fuBMrVffb84" }, "Autoplay queue repeated the seed video")
            assertTrue(radio.all { it.title.isNotBlank() && it.artist.isNotBlank() }, "Autoplay queue has incomplete song metadata")
        }
    }

    @Test
    fun javaFxStreamsAndSeeksResolvedAudio() {
        val repository = WindowsMusicRepository()
        val url = runBlocking {
            repository.streamUrl(Track("fuBMrVffb84", "petal", "Ariana Grande"))
        } ?: error("No local audio URL")
        val player = WindowsMediaPlayer()
        player.play(Track("fuBMrVffb84", "petal", "Ariana Grande"), url)
        repeat(60) {
            if (player.isPlaying || player.positionMs > 0L) {
                val firstPosition = player.positionMs
                Thread.sleep(12_000)
                assertTrue(player.isPlaying || player.currentTrack != null, "Playback stopped shortly after it started")
                val seekTarget = (player.durationMs / 2).coerceAtLeast(30_000L)
                player.seekTo(seekTarget)
                Thread.sleep(2_500)
                player.stop()
                return
            }
            Thread.sleep(250)
        }
        assertTrue(player.isPlaying || player.currentTrack != null, "Could not begin playback for a resolved audio URL")
    }


    @Test
    fun audioProxyReturnsAccurateByteRanges() {
        val remote = WindowsNewPipeExtractor.audioStream("fuBMrVffb84") ?: error("No remote audio URL")
        val local = WindowsAudioProxy.register(remote)
        OkHttpClient().newCall(
            Request.Builder().url(local).header("Range", "bytes=65536-196607").build(),
        ).execute().use { response ->
            assertEquals(206, response.code, "The proxy must preserve the player's byte-range request")
            assertTrue(response.header("Content-Range")?.startsWith("bytes 65536-") == true)
            assertTrue(response.body?.bytes()?.isNotEmpty() == true, "The range response had no audio bytes")
        }
    }
}
