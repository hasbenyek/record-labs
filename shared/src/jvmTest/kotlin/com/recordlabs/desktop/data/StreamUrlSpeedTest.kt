package com.recordlabs.desktop.data

import kotlinx.coroutines.runBlocking
import org.junit.Test
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType

class StreamUrlSpeedTest {
    @Test
    fun testTVHTML5SimplyEmbeddedPlayer() = runBlocking {
        val videoId = "0e3GPea1Tyg" // "watch" track or Billie Eilish - watch

        val client = HttpClient(CIO) {
            install(ContentNegotiation) {
                json(Json { ignoreUnknownKeys = true; isLenient = true })
            }
        }

        val jsonPayload = """
            {
              "context": {
                "client": {
                  "clientName": "TVHTML5_SIMPLY_EMBEDDED_PLAYER",
                  "clientVersion": "2.0",
                  "hl": "en",
                  "gl": "US"
                }
              },
              "videoId": "$videoId",
              "contentCheckOk": true,
              "racyCheckOk": true
            }
        """.trimIndent()

        val t1 = System.currentTimeMillis()
        val resp = client.post("https://www.youtube.com/youtubei/v1/player?key=$API_KEY") {
            contentType(ContentType.Application.Json)
            setBody(jsonPayload)
        }

        val bodyText = resp.bodyAsText()
        val jsonRoot = Json.parseToJsonElement(bodyText)

        fun JsonElement.findArrays(key: String): List<JsonArray> = when (this) {
            is JsonObject -> entries.flatMap { (k, v) -> if (k == key && v is JsonArray) listOf(v) else v.findArrays(key) }
            is JsonArray -> flatMap { it.findArrays(key) }
            else -> emptyList()
        }

        val directUrl = jsonRoot.findArrays("adaptiveFormats").asSequence()
            .flatMap { it.asSequence() }
            .filterIsInstance<JsonObject>()
            .filter { it["mimeType"]?.jsonPrimitive?.contentOrNull?.startsWith("audio/") == true }
            .sortedByDescending { it["bitrate"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 0 }
            .mapNotNull { it["url"]?.jsonPrimitive?.contentOrNull }
            .firstOrNull()

        val t2 = System.currentTimeMillis()
        println("TVHTML5_SIMPLY_EMBEDDED_PLAYER took ${t2 - t1}ms | Direct URL found: ${directUrl != null}")
        if (directUrl != null) println("URL: ${directUrl.take(100)}...")
    }

    companion object {
        private const val API_KEY = "AIzaSyC05de5b..."
    }
}
