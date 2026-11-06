import com.recordlabs.desktop.data.*
import kotlinx.coroutines.runBlocking
import java.io.File

fun main() = runBlocking {
    val repo = WindowsMusicRepository()
    try {
        println("Testing search...")
        val method = repo.javaClass.getDeclaredMethod("client")
        method.isAccessible = true
        // Actually, we can just use the public method to see if results are parsed properly.
        val results = repo.search("Ed Sheeran")
        println("Tracks count: ${results.tracks.size}")
        println("Summaries count: ${results.summaries.size}")
    } finally {
        repo.close()
    }
}
