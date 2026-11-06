package com.recordlabs.desktop.data

import kotlinx.coroutines.runBlocking
import org.junit.Test
import java.io.File
import kotlin.test.assertTrue

class SearchTest {
    @Test
    fun testSearch() = runBlocking {
        val repo = WindowsMusicRepository()
        val result = repo.search("avenged sevenfold", "All")
        println("Tracks: ${result.tracks.size}")
        println("Summaries: ${result.summaries.size}")
        result.summaries.forEach { summary ->
            println(" - ${summary.title}: ${summary.items.size} items")
            summary.items.forEach { track ->
                println("    * ${track.title} by ${track.artist}")
            }
        }
        assertTrue(result.tracks.size >= 5, "Search must parse flat itemSectionRenderer results, not only Top result")
        assertTrue(result.summaries.any { it.title == "Songs" && it.items.isNotEmpty() })
        assertTrue(result.tracks.none { it.artist.equals("Song", true) || it.artist.equals("Video", true) })
        assertTrue(result.tracks.any { it.artist.contains("Avenged Sevenfold", true) })
    }
}
