package com.recordlabs.desktop.data

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.call.body
import io.ktor.serialization.kotlinx.json.json
import org.junit.Test

class HomeParserTest {
    @Test
    fun testParser() = runBlocking {
        val client = HttpClient(CIO) {
            install(ContentNegotiation) {
                json(Json { ignoreUnknownKeys = true; isLenient = true })
            }
        }
        
        val response = client.post("https://music.youtube.com/youtubei/v1/browse?key=AIzaSyC9XL3ZjWddXya6X74dJoCTL-WEYFDNX3") {
            setBody("""{"context":{"client":{"clientName":"WEB_REMIX","clientVersion":"1.20260806.01.00","hl":"en","gl":"US"}},"browseId":"FEmusic_home"}""")
            headers.append("Content-Type", "application/json")
            headers.append("Origin", "https://music.youtube.com")
        }.body<JsonObject>()
        
        fun JsonElement.findObjects(key: String): List<JsonObject> = when (this) {
            is JsonObject -> buildList {
                val obj = this@findObjects
                obj[key]?.let { value -> if (value is JsonObject) add(value) }
                obj.values.forEach { addAll(it.findObjects(key)) }
            }
            is JsonArray -> flatMap { it.findObjects(key) }
            else -> emptyList()
        }
        
        fun JsonElement.findString(key: String): String? = when (this) {
            is JsonObject -> this@findString[key]?.jsonPrimitive?.contentOrNull
                ?: values.firstNotNullOfOrNull { it.findString(key) }
            is JsonArray -> firstNotNullOfOrNull { it.findString(key) }
            else -> null
        }

        val carousels = response.findObjects("musicCarouselShelfRenderer")
        println("Found ${carousels.size} carousels")
        
        carousels.forEach { carousel ->
            val header = carousel["header"]
            val expectedTitle = header?.findObjects("musicCarouselShelfBasicHeaderRenderer")?.firstOrNull()?.findObjects("title")?.firstOrNull()?.findString("text")
            val foundTitle = header?.findString("text")
            println("Carousel: foundTitle='${foundTitle}', expectedTitle='${expectedTitle}'")
        }
    }
}
