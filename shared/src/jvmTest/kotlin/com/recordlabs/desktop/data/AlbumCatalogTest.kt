package com.recordlabs.desktop.data

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class AlbumCatalogTest {
    @Test
    fun nicoleAlbumResolvesToOfficialTwelveTrackRelease() = runBlocking {
        WindowsMusicRepository().use { repository ->
            val album = repository.search("Nicole NIKI", "Albums").albums
                .firstOrNull { it.title.equals("Nicole", ignoreCase = true) }
            assertNotNull(album)
            assertTrue(
                album.browseId.startsWith("MPRE", true) || album.browseId.startsWith("OLAK5uy_", true),
                "Unexpected album browse id: " + album.browseId,
            )

            val page = repository.catalogPage(album.browseId, "Album")
            assertTrue(page.title.equals("Nicole", ignoreCase = true), "Unexpected album title: " + page.title)
            assertTrue(page.tracks.size >= 12, "Expected the full album, got " + page.tracks.size + " tracks")
            assertTrue(page.artworkUrl?.isNotBlank() == true)
        }
    }
}
