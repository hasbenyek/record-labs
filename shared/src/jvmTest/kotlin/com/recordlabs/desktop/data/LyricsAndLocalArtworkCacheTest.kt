package com.recordlabs.desktop.data

import java.io.ByteArrayOutputStream
import java.io.File
import java.net.URI
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class LyricsAndLocalArtworkCacheTest {
    @Test
    fun manuallySelectedLyricsReplaceAndPersistOverAutomaticChoice() {
        val suffix = System.nanoTime().toString()
        val track = Track(
            id = "manual-cache-$suffix",
            title = "Manual cache title $suffix",
            artist = "Manual cache artist $suffix",
            album = "Manual cache album $suffix",
            durationMs = 123_456,
        )
        val selected = WindowsLyricsProviderRegistry.selectLyrics(
            track,
            LyricsCandidate("Paxsenix", "[00:01.00]chosen by the user"),
        )
        val restored = WindowsLyricsProviderRegistry.peekCached(track)
        assertEquals(selected, restored)
        assertTrue(restored?.manuallySelected == true)

        val cacheFile = WindowsLyricsProviderRegistry::class.java
            .getDeclaredMethod("cacheFile", Track::class.java)
            .apply { isAccessible = true }
            .invoke(WindowsLyricsProviderRegistry, track) as File
        cacheFile.delete()
    }

    @Test
    fun mp3EmbeddedArtworkIsExtractedAsLocalArtworkUrl() {
        val tempDirectory = Files.createTempDirectory("recordlabs-local-artwork-test").toFile()
        val mp3 = File(tempDirectory, "Artist - Song.mp3")
        val jpeg = ByteArray(1_500).apply {
            this[0] = 0xff.toByte(); this[1] = 0xd8.toByte(); this[2] = 0xff.toByte()
            this[lastIndex - 1] = 0xff.toByte(); this[lastIndex] = 0xd9.toByte()
        }
        val frames = ByteArrayOutputStream().apply {
            write(textFrame("TIT2", "Local Song"))
            write(textFrame("TPE1", "Local Artist"))
            write(textFrame("TALB", "Local Album"))
            val apic = ByteArrayOutputStream().apply {
                write(0)
                write("image/jpeg".toByteArray(Charsets.ISO_8859_1)); write(0)
                write(3); write(0)
                write(jpeg)
            }.toByteArray()
            write(frame("APIC", apic))
        }.toByteArray()
        mp3.writeBytes(byteArrayOf('I'.code.toByte(), 'D'.code.toByte(), '3'.code.toByte(), 3, 0, 0) +
            synchsafe(frames.size) + frames + ByteArray(32))

        val readTrack = WindowsLocalMusicLibrary::class.java
            .getDeclaredMethod("readTrack", File::class.java)
            .apply { isAccessible = true }
        val track = readTrack.invoke(WindowsLocalMusicLibrary, mp3) as Track
        val artworkUrl = assertNotNull(track.artworkUrl)
        val extracted = File(URI(artworkUrl))
        assertTrue(extracted.isFile)
        assertTrue(extracted.length() >= jpeg.size)
        assertEquals("Local Artist", track.artist)
        assertEquals("Local Song", track.title)

        extracted.delete()
        tempDirectory.deleteRecursively()
    }

    @Test
    fun parseLocalArtworkFileAndResolveArtworkModel() {
        val tempDirectory = Files.createTempDirectory("recordlabs-resolve-artwork-test").toFile()
        val dummyImg = File(tempDirectory, "art with spaces.jpg").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        val fileUrl = dummyImg.toURI().toASCIIString()
        val parsed = WindowsLocalMusicLibrary.parseLocalArtworkFile(fileUrl)
        assertNotNull(parsed)
        assertTrue(parsed.isFile)
        assertEquals(dummyImg.absolutePath, parsed.absolutePath)

        val track = Track(
            id = "test-local-track",
            title = "Test Title",
            artist = "Test Artist",
            artworkUrl = fileUrl,
            localPath = null,
        )
        val model = WindowsLocalMusicLibrary.resolveArtworkModel(track)
        assertTrue(model is File)
        assertEquals(dummyImg.absolutePath, (model as File).absolutePath)

        dummyImg.delete()
        tempDirectory.deleteRecursively()
    }

    private fun textFrame(id: String, value: String): ByteArray =
        frame(id, byteArrayOf(3) + value.toByteArray(Charsets.UTF_8))

    private fun frame(id: String, body: ByteArray): ByteArray =
        id.toByteArray(Charsets.ISO_8859_1) +
            byteArrayOf(
                (body.size ushr 24).toByte(), (body.size ushr 16).toByte(),
                (body.size ushr 8).toByte(), body.size.toByte(), 0, 0,
            ) + body

    private fun synchsafe(value: Int): ByteArray = byteArrayOf(
        ((value ushr 21) and 0x7f).toByte(),
        ((value ushr 14) and 0x7f).toByte(),
        ((value ushr 7) and 0x7f).toByte(),
        (value and 0x7f).toByte(),
    )
}
