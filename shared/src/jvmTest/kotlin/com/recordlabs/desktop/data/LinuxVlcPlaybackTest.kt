package com.recordlabs.desktop.data

import com.sun.net.httpserver.HttpServer
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Plays a generated tone through the real Linux audio path:
 * WindowsMediaPlayer -> VlcPlayerBackend -> libVLC, served over local HTTP
 * like the app's audio proxy. Skipped on Windows and where libVLC isn't installed.
 */
class LinuxVlcPlaybackTest {
    private fun libVlcAvailable(): Boolean = listOf(
        "/usr/lib/x86_64-linux-gnu/libvlc.so.5", "/usr/lib64/libvlc.so.5", "/usr/lib/libvlc.so.5",
        "src/appResources/linux-x64/vlc/libvlc.so.5",
    ).any { File(it).isFile }

    private fun toneWav(seconds: Int, sampleRate: Int = 44100): ByteArray {
        val samples = seconds * sampleRate
        val data = ByteBuffer.allocate(samples * 2).order(ByteOrder.LITTLE_ENDIAN)
        repeat(samples) { i -> data.putShort((sin(2 * PI * 440 * i / sampleRate) * 8000).toInt().toShort()) }
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray()); putInt(36 + samples * 2); put("WAVE".toByteArray())
            put("fmt ".toByteArray()); putInt(16); putShort(1); putShort(1); putInt(sampleRate); putInt(sampleRate * 2)
            putShort(2); putShort(16); put("data".toByteArray()); putInt(samples * 2)
        }
        return ByteArrayOutputStream().apply { write(header.array()); write(data.array()) }.toByteArray()
    }

    private fun waitFor(timeoutMs: Long, condition: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return true
            Thread.sleep(50)
        }
        return condition()
    }

    @Test
    fun playsLocalFileFromJavaFileUri() {
        if (Platform.isWindows || !libVlcAvailable()) return
        // Local music and cached songs reach the player as File.toURI() strings
        // ("file:/..."), including spaces and non-ASCII names.
        val dir = kotlin.io.path.createTempDirectory("rl-local").toFile()
        // Non-ASCII file names need a UTF-8 locale (every desktop has one; bare containers may not).
        val utf8Names = System.getProperty("sun.jnu.encoding").orEmpty().contains("UTF-8", ignoreCase = true)
        val file = File(dir, if (utf8Names) "lagu tes é.wav" else "lagu tes.wav").apply { writeBytes(toneWav(seconds = 2)) }
        val player = WindowsMediaPlayer()
        var ended = false
        player.onPlaybackEnded = { ended = true }
        try {
            player.play(Track(id = "local", title = "Local", artist = "Test", durationMs = 2000), file.toURI().toString())
            assertTrue(waitFor(6000) { player.isReady && player.positionMs > 200 }, "local file starts: ${player.state.value}")
            assertNull(player.lastError)
            assertTrue(waitFor(6000) { ended }, "local file ends: ${player.state.value}")
        } finally {
            player.release()
            dir.deleteRecursively()
        }
    }

    @Test
    fun playsPausesSeeksRetunesAndEnds() {
        if (Platform.isWindows || !libVlcAvailable()) {
            println("LinuxVlcPlaybackTest skipped (Windows or no libVLC)")
            return
        }
        val wav = toneWav(seconds = 6)
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/tone.wav") { exchange ->
                exchange.responseHeaders.add("Content-Type", "audio/wav")
                exchange.sendResponseHeaders(200, wav.size.toLong())
                exchange.responseBody.use { it.write(wav) }
            }
            start()
        }
        val player = WindowsMediaPlayer()
        var ended = false
        player.onPlaybackEnded = { ended = true }
        try {
            val track = Track(id = "tone", title = "Tone", artist = "Test", durationMs = 6000)
            player.play(track, "http://127.0.0.1:${server.address.port}/tone.wav")

            assertTrue(waitFor(8000) { player.isReady && player.positionMs > 300 }, "starts playing: ${player.state.value}")
            assertNull(player.lastError)
            assertTrue(player.isPlaying)
            assertEquals(6000L, player.durationMs, "duration reported by VLC")

            player.pause()
            assertTrue(waitFor(2000) { !player.isPlaying })
            // pause() flips the UI state at once; give VLC and the 250 ms
            // status poll time to settle before measuring the held position.
            Thread.sleep(500)
            val pausedAt = player.positionMs
            Thread.sleep(600)
            assertTrue(player.positionMs - pausedAt < 150, "position holds while paused")

            player.seekTo(3000)
            player.resume()
            assertTrue(waitFor(3000) { player.isPlaying && player.positionMs >= 3000 }, "seek + resume: ${player.state.value}")

            player.setVolume(0.4f)
            player.setAudioTuning(1.25f, 3) // pitch change rebuilds the VLC player
            Thread.sleep(400)
            assertNull(player.lastError, "retune keeps playing")
            assertTrue(waitFor(3000) { player.isPlaying && player.positionMs >= 3000 }, "continues after retune: ${player.state.value}")

            assertTrue(waitFor(8000) { ended }, "reaches the end: ${player.state.value}")
        } finally {
            player.release()
            server.stop(0)
        }
    }
}
