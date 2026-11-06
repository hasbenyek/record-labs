package com.recordlabs.desktop.data

import java.io.File
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Real song / music video audio is not kept in the repository: point
 * RECORD_LABS_ALIGN_DIR at a folder with song.webm and video.webm (YouTube
 * itag 249) and EXPECTED_OFFSET_MS at the known offset to run it.
 */
class AudioAlignmentTest {
    @Test
    fun linesUpSongAndVideo() {
        val dir = System.getenv("RECORD_LABS_ALIGN_DIR")?.let(::File) ?: return
        val song = WebmOpusDecoder.decode(File(dir, "song.webm").readBytes())
        val video = WebmOpusDecoder.decode(File(dir, "video.webm").readBytes())
        println("decoded ${song.size / 8000.0}s / ${video.size / 8000.0}s")
        val started = System.nanoTime()
        val segments = AudioAlignment.align(song, video)
        println("aligned in ${(System.nanoTime() - started) / 1_000_000} ms: $segments")
        assertNotNull(segments)
        val expected = System.getenv("EXPECTED_OFFSET_MS")?.toLongOrNull() ?: return
        val pair = TrackCounterpart(Track("v", "", ""), segments)
        listOf(10_000L, 60_000L, 120_000L).forEach { at ->
            val mapped = pair.mapPosition(at)
            assertTrue(kotlin.math.abs(mapped - at - expected) < 300, "at $at -> $mapped")
        }
    }
}
