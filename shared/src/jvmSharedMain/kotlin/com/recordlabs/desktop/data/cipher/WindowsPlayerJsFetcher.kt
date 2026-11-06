package com.recordlabs.desktop.data.cipher

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object WindowsPlayerJsFetcher {
    private val client = HttpClient(CIO)
    private var cachedPlayerJs: Pair<String, String>? = null

    suspend fun getPlayerJs(): Pair<String, String>? = withContext(Dispatchers.IO) {
        cachedPlayerJs?.let { return@withContext it }
        
        try {
            val watchHtml = client.get("https://www.youtube.com/watch?v=dQw4w9WgXcQ") {
                header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36")
            }.bodyAsText()

            val playerJsUrlMatch = Regex("""\"[^\"]+?/(player_ias[^\"/]+?\.js)\"""").find(watchHtml)
                ?: Regex("""/s/player/[^\"]+?\.js""").find(watchHtml)
                ?: return@withContext null
            
            val url = playerJsUrlMatch.value.replace("\"", "")
            val fullUrl = if (url.startsWith("http")) url else "https://www.youtube.com$url"
            
            val js = client.get(fullUrl).bodyAsText()
            val hash = hashString(js)
            val result = js to hash
            cachedPlayerJs = result
            return@withContext result
        } catch (e: Exception) {
            println("ERROR fetch: $e")
            return@withContext null
        }
    }
    
    private fun hashString(input: String): String {
        return MessageDigest.getInstance("SHA-256")
            .digest(input.toByteArray())
            .joinToString("") { "%02x".format(it) }
            .take(8)
    }
}
