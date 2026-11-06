package com.recordlabs.desktop.data

import com.recordlabs.desktop.platform.*

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.ClientRequestException
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
data class WindowsAnimatedArtwork(
    val streamUrl: String,
    val staticUrl: String?,
)

internal data class ArtworkQueryCandidate(
    val title: String,
    val artist: String,
    val album: String?,
    val durationSeconds: Int,
)

object WindowsAnimatedArtworkRepository {
    private const val MEMORY_CACHE_LIMIT = 64
    private val jsonParser = Json { ignoreUnknownKeys = true; isLenient = true }
    private val client = HttpClient {
        install(HttpTimeout) {
            requestTimeoutMillis = 15_000
            connectTimeoutMillis = 8_000
            socketTimeoutMillis = 15_000
        }
        install(ContentNegotiation) {
            json(jsonParser)
        }
    }
    private val positiveCache = LruMap<String, WindowsAnimatedArtwork>(Int.MAX_VALUE)
    private val definitiveMisses = LockedSet<String>()

    fun cacheKey(track: Track): String =
        "${normalize(track.title)}|${normalize(track.artist)}|${normalize(track.album.ifBlank { track.streams })}"

    fun invalidate(track: Track) {
        val titleNorm = normalize(track.title)
        val artistNorm = normalize(track.artist)
        locked(positiveCache) {
            val toRemove = positiveCache.keys.filter { it.startsWith("$titleNorm|$artistNorm|") || it == "$titleNorm|$artistNorm" }
            toRemove.forEach { positiveCache.remove(it) }
        }
        definitiveMisses.removeIf { it.startsWith("$titleNorm|$artistNorm|") || it == "$titleNorm|$artistNorm" }
    }

    fun clearDefinitiveMiss(track: Track) {
        val titleNorm = normalize(track.title)
        val artistNorm = normalize(track.artist)
        definitiveMisses.removeIf { it.startsWith("$titleNorm|$artistNorm|") || it == "$titleNorm|$artistNorm" }
    }

    fun clearAllMisses() {
        definitiveMisses.clear()
    }

    fun isDefinitiveMiss(track: Track): Boolean {
        val key = cacheKey(track)
        return definitiveMisses.contains(key)
    }

    internal suspend fun resolveAlbumFromItunes(title: String, artist: String): String? = runCatching {
        val cleanArtist = artist.split(Regex("[,&]|\\s+(?:feat\\.?|ft\\.?|featuring)\\s+", RegexOption.IGNORE_CASE)).firstOrNull()?.trim() ?: artist
        val cleanTitle = cleanVideoNoise(title)
        val query = "$cleanArtist $cleanTitle"
        val responseText: String = client.get("https://itunes.apple.com/search") {
            parameter("term", query)
            parameter("entity", "song")
            parameter("limit", "10")
            header("User-Agent", "RecordLabs-Windows/0.1.1")
        }.body()

        val root = jsonParser.parseToJsonElement(responseText).jsonObject
        val results = root["results"] as? JsonArray ?: return@runCatching null

        data class AlbumCandidate(val name: String, val score: Int)
        val candidates = mutableListOf<AlbumCandidate>()
        val expectedTitleVersions = extractVersionTags(cleanTitle)

        for (item in results) {
            val obj = item as? JsonObject ?: continue
            val trackName = obj["trackName"]?.jsonPrimitive?.contentOrNull.orEmpty()
            val collectionName = obj["collectionName"]?.jsonPrimitive?.contentOrNull.orEmpty()
            if (collectionName.isBlank() || (!metadataMatches(title, trackName) && !metadataMatches(cleanTitle, trackName))) {
                continue
            }

            val collectionVersions = extractVersionTags(collectionName)
            // Reject collections with version tags that the track doesn't have (e.g. track is not a cappella/live/instrumental)
            if ((collectionVersions - expectedTitleVersions).isNotEmpty()) {
                continue
            }

            var score = 0
            val cleanCollection = collectionName.replace(Regex("""\s*[\\[\\(].*?(?:message|bonus).*?[\\]\\)]""", RegexOption.IGNORE_CASE), "").trim()
            val baseCollection = cleanMetadataString(cleanCollection)

            if (collectionName.equals(cleanTitle, ignoreCase = true)) {
                score += 50
            } else if (baseCollection.equals(cleanTitle, ignoreCase = true)) {
                score += 40
            }

            // Prefer collections with fewer words (studio albums over lengthy repackages)
            score -= collectionName.split(" ").filter { it.isNotBlank() }.size

            if (collectionName.contains(":") || collectionName.contains(" - ")) {
                score -= 10
            }
            if (collectionName.contains("deluxe", ignoreCase = true)) {
                score -= 5
            }

            candidates.add(AlbumCandidate(cleanCollection.ifBlank { collectionName }, score))
        }

        candidates.maxByOrNull { it.score }?.name
    }.getOrNull()

    private val appleHlsResolutionRegex = Regex("""\bRESOLUTION=(\d+)x(\d+)""", RegexOption.IGNORE_CASE)
    private val appleHlsBandwidthRegex = Regex("""\b(?:AVERAGE-BANDWIDTH|BANDWIDTH)=(\d+)""", RegexOption.IGNORE_CASE)
    private val appleHlsMapRegex = Regex("""#EXT-X-MAP:URI="([^"]+)"""", RegexOption.IGNORE_CASE)

    private fun selectCompatibleAppleAvcPlaylist(masterPlaylist: String): String? {
        val lines = masterPlaylist.lineSequence().map(String::trim).filter(String::isNotEmpty).toList()
        return lines.mapIndexedNotNull { index, line ->
            if (!line.startsWith("#EXT-X-STREAM-INF:", ignoreCase = true) ||
                !line.contains("CODECS=\"avc1", ignoreCase = true)
            ) {
                return@mapIndexedNotNull null
            }
            val resolution = appleHlsResolutionRegex.find(line) ?: return@mapIndexedNotNull null
            val width = resolution.groupValues[1].toIntOrNull() ?: return@mapIndexedNotNull null
            val height = resolution.groupValues[2].toIntOrNull() ?: return@mapIndexedNotNull null
            val uri = lines.drop(index + 1).firstOrNull { !it.startsWith("#") }
                ?: return@mapIndexedNotNull null
            val bandwidth = appleHlsBandwidthRegex.find(line)?.groupValues?.get(1)?.toIntOrNull() ?: 0
            val longestEdge = maxOf(width, height)
            val pixelCount = width * height
            Triple(pixelCount, bandwidth, uri) to longestEdge
        }
            .filter { (_, longestEdge) -> longestEdge <= 1440 }
            .maxWithOrNull(compareBy({ it.first.first }, { it.first.second }))
            ?.first?.third
    }

    /** Total length of an HLS media playlist (its #EXTINF durations). */
    private fun hlsPlaylistSeconds(mediaPlaylist: String): Double =
        Regex("""#EXTINF:([\d.]+)""").findAll(mediaPlaylist).sumOf { it.groupValues[1].toDoubleOrNull() ?: 0.0 }

    private fun firstAppleMp4Path(mediaPlaylist: String): String? {
        val mapUri = appleHlsMapRegex.find(mediaPlaylist)?.groupValues?.get(1)?.trim()
        if (!mapUri.isNullOrEmpty() && mapUri.substringBefore('?').endsWith(".mp4", ignoreCase = true)) {
            return mapUri
        }
        return mediaPlaylist
            .lineSequence()
            .map(String::trim)
            .firstOrNull { it.isNotEmpty() && !it.startsWith("#") && it.substringBefore('?').endsWith(".mp4", ignoreCase = true) }
    }

    private val avcResolutionCache = concurrentMapOf<String, String>()
    private val failedStreamUrls = LockedSet<String>()

    fun markFailedStreamUrl(url: String) {
        failedStreamUrls.add(url)
        locked(positiveCache) {
            val toRemove = positiveCache.entries.filter { it.value.streamUrl == url }.map { it.key }
            toRemove.forEach { positiveCache.remove(it) }
        }
    }

    /** Square playlists whose H.264 renditions are stale: their (real) HEVC 1080 clip. */
    private val staleSquareHevc = concurrentMapOf<String, String>()
    /** A clip to play instead when [streamUrl]'s can't be decoded here. */
    private val alternates = concurrentMapOf<String, String>()

    /**
     * Real length of each clip resolved from a playlist: Apple's single-file
     * clips run on ~10 s past what the playlist plays (frames nobody shows),
     * which is a long pause at every loop unless playback stops here.
     */
    private val clipLengths = concurrentMapOf<String, Long>()

    /** How long the clip at [streamUrl] really is (ms), when known. */
    fun clipDurationMs(streamUrl: String): Long? = clipLengths[streamUrl]

    /** The clip to fall back to when [streamUrl] fails to decode (Android: the HEVC try). */
    fun alternateStreamUrl(streamUrl: String): String? = alternates[streamUrl]

    private suspend fun resolveCompatibleAppleAvcMp4(masterUrl: String): String? {
        avcResolutionCache[masterUrl]?.let { return it }
        val resolved = runCatching {
            if (!isAllowedAppleVideoUrl(masterUrl)) return@runCatching null
            val masterPlaylist: String = client.get(masterUrl) {
                header("User-Agent", "RecordLabs-Windows/0.1.1")
            }.body()
            val mediaPlaylistPath = selectCompatibleAppleAvcPlaylist(masterPlaylist) ?: return@runCatching null
            val mediaPlaylistUrl = resolveUrl(masterUrl, mediaPlaylistPath)
            val mediaPlaylist: String = client.get(mediaPlaylistUrl) {
                header("User-Agent", "RecordLabs-Windows/0.1.1")
            }.body()
            // Apple sometimes leaves another album's video in a playlist's H.264
            // renditions (same asset id; beabadoobee's "This Is How Tomorrow Moves"
            // showed Slum Village's "F.U.N"). The HEVC renditions are the real clip,
            // and a stale H.264 one has another length: then this playlist is skipped.
            val hevcPath = masterPlaylist.lineSequence().map(String::trim).filter(String::isNotEmpty).toList().let { lines ->
                lines.indices.firstOrNull { lines[it].startsWith("#EXT-X-STREAM-INF:", ignoreCase = true) && lines[it].contains("CODECS=\"hvc1", ignoreCase = true) }
                    ?.let { index -> lines.drop(index + 1).firstOrNull { !it.startsWith("#") } }
            }
            if (hevcPath != null) {
                val hevcPlaylist: String = client.get(resolveUrl(masterUrl, hevcPath)) {
                    header("User-Agent", "RecordLabs-Windows/0.1.1")
                }.body()
                val avcSeconds = hlsPlaylistSeconds(mediaPlaylist)
                val hevcSeconds = hlsPlaylistSeconds(hevcPlaylist)
                if (avcSeconds > 0 && hevcSeconds > 0 && kotlin.math.abs(avcSeconds - hevcSeconds) > 0.5) {
                    println("Animated artwork: stale H.264 renditions in $masterUrl (${avcSeconds}s vs HEVC ${hevcSeconds}s), skipped")
                    // Android: the real square clip only exists in HEVC (10-bit). It is
                    // tried with a software decoder; the vertical H.264 clip is the fallback.
                    if (AppPlatform.isAndroid) runCatching {
                        val lines = masterPlaylist.lineSequence().map(String::trim).filter(String::isNotEmpty).toList()
                        val squareHevcPath = lines.indices.mapNotNull { index ->
                            val line = lines[index]
                            if (!line.startsWith("#EXT-X-STREAM-INF:", ignoreCase = true) || !line.contains("CODECS=\"hvc1", ignoreCase = true)) return@mapNotNull null
                            val size = appleHlsResolutionRegex.find(line) ?: return@mapNotNull null
                            val width = size.groupValues[1].toInt()
                            if (width != size.groupValues[2].toInt() || width > 1080) return@mapNotNull null
                            val uri = lines.drop(index + 1).firstOrNull { !it.startsWith("#") } ?: return@mapNotNull null
                            width to uri
                        }.maxByOrNull { it.first }?.second
                        if (squareHevcPath != null) {
                            val squareUrl = resolveUrl(masterUrl, squareHevcPath)
                            val squarePlaylist: String = client.get(squareUrl) { header("User-Agent", "RecordLabs-Windows/0.1.1") }.body()
                            firstAppleMp4Path(squarePlaylist)?.let {
                                val mp4 = resolveUrl(squareUrl, it)
                                staleSquareHevc[masterUrl] = mp4
                                hlsPlaylistSeconds(squarePlaylist).takeIf { s -> s > 0 }?.let { s -> clipLengths[mp4] = (s * 1000).toLong() }
                            }
                        }
                    }
                    return@runCatching null
                }
            }
            val mp4Path = firstAppleMp4Path(mediaPlaylist) ?: return@runCatching null
            resolveUrl(mediaPlaylistUrl, mp4Path).also { mp4 ->
                val seconds = hlsPlaylistSeconds(mediaPlaylist)
                if (seconds > 0) clipLengths[mp4] = (seconds * 1000).toLong()
            }
        }.onFailure { e ->
            println("resolveCompatibleAppleAvcMp4 failed for $masterUrl: ${e.message}")
        }.getOrNull()

        if (resolved != null) {
            avcResolutionCache[masterUrl] = resolved
        }
        return resolved
    }

    private val isDebianBasedLinux: Boolean by lazy {
        runCatching {
            if (File("/etc/debian_version").isFile) return@lazy true
            val osRelease = File("/etc/os-release").takeIf { it.isFile }?.readText() ?: ""
            val matches = Regex("""(?m)^(?:ID|ID_LIKE)=["']?([^"'\n]+)""").findAll(osRelease)
            val ids = matches.flatMap { it.groupValues[1].split(' ', ',', ';') }
            ids.any {
                it.equals("debian", ignoreCase = true) ||
                it.equals("ubuntu", ignoreCase = true) ||
                it.equals("pop", ignoreCase = true) ||
                it.equals("linuxmint", ignoreCase = true) ||
                it.equals("mint", ignoreCase = true)
            }
        }.getOrElse { false }
    }

    private val hasSystemHevcCodec: Boolean by lazy {
        runCatching {
            val libDirs = listOf(
                "/usr/lib64",
                "/usr/lib/x86_64-linux-gnu",
                "/usr/lib",
                "/usr/local/lib",
                "/usr/lib64/vlc/plugins/codec",
                "/usr/lib/x86_64-linux-gnu/vlc/plugins/codec",
                "/usr/lib/vlc/plugins/codec",
            )
            val targets = listOf(
                "libde265.so",
                "libx265.so",
                "libde265_plugin.so",
                "libhevc_plugin.so",
            )
            libDirs.any { dirPath ->
                val dir = File(dirPath)
                if (!dir.isDirectory) return@any false
                targets.any { prefix ->
                    File(dir, prefix).isFile ||
                    File(dir, "$prefix.0").isFile ||
                    (dir.list()?.any { name -> name.startsWith(prefix) } == true)
                }
            }
        }.getOrElse { false }
    }

    private val hasFfmpegHevcDecoder: Boolean by lazy { desktopFfmpegHasHevc() }

    val supportsHevc: Boolean by lazy {
        systemProperty("recordlabs.hevc")?.toBooleanStrictOrNull()?.let { return@lazy it }
        systemEnv("RECORDLABS_HEVC")?.toBooleanStrictOrNull()?.let { return@lazy it }
        // Android: Apple's square HEVC clips are 10-bit, which many decoders (and WSA)
        // can't play; the square H.264 clip plays everywhere.
        if (AppPlatform.isAndroid) return@lazy false
        // iPhone: AVPlayer plays Apple's HEVC clips natively.
        if (AppPlatform.isIos) return@lazy true
        if (AppPlatform.isWindows) return@lazy true
        if (isDebianBasedLinux) return@lazy true
        if (hasSystemHevcCodec) return@lazy true
        if (hasFfmpegHevcDecoder) return@lazy true
        false
    }

    private suspend fun extractStreamUrl(response: JsonObject): String? {
        val directMp4 = sequenceOf("videoUrl", "videoUrlVertical")
            .mapNotNull { response[it]?.jsonPrimitive?.contentOrNull }
            .firstOrNull { isAllowedAppleVideoUrl(it) && it.substringBefore('?').endsWith(".mp4", ignoreCase = true) && !failedStreamUrls.contains(it) }

        // The square clip first (true 1:1). Its H.264 renditions are checked against
        // the HEVC ones of the same playlist (see resolveCompatibleAppleAvcMp4): a
        // stale one (another album's video under the same id) falls through to the
        // vertical clip, shown centre-cropped.
        val hlsCandidates = sequenceOf("animated", "animatedVertical", "videoUrl", "videoUrlVertical")
            .mapNotNull { response[it]?.jsonPrimitive?.contentOrNull }
            .filter { isAllowedAppleVideoUrl(it) && it.substringBefore('?').endsWith(".m3u8", ignoreCase = true) }

        // 1. If platform supports HEVC (Windows, Debian, Pop!_OS, or Linux with full codecs like RPM Fusion / Arch),
        // direct 4K MP4 (2160x2160) provides the maximum possible resolution with ZERO extra network latency.
        if (supportsHevc && directMp4 != null) {
            return directMp4
        }

        // 2. Universal H.264 (AVC) 1080x1080 MP4 resolved from HLS playlists.
        // H.264 is universally supported across all platforms (Linux/Fedora/Debian/Arch/openSUSE and Windows)
        // without requiring proprietary patent codecs (Fedora's stock VLC decodes AVC via openh264, but refuses HEVC).
        var squareHevc: String? = null
        for (hlsUrl in hlsCandidates) {
            val resolvedAvc = resolveCompatibleAppleAvcMp4(hlsUrl)
            if (resolvedAvc == null) {
                squareHevc = squareHevc ?: staleSquareHevc[hlsUrl]
                continue
            }
            // A stale square H.264 clip: the HEVC square first (it may decode here),
            // this (vertical) one if it does not.
            squareHevc?.let { hevc ->
                alternates[hevc] = resolvedAvc
                return hevc
            }
            return resolvedAvc
        }

        // 3. Fallback to direct MP4 if no AVC was resolvable
        if (directMp4 != null) return directMp4

        // 4. Fallback to any remaining allowed URL
        return sequenceOf("animated", "animatedVertical", "videoUrl", "videoUrlVertical")
            .mapNotNull { response[it]?.jsonPrimitive?.contentOrNull }
            .firstOrNull(::isAllowedAppleVideoUrl)
    }

    private val YOUTUBE_MEDIA_NOISE_REGEX = Regex(
        """\s*(?:\(|\[)\s*(?:official\s+(?:music\s+)?video|official\s+audio|official\s+lyric\s+video|official\s+visualizer|music\s+video|lyric\s+video|video\s+clip|clip\s+officiel|visualizer|lyrics?|audio|performance\s+video|\d+k|hd|hq)[^()\[\]]*(?:\)|\])""",
        RegexOption.IGNORE_CASE
    )
    private val YOUTUBE_TRAILING_NOISE_REGEX = Regex(
        """\s*(?:[|\-–—/]|//)\s*(?:official\s+(?:music\s+)?video|official\s+audio|official\s+video|lyrics?|audio|visualizer)\s*$""",
        RegexOption.IGNORE_CASE
    )

    internal fun cleanVideoNoise(value: String): String = value
        .replace(YOUTUBE_MEDIA_NOISE_REGEX, "")
        .replace(YOUTUBE_TRAILING_NOISE_REGEX, "")
        .trim()

    private val METADATA_CLEAN_REGEX = Regex(
        """\s*(?:\(|\[)\s*[^()\[\]]*(?:feat\.?|ft\.?|featuring|remaster(?:ed)?|deluxe|anniversary|expanded|edition|bonus\s+track)[^()\[\]]*(?:\)|\])""",
        RegexOption.IGNORE_CASE
    )

    internal fun cleanMetadataString(value: String): String {
        val protectedValue = value.replace(Regex("""(?i)\b(taylor['’]s\s*version)\b"""), "###TAYLORS_VERSION###")
        val cleaned = protectedValue
            .replace(METADATA_CLEAN_REGEX, "")
            .replace("###TAYLORS_VERSION###", "Taylor's Version")
            .trim()
        return cleaned.ifBlank { value.trim() }
    }

    private val VERSION_TAG_REGEX = Regex(
        """\b(taylor['’]s\s*version|10\s*minute\s*version|a\s*cap+el+a(?:\s*version)?|acappella|instrumental(?:\s*version)?|karaoke|live(?:\s*version)?|acoustic(?:\s*version)?|remix|orchestral|demo|sped\s*up|slowed)\b""",
        RegexOption.IGNORE_CASE
    )

    private fun canonicalVersionTag(tag: String): String {
        val v = tag.lowercase().replace("’", "'").replace(Regex("\\s+"), " ").trim()
        return when {
            v.contains("cap") -> "a cappella"
            v.contains("instrumental") -> "instrumental"
            v.contains("live") -> "live"
            v.contains("acoustic") -> "acoustic"
            else -> v
        }
    }

    internal fun extractVersionTags(value: String): Set<String> =
        VERSION_TAG_REGEX.findAll(value)
            .map { canonicalVersionTag(it.value) }
            .toSet()

    private fun normalizeVersionText(value: String): String =
        VERSION_TAG_REGEX.replace(value) { match ->
            " " + canonicalVersionTag(match.value) + " "
        }

    internal fun metadataMatches(expected: String, actual: String): Boolean {
        if (expected.isBlank() || actual.isBlank()) return false

        // 1. Version tag consistency: if expected or actual specifies a distinctive version
        // (such as "Taylor's Version", "Live", "Remix", "Acoustic", "A Cappella"), both must strictly agree!
        val expectedVersions = extractVersionTags(expected)
        val actualVersions = extractVersionTags(actual)
        if (expectedVersions != actualVersions) {
            return false
        }

        val a = normalize(expected)
        val b = normalize(actual)
        if (a == b) return true

        val wordsA = a.split(" ").filter { it.isNotBlank() }
        val wordsB = b.split(" ").filter { it.isNotBlank() }
        if (wordsA.isEmpty() || wordsB.isEmpty()) return false
        if (wordsA == wordsB) return true

        val shorter = if (wordsA.size <= wordsB.size) wordsA else wordsB
        val longer = if (wordsA.size <= wordsB.size) wordsB else wordsA

        // All words of the shorter title must be present in the longer title
        if (shorter.all { it in longer }) {
            val ratio = shorter.size.toDouble() / longer.size
            if (ratio >= 0.65 || (shorter.size >= 2 && longer.size - shorter.size <= 2)) {
                return true
            }
        }

        return false
    }

    internal suspend fun buildQueryCandidates(track: Track): List<ArtworkQueryCandidate> {
        val durationSec = if (track.durationMs > 0) (track.durationMs / 1000).toInt() else 0
        val candidates = mutableListOf<ArtworkQueryCandidate>()

        val primaryArtist = track.artist
            .split(Regex("[,&]|\\s+(?:feat\\.?|ft\\.?|featuring)\\s+", RegexOption.IGNORE_CASE))
            .firstOrNull()?.trim().orEmpty().ifBlank { track.artist.trim() }

        val cleanTitle = cleanVideoNoise(track.title).ifBlank { track.title.trim() }
        val rawAlbum = track.album.ifBlank { track.streams }.trim()
        val cleanAlbum = if (rawAlbum.isNotBlank()) cleanVideoNoise(rawAlbum) else null
        val expectedTitleVersions = extractVersionTags(cleanTitle)

        // 1. If the track already has an album provided (e.g. from album page or local tags):
        if (!cleanAlbum.isNullOrBlank()) {
            val albumVersions = extractVersionTags(cleanAlbum)
            // Reject albums whose version tags conflict with the track title
            // (e.g. don't query a cappella album if the track itself is standard vocal)
            val albumVersionMismatch = (albumVersions - expectedTitleVersions).isNotEmpty()

            if (!albumVersionMismatch) {
                candidates.add(
                    ArtworkQueryCandidate(
                        title = cleanTitle,
                        artist = track.artist.trim(),
                        album = rawAlbum,
                        durationSeconds = durationSec,
                    )
                )
                if (cleanAlbum != rawAlbum) {
                    candidates.add(
                        ArtworkQueryCandidate(
                            title = cleanTitle,
                            artist = track.artist.trim(),
                            album = cleanAlbum,
                            durationSeconds = durationSec,
                        )
                    )
                }
                if (track.artist.trim() != primaryArtist) {
                    candidates.add(
                        ArtworkQueryCandidate(
                            title = cleanTitle,
                            artist = primaryArtist,
                            album = cleanAlbum,
                            durationSeconds = durationSec,
                        )
                    )
                }

                // If cleanAlbum has an edition/deluxe tag (e.g. "eternal sunshine (slightly deluxe)"),
                // also try the base studio album (e.g. "eternal sunshine") as a direct candidate!
                val baseAlbum = cleanMetadataString(cleanAlbum)
                if (baseAlbum != cleanAlbum && baseAlbum.isNotBlank()) {
                    candidates.add(
                        ArtworkQueryCandidate(
                            title = cleanTitle,
                            artist = primaryArtist,
                            album = baseAlbum,
                            durationSeconds = durationSec,
                        )
                    )
                }
            }
        }

        // 2. Exact clean title and artist WITHOUT album.
        // Apple Music's index on artwork.boidu.dev resolves the official song directly from title and artist!
        // This is extremely accurate for "Red (Taylor's Version)", "Fearless (Taylor's Version)", etc.
        candidates.add(
            ArtworkQueryCandidate(
                title = cleanTitle,
                artist = primaryArtist,
                album = null,
                durationSeconds = durationSec,
            )
        )
        if (track.artist.trim() != primaryArtist) {
            candidates.add(
                ArtworkQueryCandidate(
                    title = cleanTitle,
                    artist = track.artist.trim(),
                    album = null,
                    durationSeconds = durationSec,
                )
            )
        }

        // 3. Studio album resolved from iTunes (with intelligent scoring and version filtering)
        val resolvedAlbum = if (cleanAlbum.isNullOrBlank() || (extractVersionTags(cleanAlbum) - expectedTitleVersions).isNotEmpty()) {
            resolveAlbumFromItunes(cleanTitle, primaryArtist)
        } else null

        if (!resolvedAlbum.isNullOrBlank()) {
            candidates.add(
                ArtworkQueryCandidate(
                    title = cleanTitle,
                    artist = primaryArtist,
                    album = resolvedAlbum,
                    durationSeconds = durationSec,
                )
            )
        }

        // 4. Fallback: try raw uncleaned title if cleanTitle was different
        if (track.title.trim() != cleanTitle) {
            candidates.add(
                ArtworkQueryCandidate(
                    title = track.title.trim(),
                    artist = primaryArtist,
                    album = cleanAlbum ?: resolvedAlbum,
                    durationSeconds = durationSec,
                )
            )
        }

        // 5. Relaxed metadata fallback (e.g. strip feat / remaster / deluxe)
        // ONLY if the title does not have a protected version tag (like Taylor's Version or Live)
        val relaxedTitle = cleanMetadataString(cleanTitle)
        if (relaxedTitle != cleanTitle && extractVersionTags(cleanTitle).isEmpty()) {
            candidates.add(
                ArtworkQueryCandidate(
                    title = relaxedTitle,
                    artist = primaryArtist,
                    album = cleanAlbum ?: resolvedAlbum,
                    durationSeconds = durationSec,
                )
            )
            candidates.add(
                ArtworkQueryCandidate(
                    title = relaxedTitle,
                    artist = primaryArtist,
                    album = null,
                    durationSeconds = durationSec,
                )
            )
        }

        return candidates.distinct()
    }

    /** Lookup off the UI thread: the HTTP replies are parsed where this runs. */
    suspend fun find(track: Track, forceRefresh: Boolean = false): WindowsAnimatedArtwork? =
        withContext(IoDispatcher) { findOnCaller(track, forceRefresh) }

    private suspend fun findOnCaller(track: Track, forceRefresh: Boolean): WindowsAnimatedArtwork? {
        if (track.title.isBlank() || track.artist.isBlank()) return null
        val key = cacheKey(track)

        if (forceRefresh) {
            invalidate(track)
        } else {
            locked(positiveCache) {
                positiveCache[key]?.let { return it }
            }
            if (definitiveMisses.contains(key)) return null
        }

        val primaryArtist = track.artist
            .split(Regex("[,&]|\\s+(?:feat\\.?|ft\\.?|featuring)\\s+", RegexOption.IGNORE_CASE))
            .firstOrNull()?.trim().orEmpty().ifBlank { track.artist.trim() }
        val cleanTitle = cleanVideoNoise(track.title).ifBlank { track.title.trim() }

        val candidates = buildQueryCandidates(track).toMutableList()

        for (attempt in 0..1) {
            var allCandidates404OrEmpty = true

            val result = runCatching {
                for (candidate in candidates) {
                    val responseHttp = try {
                        client.get("https://artwork.boidu.dev/") {
                            parameter("s", candidate.title)
                            parameter("a", candidate.artist)
                            if (!candidate.album.isNullOrBlank()) {
                                parameter("albumName", candidate.album)
                            }
                            if (candidate.durationSeconds > 0) {
                                parameter("duration", candidate.durationSeconds)
                            }
                            header("User-Agent", "RecordLabs-Windows/0.1.1")
                        }
                    } catch (e: ClientRequestException) {
                        if (e.response.status.value == 404) {
                            continue
                        }
                        allCandidates404OrEmpty = false
                        throw e
                    } catch (e: Exception) {
                        allCandidates404OrEmpty = false
                        throw e
                    }

                    val response: JsonObject = responseHttp.body()
                    val matchedTitle = response["name"]?.jsonPrimitive?.contentOrNull.orEmpty()
                    val matchedArtist = response["artist"]?.jsonPrimitive?.contentOrNull.orEmpty()

                    val titleMatches = metadataMatches(track.title, matchedTitle) || metadataMatches(cleanTitle, matchedTitle)
                    val artistMatches = metadataMatches(track.artist, matchedArtist) || metadataMatches(primaryArtist, matchedArtist)

                    if (!titleMatches || !artistMatches) {
                        continue
                    }

                    val stream = extractStreamUrl(response)

                    if (stream != null) {
                        return@runCatching WindowsAnimatedArtwork(
                            streamUrl = stream,
                            staticUrl = response["static"]?.jsonPrimitive?.contentOrNull,
                        )
                    }
                }

                // If all candidates failed, try resolving the official studio album from iTunes
                val existingAlbum = track.album.ifBlank { track.streams }
                val fallbackAlbum = resolveAlbumFromItunes(cleanTitle, primaryArtist)
                if (!fallbackAlbum.isNullOrBlank() && !fallbackAlbum.equals(existingAlbum, ignoreCase = true)) {
                    val fallbackResponse = try {
                        client.get("https://artwork.boidu.dev/") {
                            parameter("s", cleanTitle)
                            parameter("a", primaryArtist)
                            parameter("albumName", fallbackAlbum)
                            header("User-Agent", "RecordLabs-Windows/0.1.1")
                        }
                    } catch (e: Exception) { null }

                    if (fallbackResponse != null) {
                        val respJson: JsonObject = fallbackResponse.body()
                        val matchedTitle = respJson["name"]?.jsonPrimitive?.contentOrNull.orEmpty()
                        val matchedArtist = respJson["artist"]?.jsonPrimitive?.contentOrNull.orEmpty()
                        val titleMatches = metadataMatches(track.title, matchedTitle) || metadataMatches(cleanTitle, matchedTitle)
                        val artistMatches = metadataMatches(track.artist, matchedArtist) || metadataMatches(primaryArtist, matchedArtist)

                        if (titleMatches && artistMatches) {
                            val stream = extractStreamUrl(respJson)
                            if (stream != null) {
                                return@runCatching WindowsAnimatedArtwork(
                                    streamUrl = stream,
                                    staticUrl = respJson["static"]?.jsonPrimitive?.contentOrNull,
                                )
                            }
                        }
                    }
                }

                null
            }

            val artwork = result.getOrNull()
            if (artwork != null) {
                locked(positiveCache) {
                    positiveCache[key] = artwork
                    while (positiveCache.size > MEMORY_CACHE_LIMIT) {
                        val firstKey = positiveCache.keys.firstOrNull() ?: break
                        positiveCache.remove(firstKey)
                    }
                }
                definitiveMisses.remove(key)
                return artwork
            }

            if (result.isSuccess && allCandidates404OrEmpty) {
                definitiveMisses.add(key)
                return null
            }

            // Temporary network failure: brief pause before second attempt
            if (attempt == 0) {
                delay(1200L)
            }
        }

        return null
    }

    private fun isAllowedAppleVideoUrl(value: String): Boolean = runCatching {
        val host = urlHost(value)
        value.startsWith("https://", true) && (host.endsWith("apple.com", true) || host.endsWith("mzstatic.com", true))
    }.getOrDefault(false)

    private fun normalize(value: String): String = normalizeVersionText(
        value.lowercase()
            .replace(Regex("""(?i)\s*(?:\(|\[)[^()\[\]]*(?:official|video|audio|lyrics?|visualizer)[^()\[\]]*(?:\)|\])"""), "")
    )
        .replace(Regex("[^\\p{L}\\p{N}]+"), " ")
        .trim()
}

/** Linux desktop: ffmpeg on the PATH can decode HEVC (false elsewhere). */
internal expect fun desktopFfmpegHasHevc(): Boolean
