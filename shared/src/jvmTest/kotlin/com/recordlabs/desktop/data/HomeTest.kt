package com.recordlabs.desktop.data

import kotlinx.coroutines.runBlocking
import org.junit.Test
import kotlin.test.assertTrue

class HomeTest {
    @Test
    fun testHome() {
        runBlocking {
        val repo = WindowsMusicRepository()
        val feed = repo.home()
        println("Home feed shelves count: ${feed.shelves.size}")
        for (shelf in feed.shelves) {
            println(" - Shelf: ${shelf.title} (items: ${shelf.items.size})")
        }
        assertTrue(feed.shelves.isNotEmpty(), "Canonical YouTube Music Home must contain at least one shelf")
        assertTrue(feed.shelves.all { it.items.isNotEmpty() }, "Home shelves must not be empty")
        
        feed.continuation?.let { token ->
            println("Loading continuation...")
            val more = repo.homeContinuation(token)
            println("Continuation shelves count: ${more.shelves.size}")
            for (shelf in more.shelves) {
                println(" - Continuation Shelf: ${shelf.title} (items: ${shelf.items.size})")
            }
        }
    }
}
}
