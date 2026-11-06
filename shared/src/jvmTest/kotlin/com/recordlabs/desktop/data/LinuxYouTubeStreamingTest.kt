package com.recordlabs.desktop.data

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Streams a real YouTube Music song the way the app does: the repository
 * races the stream sources, the audio goes through WindowsAudioProxy (or the
 * cache) and WindowsMediaPlayer plays it (libVLC on Linux).
 *
 * Needs the network and an IP YouTube serves, so it only runs with
 * RECORDLABS_NETWORK_TESTS=1 (optionally RECORDLABS_TEST_VIDEO=<id>).
 */
class LinuxYouTubeStreamingTest {
    private fun waitFor(timeoutMs: Long, condition: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return true
            Thread.sleep(100)
        }
        return condition()
    }

    @Test
    fun streamsAYouTubeMusicSongThroughTheAppPath() {
        if (System.getenv("RECORDLABS_NETWORK_TESTS") != "1") {
            println("LinuxYouTubeStreamingTest skipped (set RECORDLABS_NETWORK_TESTS=1)")
            return
        }
        val videoId = System.getenv("RECORDLABS_TEST_VIDEO") ?: "dQw4w9WgXcQ"
        val repository = WindowsMusicRepository()
        val player = WindowsMediaPlayer()
        try {
            val track = Track(id = videoId, title = "Test", artist = "Test", durationMs = 213_000, musicVideoType = "MUSIC_VIDEO_TYPE_ATV")
            val started = System.currentTimeMillis()
            val url = runBlocking { repository.playbackStreamUrl(track) }
            println("stream resolved in ${System.currentTimeMillis() - started} ms: ${url?.take(80)}")
            assertNotNull(url, "a stream URL was found")

            player.play(track, url)
            assertTrue(waitFor(20_000) { player.isReady && player.positionMs > 1_500 }, "audio starts: ${player.state.value}")
            println("audio playing after ${System.currentTimeMillis() - started} ms (duration ${player.durationMs} ms)")
            assertNull(player.lastError)

            // Seek forward: the proxy must serve a new byte range to VLC.
            player.seekTo(150_000)
            assertTrue(waitFor(15_000) { player.isPlaying && player.positionMs in 150_000..170_000 }, "seek: ${player.state.value}")

            player.pause()
            Thread.sleep(800)
            val held = player.positionMs
            Thread.sleep(800)
            assertTrue(player.positionMs - held < 200, "paused")
            player.resume()
            assertTrue(waitFor(10_000) { player.isPlaying && player.positionMs > held + 1_000 }, "resume: ${player.state.value}")
            println("final state: ${player.state.value}")
        } finally {
            player.release()
            repository.close()
        }
    }

    /**
     * The fallbacks that need the bundled Linux tools: yt-dlp alone (with Deno
     * as its JavaScript runtime), and WEB_REMIX alone (its URLs are deciphered
     * with Deno). Each runs with every other stream source switched off.
     */
    @Test
    fun fallbackSourcesPlayOnLinux() {
        if (System.getenv("RECORDLABS_NETWORK_TESTS") != "1") return
        val videoId = System.getenv("RECORDLABS_TEST_VIDEO") ?: "dQw4w9WgXcQ"
        val saved = AppSettings.disabledStreamSources.value
        val results = mutableMapOf<String, String>()
        try {
            for (source in listOf("YTDLP", "WEB_REMIX")) {
                AppSettings.disabledStreamSources.value = AppSettings.streamSourceNames.filter { it != source }.joinToString(",")
                val repository = WindowsMusicRepository()
                val player = WindowsMediaPlayer()
                try {
                    // Fresh repository (no in-memory URL cache) and no cached audio file,
                    // so this source really has to resolve and stream the song.
                    WindowsAudioCache.trimCache(0L)
                    val track = Track(id = videoId, title = "Test", artist = "Test", durationMs = 213_000, musicVideoType = "MUSIC_VIDEO_TYPE_ATV")
                    val url = runBlocking { repository.playbackStreamUrl(track) }
                    if (url == null) {
                        results[source] = "no stream"
                        continue
                    }
                    player.play(track, url)
                    val playing = waitFor(20_000) { player.isReady && player.positionMs > 1_500 }
                    assertTrue(url.startsWith("http://127.0.0.1"), "$source streamed through the proxy, not a cached file: $url")
                    results[source] = if (playing && player.lastError == null) "playing" else "failed: ${player.state.value}"
                } finally {
                    player.release()
                    repository.close()
                }
            }
        } finally {
            AppSettings.disabledStreamSources.value = saved
        }
        println("fallback results: $results")
        assertTrue(results["YTDLP"] == "playing", "yt-dlp + Deno: ${results["YTDLP"]}")
        // WEB_REMIX can be refused for the test machine's network; report it, require it only when asked.
        if (System.getenv("RECORDLABS_REQUIRE_WEB_REMIX") == "1") {
            assertTrue(results["WEB_REMIX"] == "playing", "WEB_REMIX + Deno cipher: ${results["WEB_REMIX"]}")
        }
    }
}
