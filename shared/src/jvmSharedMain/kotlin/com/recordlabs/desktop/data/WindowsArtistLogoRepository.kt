package com.recordlabs.desktop.data

import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.client.statement.readRawBytes
import io.ktor.http.isSuccess
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.text.Normalizer

/**
 * Artist name logos, as shown on Apple Music artist pages since iOS 27: a
 * transparent PNG of the artist's name in their own lettering, uploaded by the
 * artist or label ("musicContentColorLogoTrimmed" in Apple Music's editorial
 * artwork). Artists without one get null, and the app keeps its normal font.
 *
 * Lookups are cached on disk: a found logo for 30 days, "no logo" for 3 days
 * (artists can add one later). Network errors are not remembered.
 */
object WindowsArtistLogoRepository {
    private const val FOUND_TTL_MS = 30L * 24 * 60 * 60 * 1000
    private const val MISSING_TTL_MS = 3L * 24 * 60 * 60 * 1000
    private const val LOGO_WIDTH = 1200
    private const val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36"

    @Serializable
    private data class Entry(val artistId: String? = null, val file: String? = null, val checkedAt: Long = 0L)

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val client = HttpClient {
        install(HttpTimeout) {
            requestTimeoutMillis = 15_000
            connectTimeoutMillis = 10_000
            socketTimeoutMillis = 15_000
        }
    }
    private val directory: File by lazy { File(WindowsStorage.directory, "artist-logos").apply { mkdirs() } }
    private val indexFile: File by lazy { File(directory, "index.json") }
    private val mutex = Mutex()
    private var index: MutableMap<String, Entry>? = null

    /** The logo picture file for [artistName], or null when the artist has none. Throws on network errors. */
    suspend fun logoFile(artistName: String): File? = withContext(Dispatchers.IO) {
        val key = artistName.nameKey()
        if (key.isBlank()) return@withContext null
        mutex.withLock {
            val entries = index ?: loadIndex().also { index = it }
            val now = System.currentTimeMillis()
            entries[key]?.let { entry ->
                val file = entry.file?.let { File(directory, it) }?.takeIf { it.isFile && it.length() > 0L }
                val fresh = now - entry.checkedAt < if (entry.file != null) FOUND_TTL_MS else MISSING_TTL_MS
                if (fresh && (entry.file == null || file != null)) return@withContext file
            }
            // A network error is thrown to the caller (which may try again) and not remembered.
            val found = lookUp(artistName, key)
            entries[key] = found?.let { Entry(it.first, it.second.name, now) } ?: Entry(checkedAt = now)
            saveIndex(entries)
            found?.second
        }
    }

    /** (Apple Music artist id, saved logo) — or null when the artist has no logo. Throws on network errors. */
    private suspend fun lookUp(artistName: String, key: String): Pair<String, File>? {
        val search = appleGet("/v1/catalog/us/search") {
            parameter("term", artistName)
            parameter("types", "artists")
            parameter("limit", 10)
        }
        val artists = search["results"]?.jsonObject?.get("artists")?.jsonObject?.get("data")?.jsonArray.orEmpty()
        // Only the artist with exactly this name: a logo must never land on
        // someone else's page.
        val artistId = artists.firstNotNullOfOrNull { item ->
            val artist = item.jsonObject
            val name = artist["attributes"]?.jsonObject?.get("name")?.jsonPrimitive?.contentOrNull.orEmpty()
            artist["id"]?.jsonPrimitive?.contentOrNull?.takeIf { name.nameKey() == key }
        } ?: return null
        val details = appleGet("/v1/catalog/us/artists/$artistId") { parameter("extend", "editorialArtwork") }
        val logo = details["data"]?.jsonArray?.firstOrNull()?.jsonObject
            ?.get("attributes")?.jsonObject
            ?.get("editorialArtwork")?.jsonObject
            ?.get("musicContentColorLogoTrimmed")?.jsonObject
            ?: return null
        val template = logo["url"]?.jsonPrimitive?.contentOrNull ?: return null
        val width = logo["width"]?.jsonPrimitive?.intOrNull?.takeIf { it > 0 } ?: return null
        val height = logo["height"]?.jsonPrimitive?.intOrNull?.takeIf { it > 0 } ?: return null
        val targetWidth = minOf(LOGO_WIDTH, width)
        val targetHeight = (targetWidth.toLong() * height / width).toInt().coerceAtLeast(1)
        // PNG keeps the logo's transparency ("bb.jpg" would give it a background).
        val url = template.replace("{w}", targetWidth.toString()).replace("{h}", targetHeight.toString())
            .replace(Regex("bb\\.(jpg|jpeg|webp)$"), "bb.png")
        val response: HttpResponse = client.get(url) { header("User-Agent", USER_AGENT) }
        if (!response.status.isSuccess()) error("Logo download failed: ${response.status}")
        val bytes = response.readRawBytes()
        if (bytes.size < 64) error("Logo download was empty")
        val file = File(directory, "$artistId.png")
        val temp = File(directory, "$artistId.png.tmp")
        temp.writeBytes(bytes)
        if (!temp.renameTo(file)) { file.writeBytes(bytes); temp.delete() }
        return artistId to file
    }

    private suspend fun appleGet(path: String, extra: io.ktor.client.request.HttpRequestBuilder.() -> Unit): JsonObject {
        suspend fun request(token: String): HttpResponse = client.get("https://amp-api.music.apple.com$path") {
            parameter("l", "en-US")
            parameter("platform", "web")
            extra()
            header("Authorization", "Bearer $token")
            header("Origin", "https://music.apple.com")
            header("Referer", "https://music.apple.com/")
            header("User-Agent", USER_AGENT)
        }
        var response = request(WindowsLyricsProviderRegistry.appleMusicWebToken())
        // An expired web token: fetch a new one once.
        if (response.status.value == 401 || response.status.value == 403) {
            response = request(WindowsLyricsProviderRegistry.appleMusicWebToken(refresh = true))
        }
        if (!response.status.isSuccess()) error("Apple Music request failed: ${response.status}")
        return json.parseToJsonElement(response.bodyAsText()).jsonObject
    }

    private fun String.nameKey(): String = Normalizer.normalize(this, Normalizer.Form.NFKC)
        .lowercase()
        .replace("&", "and")
        .replace(Regex("[^\\p{L}\\p{N}]+"), "")

    private fun loadIndex(): MutableMap<String, Entry> = runCatching {
        if (indexFile.isFile) json.decodeFromString<Map<String, Entry>>(indexFile.readText()).toMutableMap() else mutableMapOf()
    }.getOrDefault(mutableMapOf())

    private fun saveIndex(entries: Map<String, Entry>) {
        runCatching {
            val temp = File(directory, "index.json.tmp")
            temp.writeText(json.encodeToString(entries))
            if (!temp.renameTo(indexFile)) { indexFile.writeText(temp.readText()); temp.delete() }
        }
    }
}
