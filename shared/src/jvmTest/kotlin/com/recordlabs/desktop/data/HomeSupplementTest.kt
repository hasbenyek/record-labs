package com.recordlabs.desktop.data

import kotlinx.coroutines.runBlocking
import org.junit.Test
import kotlin.test.assertTrue

class HomeSupplementTest {
    @Test
    fun testHomeSupplement() {
        runBlocking {
        val repo = WindowsMusicRepository()
        val supplement = repo.homeSupplement()
        println("Supplement shelves count: ${supplement.size}")
        for (shelf in supplement) {
            println(" - Shelf: ${shelf.title} (items: ${shelf.items.size})")
        }
        assertTrue(supplement.isNotEmpty(), "Supplement should not be empty")
    }
}
}
