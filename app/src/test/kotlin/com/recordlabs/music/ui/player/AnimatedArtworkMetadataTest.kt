package com.recordlabs.music.ui.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AnimatedArtworkMetadataTest {
    @Test
    fun parsesOfficialAppleAlbumArtworkPage() {
        val page = parseOfficialAppleArtworkPage(
            """
            <h1 data-testid="non-editable-product-title">
              <span dir="auto">This Is How Tomorrow Moves</span>
            </h1>
            <div data-testid="product-subtitles">
              <a href="/artist/beabadoobee/1307650474">beabadoobee</a>
            </div>
            <amp-ambient-video
              src="https://mvod.itunes.apple.com/path/P855322150_default.m3u8">
            </amp-ambient-video>
            <amp-ambient-video
              src="https://mvod.itunes.apple.com/path/P855293396_default.m3u8">
            </amp-ambient-video>
            """.trimIndent(),
        )

        assertEquals("This Is How Tomorrow Moves", page?.album)
        assertEquals("beabadoobee", page?.artist)
        assertEquals(
            "https://mvod.itunes.apple.com/path/P855322150_default.m3u8",
            page?.animatedUrls?.firstOrNull(),
        )
        assertEquals(2, page?.animatedUrls?.size)
    }

    @Test
    fun selectsLargestCompatibleAvcPlaylist() {
        val selected = selectCompatibleAppleAvcPlaylist(
            """
            #EXTM3U
            #EXT-X-STREAM-INF:CODECS="avc1.64001f",RESOLUTION=486x648
            https://mvod.itunes.apple.com/486.m3u8
            #EXT-X-STREAM-INF:CODECS="avc1.640028",RESOLUTION=1078x1438
            https://mvod.itunes.apple.com/1078.m3u8
            #EXT-X-STREAM-INF:CODECS="hvc1.2.4.L150.B0",RESOLUTION=2048x2732
            https://mvod.itunes.apple.com/hevc.m3u8
            """.trimIndent(),
        )

        assertEquals("https://mvod.itunes.apple.com/1078.m3u8", selected)
    }

    @Test
    fun selectsSquareHevcPlaylistWithoutAvcFallback() {
        val selected = selectCompatibleAppleHevcPlaylist(
            """
            #EXTM3U
            #EXT-X-STREAM-INF:CODECS="avc1.640028",RESOLUTION=1080x1080
            https://mvod.itunes.apple.com/wrong-avc.m3u8
            #EXT-X-STREAM-INF:CODECS="hvc1.2.20000000.L123.B0",RESOLUTION=768x768
            https://mvod.itunes.apple.com/hevc-768.m3u8
            #EXT-X-STREAM-INF:CODECS="hvc1.2.20000000.L123.B0",RESOLUTION=1080x1080
            https://mvod.itunes.apple.com/hevc-1080.m3u8
            #EXT-X-STREAM-INF:CODECS="hvc1.2.20000000.H150.B0",RESOLUTION=2160x2160
            https://mvod.itunes.apple.com/hevc-2160.m3u8
            """.trimIndent(),
        )

        assertEquals("https://mvod.itunes.apple.com/hevc-1080.m3u8", selected)
    }

    @Test
    fun extractsMp4FromAppleMediaPlaylist() {
        assertEquals(
            "P855293396_Anull_video_gr280_sdr_1078x1438-.mp4",
            firstAppleMp4Path(
                """
                #EXTM3U
                #EXT-X-MAP:URI="P855293396-init.mp4"
                #EXT-X-BYTERANGE:2997446@899
                P855293396_Anull_video_gr280_sdr_1078x1438-.mp4
                """.trimIndent(),
            ),
        )
    }

    @Test
    fun lookupUsesCurrentBoiDuAlbumAndDurationParameters() {
        val url = animatedArtworkLookupUrl(
            song = "Beaches",
            artist = "beabadoobee",
            durationSeconds = 230,
            album = "This Is How Tomorrow Moves",
        )

        assertEquals("Beaches", url.queryParameter("s"))
        assertEquals("beabadoobee", url.queryParameter("a"))
        assertEquals("This Is How Tomorrow Moves", url.queryParameter("albumName"))
        assertEquals("230", url.queryParameter("duration"))
        assertEquals(null, url.queryParameter("al"))
        assertEquals(null, url.queryParameter("d"))
    }

    @Test
    fun acceptsExactBeachesMetadata() {
        assertTrue(
            animatedArtworkMetadataMatches(
                expectedSong = "Beaches",
                expectedArtist = "beabadoobee",
                actualSong = "Beaches",
                actualArtist = "beabadoobee",
            ),
        )
    }

    @Test
    fun acceptsHarmlessTitleAndArtistQualifiers() {
        assertTrue(
            animatedArtworkMetadataMatches(
                expectedSong = "Beaches",
                expectedArtist = "beabadoobee",
                actualSong = "Beaches (Remastered)",
                actualArtist = "beabadoobee & Guest Artist",
            ),
        )
    }

    @Test
    fun rejectsFunArtworkForBeaches() {
        assertFalse(
            animatedArtworkMetadataMatches(
                expectedSong = "Beaches",
                expectedArtist = "beabadoobee",
                actualSong = "F.U.N.",
                actualArtist = "Slum Village",
            ),
        )
    }

    @Test
    fun rejectsBlankUpstreamMetadata() {
        assertFalse(
            animatedArtworkMetadataMatches(
                expectedSong = "Beaches",
                expectedArtist = "beabadoobee",
                actualSong = "",
                actualArtist = "",
            ),
        )
    }
}
