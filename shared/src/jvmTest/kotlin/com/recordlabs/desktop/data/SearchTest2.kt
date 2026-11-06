package com.recordlabs.desktop.data

import kotlinx.coroutines.runBlocking
import org.junit.Test
import kotlin.test.assertTrue

class SearchTest2 {
    @Test
    fun testSearchAll() = runBlocking {
        val repo = WindowsMusicRepository()
        val results = repo.search("Avenged Sevenfold", filter = null)
        println("Search results for 'All':")
        println("Tracks count: ${results.tracks.size}")
        println("Summaries count: ${results.summaries.size}")
        for (summary in results.summaries) {
            println(" - Summary: ${summary.title} (items: ${summary.items.size})")
        }
        for (track in results.tracks.take(5)) {
            println(" - Track: ${track.title} by ${track.artist}")
        }
    }
}
