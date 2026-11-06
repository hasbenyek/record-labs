package com.recordlabs.desktop.data

import androidx.compose.ui.graphics.Color
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Cache
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Player color extraction system matching Android's PlayerColorExtractor.
 * Extracts dominant vibrant colors from album artwork to dynamically theme the app UI.
 *
 * Uses OkHttp with disk cache for artwork download (connection pooling + HTTP caching)
 * and a persistent disk cache for extracted colors so we never re-download or
 * re-process the same artwork after an app restart.
 */
object PlayerColorExtractor {
    private val DEFAULT_ACCENT = Color(0xFFFFFFFF)

    // In-memory LRU cache: artworkUrl → extracted Color
    private val memoryCache = LinkedHashMap<String, Color>(64, 0.75f, true)

    // Disk cache dir for extracted colors: avoids re-downloading + re-processing on restart
    private val colorDiskCacheDir: File by lazy {
        // v2: dominant colour by hue (the earlier cache also kept the fallback after a failed download).
        File(WindowsStorage.directory, "color-cache-v2").apply { mkdirs() }
    }

    // OkHttp client with response disk cache for image downloads.
    // Reuses connections (connection pooling) and respects HTTP cache headers
    // so the same artwork URL is never downloaded more than once per session.
    private val httpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(12, TimeUnit.SECONDS)
            .cache(Cache(File(WindowsStorage.directory, "artwork-http-cache"), 50L * 1024 * 1024))
            .build()
    }

    fun diskCacheBytes(): Long = listOf(File(WindowsStorage.directory, "artwork-http-cache"), colorDiskCacheDir)
        .sumOf { dir -> dir.walkTopDown().filter(File::isFile).sumOf(File::length) }

    /** Settings > Storage > Clear image cache (downloaded artwork and the saved accent colours). */
    fun clearDiskCaches() {
        runCatching { httpClient.cache?.evictAll() }
        colorDiskCacheDir.listFiles()?.forEach { runCatching { it.delete() } }
    }

    suspend fun extractAccentColor(artworkUrl: String?): Color = withContext(Dispatchers.IO) {
        if (artworkUrl.isNullOrBlank()) return@withContext DEFAULT_ACCENT

        // 1. Memory cache hit — instant, no IO
        synchronized(memoryCache) {
            memoryCache[artworkUrl]?.let { return@withContext it }
        }

        // 2. Disk color-cache hit — fast (read one tiny file, no network)
        val diskCacheFile = colorDiskCacheFile(artworkUrl)
        runCatching {
            diskCacheFile.readText().trim().toIntOrNull()?.let { packed ->
                // Validate alpha channel — old cache entries stored as Long may have lost alpha bits
                val alpha = (packed ushr 24) and 0xFF
                if (alpha >= 0xE0) {
                    val color = Color(packed)
                    putMemoryCache(artworkUrl, color)
                    return@withContext color
                }
            }
        }

        // 3. Download via OkHttp (connection-pooled, HTTP-cached) + extract OR read local file
        val color = runCatching {
            val localFile = WindowsLocalMusicLibrary.parseLocalArtworkFile(artworkUrl)
            val img = if (localFile != null && localFile.isFile) {
                decodeRgbThumbnail(localFile.readBytes(), THUMBNAIL_SIZE)
            } else {
                val request = Request.Builder()
                    .url(artworkUrl)
                    .header("User-Agent", "RecordLabs-Windows/0.1.7")
                    .build()
                val bytes = httpClient.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) return@runCatching null
                    response.body?.bytes() ?: return@runCatching null
                }
                decodeRgbThumbnail(bytes, THUMBNAIL_SIZE)
            } ?: return@runCatching null
            extractVibrantColor(img)
        }.getOrNull() ?: return@withContext DEFAULT_ACCENT // not remembered: tried again next time

        // Persist as ARGB int (guaranteed to round-trip without losing alpha)
        val argb = ((color.alpha * 255).toInt() shl 24) or
            ((color.red * 255).toInt() shl 16) or
            ((color.green * 255).toInt() shl 8) or
            (color.blue * 255).toInt()
        runCatching { diskCacheFile.writeText(argb.toString()) }
        putMemoryCache(artworkUrl, color)
        color
    }

    /**
     * The artwork's dominant colour: pixels are grouped by hue (grays, near
     * black and near white left out), the hue covering most of the picture
     * (colourful pixels count a little more) wins, and its average colour is
     * made a bit more vivid.
     */
    private const val THUMBNAIL_SIZE = 48

    private fun extractVibrantColor(pixels: IntArray): Color {
        val size = THUMBNAIL_SIZE

        val bins = 24
        val weight = FloatArray(bins)
        val sumR = FloatArray(bins)
        val sumG = FloatArray(bins)
        val sumB = FloatArray(bins)
        val hsb = FloatArray(3)
        for (y in 0 until size) {
            for (x in 0 until size) {
                val rgb = pixels[y * size + x]
                val r = (rgb shr 16) and 0xFF
                val gr = (rgb shr 8) and 0xFF
                val b = rgb and 0xFF
                rgbToHsb(r, gr, b, hsb)
                if (hsb[1] < 0.18f || hsb[2] < 0.18f || hsb[2] > 0.97f) continue
                val bin = ((hsb[0] * bins).toInt()).coerceIn(0, bins - 1)
                val w = 0.4f + hsb[1]
                weight[bin] += w
                sumR[bin] += r * w
                sumG[bin] += gr * w
                sumB[bin] += b * w
            }
        }
        // Neighbouring hues count half, so a hue split over two bins is not lost.
        val best = (0 until bins).maxByOrNull { weight[it] + 0.5f * (weight[(it + 1) % bins] + weight[(it + bins - 1) % bins]) }
            ?: return DEFAULT_ACCENT
        // Barely any colour (black-and-white artwork): keep the default accent.
        if (weight[best] < size * size * 0.02f) return DEFAULT_ACCENT
        val r = (sumR[best] / weight[best]).toInt().coerceIn(0, 255)
        val gr = (sumG[best] / weight[best]).toInt().coerceIn(0, 255)
        val b = (sumB[best] / weight[best]).toInt().coerceIn(0, 255)
        rgbToHsb(r, gr, b, hsb)
        val enhancedSat = (hsb[1] * 1.25f).coerceIn(0.35f, 1.0f)
        val enhancedBri = hsb[2].coerceIn(0.50f, 0.90f)
        return Color(hsbToRgb(hsb[0], enhancedSat, enhancedBri))
    }

    private fun colorDiskCacheFile(artworkUrl: String): File =
        colorDiskCacheDir.resolve("${artworkUrl.hashCode().toUInt().toString(16)}.color")

    private fun putMemoryCache(artworkUrl: String, color: Color) {
        synchronized(memoryCache) {
            memoryCache[artworkUrl] = color
            while (memoryCache.size > 64) {
                val eldestKey = memoryCache.keys.firstOrNull() ?: break
                memoryCache.remove(eldestKey)
            }
        }
    }
}

/** The picture scaled to [size] x [size] RGB pixels (row by row), or null when it can't be decoded. */
internal expect fun decodeRgbThumbnail(bytes: ByteArray, size: Int): IntArray?

/** java.awt.Color.RGBtoHSB, available on every platform. */
internal fun rgbToHsb(r: Int, g: Int, b: Int, hsb: FloatArray) {
    val cmax = maxOf(r, g, b)
    val cmin = minOf(r, g, b)
    val brightness = cmax / 255f
    val saturation = if (cmax != 0) (cmax - cmin).toFloat() / cmax else 0f
    var hue: Float
    if (saturation == 0f) {
        hue = 0f
    } else {
        val redc = (cmax - r).toFloat() / (cmax - cmin)
        val greenc = (cmax - g).toFloat() / (cmax - cmin)
        val bluec = (cmax - b).toFloat() / (cmax - cmin)
        hue = when {
            r == cmax -> bluec - greenc
            g == cmax -> 2f + redc - bluec
            else -> 4f + greenc - redc
        }
        hue /= 6f
        if (hue < 0) hue += 1f
    }
    hsb[0] = hue
    hsb[1] = saturation
    hsb[2] = brightness
}

/** java.awt.Color.HSBtoRGB (opaque ARGB), available on every platform. */
internal fun hsbToRgb(hue: Float, saturation: Float, brightness: Float): Int {
    var r = 0; var g = 0; var b = 0
    if (saturation == 0f) {
        r = (brightness * 255f + 0.5f).toInt(); g = r; b = r
    } else {
        val h = (hue - kotlin.math.floor(hue)) * 6f
        val f = h - kotlin.math.floor(h)
        val p = brightness * (1f - saturation)
        val q = brightness * (1f - saturation * f)
        val t = brightness * (1f - saturation * (1f - f))
        when (h.toInt()) {
            0 -> { r = (brightness * 255f + 0.5f).toInt(); g = (t * 255f + 0.5f).toInt(); b = (p * 255f + 0.5f).toInt() }
            1 -> { r = (q * 255f + 0.5f).toInt(); g = (brightness * 255f + 0.5f).toInt(); b = (p * 255f + 0.5f).toInt() }
            2 -> { r = (p * 255f + 0.5f).toInt(); g = (brightness * 255f + 0.5f).toInt(); b = (t * 255f + 0.5f).toInt() }
            3 -> { r = (p * 255f + 0.5f).toInt(); g = (q * 255f + 0.5f).toInt(); b = (brightness * 255f + 0.5f).toInt() }
            4 -> { r = (t * 255f + 0.5f).toInt(); g = (p * 255f + 0.5f).toInt(); b = (brightness * 255f + 0.5f).toInt() }
            5 -> { r = (brightness * 255f + 0.5f).toInt(); g = (p * 255f + 0.5f).toInt(); b = (q * 255f + 0.5f).toInt() }
        }
    }
    return (0xff shl 24) or (r shl 16) or (g shl 8) or b
}
