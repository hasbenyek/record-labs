package test_repo

import kotlinx.coroutines.runBlocking
import com.recordlabs.desktop.data.*

fun main() = runBlocking {
    try {
        val repo = WindowsMusicRepository()
        println("Fetching...")
        val page = repo.catalogPage("MPADUCPC0L1d253x-KuMNwa05TpA", "Shelf", null)
        println("Title: " + page.title)
        println("Albums size: " + page.albums.size)
        println("Tracks size: " + page.tracks.size)
        println("Shelves size: " + page.shelves.size)
    } catch (e: Exception) {
        e.printStackTrace()
    }
}
