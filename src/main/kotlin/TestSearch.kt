import com.recordlabs.desktop.data.*
import kotlinx.coroutines.runBlocking

suspend fun main() {
    val repo = WindowsMusicRepository()
    val res = repo.search("Ariana Grande", "artists")
    println("ARTISTS COUNT: " + res.artists.size)
    res.artists.take(5).forEach {
        println(it.title + " | " + it.browseId)
    }
}
