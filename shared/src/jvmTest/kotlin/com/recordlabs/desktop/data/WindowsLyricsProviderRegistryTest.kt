package com.recordlabs.desktop.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WindowsLyricsProviderRegistryTest {
    @Test
    fun `word timed paxsenix lyrics outrank line synced lyrics`() {
        val lineSynced = "[00:01.00]First line\n[00:03.00]Second line"
        val wordSynced = "[00:01.00]First line\n<First:1.0:1.4|line:1.4:2.0>"

        assertTrue(
            WindowsLyricsProviderRegistry.lyricsTimingPriority(wordSynced) >
                WindowsLyricsProviderRegistry.lyricsTimingPriority(lineSynced),
        )
    }

    @Test
    fun `non latin title queries latin alias first and original as fallback`() {
        val variants = WindowsLyricsProviderRegistry.lyricsQueryVariants(
            Track(id = "song", title = "夜に駆ける (Racing Into the Night)", artist = "YOASOBI"),
        )

        assertEquals("Racing Into the Night", variants.first().title)
        assertEquals("YOASOBI", variants.first().artist)
        assertTrue(variants.drop(1).any { it.title == "夜に駆ける (Racing Into the Night)" })
    }

    @Test
    fun `latin metadata keeps a single query`() {
        val variants = WindowsLyricsProviderRegistry.lyricsQueryVariants(
            Track(id = "song", title = "Poker Face", artist = "Lady Gaga"),
        )

        assertEquals(1, variants.size)
    }

    @Test
    fun `same song title by another artist is rejected`() {
        assertFalse(
            WindowsLyricsProviderRegistry.artistsReferToSameArtist(
                expected = "Adele",
                candidate = "Bob Dylan",
            ),
        )
        assertFalse(
            WindowsLyricsProviderRegistry.artistsReferToSameArtist(
                expected = "Young Miko",
                candidate = "Young Thug",
            ),
        )
    }

    @Test
    fun `candidate may append a featured artist without losing the primary match`() {
        assertTrue(
            WindowsLyricsProviderRegistry.artistsReferToSameArtist(
                expected = "Lady Gaga",
                candidate = "Lady Gaga & Bradley Cooper",
            ),
        )
        assertTrue(
            WindowsLyricsProviderRegistry.artistsReferToSameArtist(
                expected = "Beyoncé",
                candidate = "Beyonce feat. Jay-Z",
            ),
        )
    }
}
