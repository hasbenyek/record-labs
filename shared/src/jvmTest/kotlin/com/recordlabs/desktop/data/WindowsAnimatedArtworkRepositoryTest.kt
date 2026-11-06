package com.recordlabs.desktop.data

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class WindowsAnimatedArtworkRepositoryTest {

    @Test
    fun cleanVideoNoiseStripsYouTubeMediaNoiseWhilePreservingVersionTags() {
        assertEquals(
            "Red (Taylor's Version)",
            WindowsAnimatedArtworkRepository.cleanVideoNoise("Red (Taylor's Version) (Official Music Video)")
        )
        assertEquals(
            "Red (Taylor's Version)",
            WindowsAnimatedArtworkRepository.cleanVideoNoise("Red (Taylor's Version) [Official Audio]")
        )
        assertEquals(
            "All Too Well (10 Minute Version) (Taylor's Version)",
            WindowsAnimatedArtworkRepository.cleanVideoNoise("All Too Well (10 Minute Version) (Taylor's Version) (Lyric Video)")
        )
        assertEquals(
            "Ride",
            WindowsAnimatedArtworkRepository.cleanVideoNoise("Ride (Official Video)")
        )
        assertEquals(
            "Anti-Hero",
            WindowsAnimatedArtworkRepository.cleanVideoNoise("Anti-Hero (Official Music Video)")
        )
    }

    @Test
    fun cleanMetadataStringPreservesTaylorsVersion() {
        assertEquals(
            "Red (Taylor's Version)",
            WindowsAnimatedArtworkRepository.cleanMetadataString("Red (Taylor's Version)")
        )
        assertEquals(
            "Bohemian Rhapsody",
            WindowsAnimatedArtworkRepository.cleanMetadataString("Bohemian Rhapsody (2011 Remaster)")
        )
        assertEquals(
            "Levitating",
            WindowsAnimatedArtworkRepository.cleanMetadataString("Levitating (feat. DaBaby)")
        )
        assertEquals(
            "eternal sunshine",
            WindowsAnimatedArtworkRepository.cleanMetadataString("eternal sunshine (slightly deluxe)")
        )
    }

    @Test
    fun extractVersionTagsDetectsDistinctiveSongVersions() {
        assertTrue(WindowsAnimatedArtworkRepository.extractVersionTags("Red (Taylor's Version)").contains("taylor's version"))
        assertTrue(WindowsAnimatedArtworkRepository.extractVersionTags("Red (Taylor’s Version)").contains("taylor's version"))
        assertTrue(WindowsAnimatedArtworkRepository.extractVersionTags("Red").isEmpty())
        assertTrue(WindowsAnimatedArtworkRepository.extractVersionTags("Hotel California (Live)").contains("live"))
        assertTrue(WindowsAnimatedArtworkRepository.extractVersionTags("Levitating (Remix)").contains("remix"))
        assertTrue(WindowsAnimatedArtworkRepository.extractVersionTags("Shape of You (Acoustic)").contains("acoustic"))
        assertTrue(WindowsAnimatedArtworkRepository.extractVersionTags("eternal sunshine (a cappella)").contains("a cappella"))
        assertTrue(WindowsAnimatedArtworkRepository.extractVersionTags("eternal sunshine (a capella version)").contains("a cappella"))
        assertTrue(WindowsAnimatedArtworkRepository.extractVersionTags("eternal sunshine (acappella)").contains("a cappella"))
        assertTrue(WindowsAnimatedArtworkRepository.extractVersionTags("eternal sunshine (instrumental)").contains("instrumental"))
    }

    @Test
    fun metadataMatchesRejectsMismatchedVersions() {
        // Red (Taylor's Version) vs 2012 Red: MUST NOT MATCH!
        assertFalse(
            WindowsAnimatedArtworkRepository.metadataMatches("Red (Taylor's Version)", "Red"),
            "Expected 'Red (Taylor's Version)' NOT to match original 2012 'Red'"
        )
        assertFalse(
            WindowsAnimatedArtworkRepository.metadataMatches("Red", "Red (Taylor's Version)"),
            "Expected original 'Red' NOT to match 'Red (Taylor's Version)'"
        )

        // Live vs Studio: MUST NOT MATCH!
        assertFalse(
            WindowsAnimatedArtworkRepository.metadataMatches("Hotel California (Live)", "Hotel California")
        )

        // A cappella vs Standard: MUST NOT MATCH!
        assertFalse(
            WindowsAnimatedArtworkRepository.metadataMatches("eternal sunshine", "eternal sunshine (a cappella)"),
            "Expected 'eternal sunshine' NOT to match a cappella"
        )
        assertFalse(
            WindowsAnimatedArtworkRepository.metadataMatches("eternal sunshine", "eternal sunshine (a capella version)")
        )
        assertFalse(
            WindowsAnimatedArtworkRepository.metadataMatches("eternal sunshine", "eternal sunshine (acappella)")
        )
        assertFalse(
            WindowsAnimatedArtworkRepository.metadataMatches("eternal sunshine", "eternal sunshine (instrumental)")
        )

        // Unrelated songs sharing first word: MUST NOT MATCH!
        assertFalse(
            WindowsAnimatedArtworkRepository.metadataMatches("Love Story", "Lover")
        )
        assertFalse(
            WindowsAnimatedArtworkRepository.metadataMatches("You Belong With Me", "You Need To Calm Down")
        )
    }

    @Test
    fun metadataMatchesAcceptsLegitimateMatches() {
        assertTrue(
            WindowsAnimatedArtworkRepository.metadataMatches("Red (Taylor's Version)", "Red (Taylor's Version)")
        )
        assertTrue(
            WindowsAnimatedArtworkRepository.metadataMatches("Red (Taylor's Version)", "Red (Taylor’s Version)")
        )
        assertTrue(
            WindowsAnimatedArtworkRepository.metadataMatches(
                "All Too Well (10 Minute Version) (Taylor's Version)",
                "All Too Well (10 Minute Version) [Taylor's Version] [From The Vault]"
            )
        )
        assertTrue(
            WindowsAnimatedArtworkRepository.metadataMatches("Ride (Official Video)", "Ride")
        )
        assertTrue(
            WindowsAnimatedArtworkRepository.metadataMatches("Anti-Hero (Official Music Video)", "Anti-Hero")
        )
        assertTrue(
            WindowsAnimatedArtworkRepository.metadataMatches("eternal sunshine", "eternal sunshine")
        )
        assertTrue(
            WindowsAnimatedArtworkRepository.metadataMatches(
                "eternal sunshine (a cappella)",
                "eternal sunshine (a capella version)"
            )
        )
    }

    @Test
    fun buildQueryCandidatesPrioritizesExactTaylorsVersion() = runBlocking {
        val track = Track(
            id = "test-taylor-red",
            title = "Red (Taylor's Version)",
            artist = "Taylor Swift",
            album = "",
        )
        val candidates = WindowsAnimatedArtworkRepository.buildQueryCandidates(track)
        assertTrue(candidates.isNotEmpty())

        val firstCandidate = candidates.first()
        assertEquals("Red (Taylor's Version)", firstCandidate.title)
        assertEquals("Taylor Swift", firstCandidate.artist)

        // None of the candidates should strip "Taylor's Version" into a plain "Red" query
        for (candidate in candidates) {
            assertTrue(
                candidate.title.contains("Taylor's Version", ignoreCase = true),
                "Candidate '${candidate.title}' should not have stripped Taylor's Version"
            )
        }
    }

    @Test
    fun buildQueryCandidatesForEternalSunshineSlightlyDeluxeIncludesBaseAlbum() = runBlocking {
        val track = Track(
            id = "test-es-deluxe",
            title = "eternal sunshine",
            artist = "Ariana Grande",
            album = "eternal sunshine (slightly deluxe)",
        )
        val candidates = WindowsAnimatedArtworkRepository.buildQueryCandidates(track)
        assertTrue(candidates.isNotEmpty())

        val albums = candidates.mapNotNull { it.album }
        // Must include the exact album
        assertTrue(albums.any { it.equals("eternal sunshine (slightly deluxe)", ignoreCase = true) })
        // Must include base studio album as immediate fallback
        assertTrue(albums.any { it.equals("eternal sunshine", ignoreCase = true) })
        // Must NOT include derivative albums like a cappella or brighter days ahead
        assertFalse(albums.any { it.contains("a cap", ignoreCase = true) })
        assertFalse(albums.any { it.contains("brighter days", ignoreCase = true) })
    }

    @Test
    fun buildQueryCandidatesRejectsMismatchedACappellaAlbumTag() = runBlocking {
        // Track from YouTube Music with falsely assigned a cappella compilation album
        val track = Track(
            id = "test-ytm-polluted",
            title = "eternal sunshine",
            artist = "Ariana Grande",
            album = "eternal sunshine deluxe: brighter days ahead (a cappella version)",
        )
        val candidates = WindowsAnimatedArtworkRepository.buildQueryCandidates(track)
        assertTrue(candidates.isNotEmpty())

        val albums = candidates.mapNotNull { it.album }
        // Should NOT query with the mismatched a cappella album
        assertFalse(
            albums.any { it.contains("a cap", ignoreCase = true) },
            "Candidates should reject a cappella album when song title is standard vocal"
        )
    }

    @Test
    fun findResolvesCompatibleAvcMp4ForAnimatedArtwork() = runBlocking {
        val track = Track(
            id = "test-nonsense",
            title = "Nonsense",
            artist = "Sabrina Carpenter",
            album = "",
        )
        val artwork = WindowsAnimatedArtworkRepository.find(track)
        if (artwork != null) {
            println("Resolved artwork stream URL: ${artwork.streamUrl}")
            assertTrue(
                artwork.streamUrl.endsWith(".mp4", ignoreCase = true) || artwork.streamUrl.contains(".mp4?"),
                "Expected MP4 URL, got ${artwork.streamUrl}"
            )
            if (!WindowsAnimatedArtworkRepository.supportsHevc) {
                assertTrue(
                    artwork.streamUrl.contains("gr2"),
                    "Expected AVC (gr2xx) stream on system without HEVC, got: ${artwork.streamUrl}"
                )
            } else {
                assertTrue(
                    artwork.streamUrl.contains("gr5") || artwork.streamUrl.contains("gr6") || artwork.streamUrl.contains("gr2"),
                    "Expected high-res stream, got: ${artwork.streamUrl}"
                )
            }
        }
    }

    @Test
    fun testMarkFailedStreamUrlForcesFallback() = runBlocking {
        val track = Track(
            id = "test-track-fallback",
            title = "Fortnight",
            artist = "Taylor Swift",
            album = "",
        )
        val normal = WindowsAnimatedArtworkRepository.find(track)
        if (normal != null) {
            WindowsAnimatedArtworkRepository.markFailedStreamUrl(normal.streamUrl)
            val fallback = WindowsAnimatedArtworkRepository.find(track)
            assertNotNull(fallback)
            assertNotEquals(normal.streamUrl, fallback.streamUrl)
        }
    }
}
