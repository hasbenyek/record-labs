package com.recordlabs.desktop.data

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.timeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.statement.bodyAsText
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.util.Locale
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs

@Serializable
private data class LrcLibResponse(
    val trackName: String? = null,
    val artistName: String? = null,
    val syncedLyrics: String? = null,
    val plainLyrics: String? = null,
)

private data class AppleLyricsCandidate(
    val id: String,
    val title: String,
    val artist: String,
    val album: String,
    val durationMs: Long,
)

data class LyricsResult(
    val lyrics: String,
    val provider: String,
    val manuallySelected: Boolean = false,
)
data class LyricsCandidate(val provider: String, val lyrics: String)

/** Provider order: Paxsenix, BetterLyrics, Musixmatch, KuGou, LRCLIB, YouTube Music. */
object WindowsLyricsProviderRegistry {
    private const val AUTOMATIC_MATCH_VERSION = 2
    private const val MEMORY_CACHE_LIMIT = 96
    private val jsonCodec = Json { ignoreUnknownKeys = true; isLenient = true }
    private val client = HttpClient {
        install(HttpTimeout) {
            requestTimeoutMillis = 15_000
            connectTimeoutMillis = 10_000
            socketTimeoutMillis = 15_000
        }
        install(ContentNegotiation) { json(jsonCodec) }
    }
    private val cache = LinkedHashMap<String, LyricsResult>(32, 0.75f, true)
    private val priorityValidated = mutableSetOf<String>()
    private val fallbackRepositoryDelegate = lazy { WindowsMusicRepository() }
    private val fallbackRepository by fallbackRepositoryDelegate
    private val closed = AtomicBoolean(false)
    @Volatile private var appleMusicToken: String? = null
    private val listeners = CopyOnWriteArrayList<(trackId: String, result: LyricsResult) -> Unit>()

    /**
     * Paxsenix often needs 15-20 s for a song it has not served before and
     * answers "busy" under load. Its requests may take this long, are retried
     * when busy, and keep running in the background after a quicker provider
     * is shown, so the preferred lyric still replaces it once it arrives.
     */
    private const val PAXSENIX_REQUEST_TIMEOUT_MS = 45_000L
    private const val PAXSENIX_TOTAL_TIMEOUT_MS = 75_000L
    /** How long the first lyric waits for Paxsenix before showing another provider. */
    private const val PAXSENIX_GRACE_MS = 6_000L
    private val backgroundScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val paxsenixInFlight = HashMap<String, PaxsenixLookup>()
    /** Finished lookups, reused for a while (a song without Paxsenix lyrics is not asked again at once). */
    private val paxsenixFinished = HashMap<String, PaxsenixLookup>()
    private const val PAXSENIX_REUSE_MS = 10 * 60_000L
    private const val PAXSENIX_LAST_WAIT_MS = 30_000L
    /**
     * While Paxsenix answers "busy" (HTTP 429/502/503/504 or no answer), the
     * background lookup asks again after these growing pauses, for as long as
     * the song is one of the few most recently wanted ones. A busy outcome is
     * never remembered as "this song has no Paxsenix lyrics".
     */
    private val PAXSENIX_BUSY_RETRY_DELAYS_MS = longArrayOf(10_000L, 20_000L, 30_000L, 45_000L, 60_000L, 90_000L, 120_000L)
    private const val PAXSENIX_BUSY_RETRY_WINDOW_MS = 12 * 60_000L
    private const val PAXSENIX_RECENT_TRACKS = 4
    private val paxsenixRecentKeys = ArrayDeque<String>()

    private class PaxsenixLookup(val queries: Set<Pair<String, String>>, val result: Deferred<String?>) {
        @Volatile var finishedAt = 0L
        /** Ended while Paxsenix was still busy: not reused as a final answer. */
        @Volatile var busy = false
    }

    /** One Paxsenix answer: lyrics found, definitely none, or service busy. */
    private sealed interface PaxsenixReply {
        class Found(val json: JsonObject) : PaxsenixReply
        object Missing : PaxsenixReply
        object Busy : PaxsenixReply
    }

    private class PaxsenixFetch(val lyrics: List<String>, val busy: Boolean)

    /** Settings > Content > Lyrics providers: a provider turned off is never asked. */
    private fun providerEnabled(name: String): Boolean = AppSettings.isLyricsProviderEnabled(name)

    /** Paxsenix lookup, or an empty answer when Paxsenix is turned off. */
    private fun paxsenixIfEnabled(track: Track, queries: List<Track>): Deferred<String?> =
        if (providerEnabled("Paxsenix")) paxsenixInBackground(track, queries) else kotlinx.coroutines.CompletableDeferred<String?>(null)
    private val cacheWriteLock = Any()
    /** English (or Latin) title and artist found for non-Latin metadata, per title and artist. */
    private val aliasAppleIds = object : LinkedHashMap<String, String>(32, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?) = size > 200
    }
    private val englishAliases = object : LinkedHashMap<String, Track?>(32, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Track?>?) = size > 200
    }

    /**
     * Subscribe to real-time lyrics updates (new fetches, provider upgrades, manual user selection).
     * Returns an unregister function.
     */
    fun addListener(listener: (trackId: String, result: LyricsResult) -> Unit): () -> Unit {
        listeners.add(listener)
        return { listeners.remove(listener) }
    }

    private fun notifyListeners(trackId: String, result: LyricsResult) {
        listeners.forEach { runCatching { it(trackId, result) } }
    }

    private fun cacheKey(track: Track): String = track.id

    private fun cacheFile(track: Track): File = File(WindowsStorage.directory, "lyrics-cache").apply { mkdirs() }
        .resolve("${cacheKey(track).hashCode().toUInt().toString(16)}.lyrics")

    /** Returns an already fetched result without starting network work. */
    /**
     * The stored lyric of [track]. One from a provider turned off in Settings is
     * ignored (looked up again with the providers that are on), unless the user
     * picked it by hand.
     */
    fun peekCached(track: Track): LyricsResult? =
        peekCachedRaw(track)?.takeIf { it.manuallySelected || it.provider !in allProviders || providerEnabled(it.provider) }

    private fun peekCachedRaw(track: Track): LyricsResult? {
        val key = cacheKey(track)
        synchronized(cache) { cache[key]?.let { return it } }
        val diskCache = cacheFile(track)
        val persisted = runCatching { diskCache.readText() }.getOrNull() ?: return null
        val lines = persisted.lines()
        val provider = lines.firstOrNull()?.removePrefix("provider:").orEmpty()
        var headerIndex = 1
        var manuallySelected = false
        var matchVersion = 0
        while (headerIndex < lines.size) {
            val header = lines[headerIndex]
            when {
                header.startsWith("selected:") -> manuallySelected =
                    header.removePrefix("selected:").toBooleanStrictOrNull() == true
                header.startsWith("match-version:") -> matchVersion =
                    header.removePrefix("match-version:").toIntOrNull() ?: 0
                else -> break
            }
            headerIndex++
        }
        val lyrics = lines.drop(headerIndex).joinToString("\n")
        if (provider.isBlank() || lyrics.isBlank()) return null
        // Automatic entries made before artist validation was tightened can
        // contain a same-title cover by a different artist. Manual choices are
        // deliberately preserved; only old automatic decisions are refetched.
        if (!manuallySelected && matchVersion < AUTOMATIC_MATCH_VERSION) {
            diskCache.delete()
            return null
        }
        return LyricsResult(lyrics, provider, manuallySelected).also { result -> putMemoryCache(key, result) }
    }

    /**
     * A user's explicit choice outranks every automatic provider decision.
     * Persist it under the normal track cache key so closing/reopening Lyrics,
     * the full player, or the application restores exactly the selected text.
     */
    fun selectLyrics(track: Track, candidate: LyricsCandidate): LyricsResult {
        val result = LyricsResult(candidate.lyrics, candidate.provider, manuallySelected = true)
        synchronized(cacheWriteLock) { writeCache(track, result) }
        return result
    }

    suspend fun getLyricsResult(track: Track, forceRefresh: Boolean = false): LyricsResult = withContext(Dispatchers.IO) {
        val cacheKey = cacheKey(track)
        val diskCache = cacheFile(track)
        if (forceRefresh) {
            synchronized(paxsenixInFlight) {
                paxsenixFinished.remove(cacheKey)
                paxsenixInFlight.remove(cacheKey)
            }?.result?.cancel()
            synchronized(cache) { cache.remove(cacheKey) }
            synchronized(priorityValidated) { priorityValidated.remove(cacheKey) }
            diskCache.delete()
        } else {
            val cached = peekCached(track)
            if (cached?.manuallySelected == true) return@withContext cached
            if (cached != null && synchronized(priorityValidated) { cacheKey in priorityValidated }) return@withContext cached

            // Revalidate automatic Paxsenix line-timed entries as well as
            // lower-priority providers once per session. This upgrades an old
            // cache to a word-timed Paxsenix variant when one is available,
            // while a user's manual choice above remains authoritative.
            if (cached != null) {
                val cleaned = track.copy(title = cleanTitle(track.title))
                // Query variants are already ordered by metadata preference:
                // English/Latin first, then the original script. Keep that
                // order while still choosing the richest timing returned for
                // the selected metadata variant.
                val queries = (
                    resolvedQueryVariants(cleaned) +
                        normalizedTrack(cleaned).takeIf { it != cleaned }?.let { resolvedQueryVariants(it) }.orEmpty()
                    ).distinctBy { canonicalTitle(it.title) to canonicalArtist(it.artist) }
                // Bounded: a busy Paxsenix keeps retrying in the background and
                // replaces the cached lyric through the listeners when it answers.
                val paxsenix = runCatching {
                    withTimeoutOrNull(PAXSENIX_LAST_WAIT_MS) { paxsenixIfEnabled(track, queries).await() }
                }.getOrNull()
                synchronized(priorityValidated) { priorityValidated += cacheKey }
                if (!paxsenix.isNullOrBlank() &&
                    paxsenixReplaces(paxsenix, cached)
                ) {
                    val upgraded = LyricsResult(paxsenix, "Paxsenix")
                    synchronized(cacheWriteLock) {
                        val latest = peekCached(track)
                        if (latest?.manuallySelected == true) return@withContext latest
                        writeCache(track, upgraded)
                    }
                    return@withContext upgraded
                }
                return@withContext cached
            }
        }

        val cleanedTrack = track.copy(title = cleanTitle(track.title))
        var result = firstAvailable(track, cleanedTrack)
        if (result.lyrics.isBlank()) {
            // Metadata from queue/radio can include featured artists or an
            // edition suffix. Retry once with the same-song-safe normalized
            // metadata before declaring the track lyricless.
            val relaxed = normalizedTrack(cleanedTrack)
            result = firstAvailable(track, relaxed)
        }
        if (result.lyrics.isNotBlank()) {
            synchronized(cacheWriteLock) {
                // Paxsenix finishing in the background meanwhile (or a manual
                // choice) is kept instead of a lower-priority result.
                val latest = peekCached(track)
                if (latest != null && (latest.manuallySelected || (latest.provider == "Paxsenix" && result.provider != "Paxsenix"))) {
                    result = latest
                } else {
                    writeCache(track, result)
                }
            }
            synchronized(priorityValidated) { priorityValidated += cacheKey }
        } else {
            // A lower-priority cached result is still preferable when the
            // network is unavailable during revalidation.
            peekCached(track)?.let { return@withContext it }
        }
        result
    }

    private fun normalizedTrack(track: Track): Track = track.copy(
        // Keep genuine collaborations intact. Only a feature suffix is relaxed;
        // dropping everything after '&' or ',' made same-title covers eligible.
        artist = track.artist.replace(Regex("(?i)\\s+(feat\\.?|featuring)\\s+.*$"), "").trim(),
        album = "",
    )

    private fun writeCache(track: Track, result: LyricsResult) {
        val key = cacheKey(track)
        putMemoryCache(key, result)
        runCatching {
            cacheFile(track).writeText(
                "provider:${result.provider}\nselected:${result.manuallySelected}\n" +
                    "match-version:$AUTOMATIC_MATCH_VERSION\n${result.lyrics}",
            )
        }
        notifyListeners(key, result)
    }

    private fun putMemoryCache(key: String, result: LyricsResult) {
        val evicted = mutableListOf<String>()
        synchronized(cache) {
            cache[key] = result
            while (cache.size > MEMORY_CACHE_LIMIT) {
                val iterator = cache.entries.iterator()
                if (!iterator.hasNext()) break
                evicted += iterator.next().key
                iterator.remove()
            }
        }
        if (evicted.isNotEmpty()) synchronized(priorityValidated) {
            priorityValidated.removeAll(evicted.toSet())
        }
    }

    fun cacheBytes(): Long = listOf(File(WindowsStorage.directory, "lyrics-cache"), File(WindowsStorage.directory, "lyrics-translations"))
        .sumOf { dir -> dir.listFiles()?.filter(File::isFile)?.sumOf(File::length) ?: 0L }

    /**
     * Settings > Storage > Clear lyrics cache: every stored lyric and translation
     * is looked up again next time. Lyrics the user picked by hand are removed
     * too (they are part of the same cache).
     */
    fun clearAllCaches() {
        synchronized(paxsenixInFlight) {
            paxsenixInFlight.values.forEach { it.result.cancel() }
            paxsenixInFlight.clear()
            paxsenixFinished.clear()
        }
        synchronized(cache) { cache.clear() }
        synchronized(priorityValidated) { priorityValidated.clear() }
        listOf(File(WindowsStorage.directory, "lyrics-cache"), File(WindowsStorage.directory, "lyrics-translations")).forEach { dir ->
            dir.listFiles()?.forEach { runCatching { it.delete() } }
        }
    }

    fun close() {
        if (!closed.compareAndSet(false, true)) return
        client.close()
        if (fallbackRepositoryDelegate.isInitialized()) fallbackRepository.close()
    }

    /**
     * Rechecks only the two preferred providers after a lower-priority result
     * is already visible. The caller can keep showing that provisional lyric;
     * a strict Paxsenix (best word timing first) or BetterLyrics result replaces
     * it when ready. A manual user selection always wins, including when it is
     * made while these requests are still running.
     */
    suspend fun getPreferredLyricsUpgrade(track: Track, current: LyricsResult): LyricsResult? =
        withContext(Dispatchers.IO) {
            // BetterLyrics is only replaced by Paxsenix; everything else by either.
            if (current.manuallySelected || current.provider == "Paxsenix") {
                return@withContext null
            }
            val cleaned = track.copy(title = cleanTitle(track.title))
            val queries = (
                resolvedQueryVariants(cleaned) +
                    normalizedTrack(cleaned).takeIf { it != cleaned }
                        ?.let { resolvedQueryVariants(it) }.orEmpty()
                ).distinctBy { canonicalTitle(it.title) to canonicalArtist(it.artist) }

            val preferred = coroutineScope {
                val paxsenix = paxsenixIfEnabled(track, queries)
                val betterLyrics = if (current.provider == "BetterLyrics" || !providerEnabled("BetterLyrics")) null
                else async { fetchBestAcross(queries, ::fetchBetterLyrics) }
                // Musixmatch (third) only replaces the providers after it.
                val musixmatch = if (current.provider == "BetterLyrics" || current.provider == "Musixmatch" || !providerEnabled("Musixmatch")) null
                else async { fetchBestAcross(queries, ::fetchMusixmatchLyrics) }
                // Bounded so BetterLyrics is not held back while Paxsenix is busy;
                // a later Paxsenix answer still arrives through the listeners.
                runCatching { withTimeoutOrNull(PAXSENIX_LAST_WAIT_MS) { paxsenix.await() } }.getOrNull()?.takeIf(String::isNotBlank)
                    ?.takeIf { paxsenixReplaces(it, current) }?.let { LyricsResult(it, "Paxsenix") }
                    ?: betterLyrics?.await()?.takeIf(String::isNotBlank)?.let { LyricsResult(it, "BetterLyrics") }
                    ?: musixmatch?.await()?.takeIf(String::isNotBlank)?.let { LyricsResult(it, "Musixmatch") }
            }
            synchronized(priorityValidated) { priorityValidated += cacheKey(track) }
            preferred ?: return@withContext null

            synchronized(cacheWriteLock) {
                val latest = peekCached(track)
                if (latest?.manuallySelected == true) return@withContext null
                if (latest != null && latest.provider == preferred.provider && latest.lyrics == preferred.lyrics) {
                    // Already stored (e.g. by the background Paxsenix request): still show it.
                    return@withContext preferred.takeIf { current.provider != preferred.provider || current.lyrics != preferred.lyrics }
                }
                writeCache(track, preferred)
            }
            preferred
        }

    private suspend fun firstAvailable(original: Track, track: Track): LyricsResult = coroutineScope {
        val queryTracks = resolvedQueryVariants(track)
        // Paxsenix runs on its own (shared with the upgrade check), so a slow
        // answer is not lost when another provider is shown first.
        val paxsenix = paxsenixIfEnabled(original, queryTracks)
        val providers = listOf<Pair<String, suspend () -> String?>>(
            "BetterLyrics" to { fetchBestAcross(queryTracks, ::fetchBetterLyrics) },
            "Musixmatch" to { fetchBestAcross(queryTracks, ::fetchMusixmatchLyrics) },
            "KuGou" to { fetchBestAcross(queryTracks) { fetchKuGouLyrics(it.title, it.artist) } },
            "LRCLIB" to { fetchBestAcross(queryTracks) { fetchLrcLibLyrics(it.title, it.artist, it.durationMs / 1000) } },
            // Always queried so it is available, but awaited strictly last.
            "YouTube Music" to { fetchYouTubeLyrics(track.id) },
        ).filter { (name, _) -> providerEnabled(name) }
        val pending = providers.map { (name, provider) -> name to async { runCatching { provider() }.getOrNull() } }
        suspend fun paxsenixReady(): String? = if (paxsenix.isCompleted) {
            runCatching { paxsenix.await() }.getOrNull()?.takeIf(String::isNotBlank)
        } else null
        fun done(result: LyricsResult): LyricsResult {
            pending.forEach { it.second.cancel() }
            return result
        }
        runCatching { withTimeoutOrNull(PAXSENIX_GRACE_MS) { paxsenix.await() } }.getOrNull()
            ?.takeIf(String::isNotBlank)?.let { return@coroutineScope done(LyricsResult(it, "Paxsenix")) }
        for ((providerName, deferred) in pending) {
            val lyrics = deferred.await()
            paxsenixReady()?.let { return@coroutineScope done(LyricsResult(it, "Paxsenix")) }
            lyrics?.takeIf(String::isNotBlank)?.let { return@coroutineScope done(LyricsResult(it, providerName)) }
        }
        // Nothing else has this song: wait for Paxsenix a while longer (if it
        // answers even later, it is stored and shown then).
        runCatching { withTimeoutOrNull(PAXSENIX_LAST_WAIT_MS) { paxsenix.await() } }.getOrNull()
            ?.takeIf(String::isNotBlank)?.let { return@coroutineScope LyricsResult(it, "Paxsenix") }
        LyricsResult("", "")
    }

    /**
     * One Paxsenix lookup per track at a time, kept running in the background.
     * When it finds lyrics after another provider was already stored, it
     * replaces that result (never a manual choice) and listeners are told.
     */
    private fun paxsenixInBackground(track: Track, queries: List<Track>): Deferred<String?> {
        val key = cacheKey(track)
        val wanted = queries.map { canonicalTitle(it.title) to canonicalArtist(it.artist) }.toSet()
        synchronized(paxsenixInFlight) {
            paxsenixRecentKeys.remove(key)
            paxsenixRecentKeys.addLast(key)
            while (paxsenixRecentKeys.size > PAXSENIX_RECENT_TRACKS) paxsenixRecentKeys.removeFirst()
            paxsenixInFlight[key]?.takeIf { it.result.isActive }?.let { return it.result }
            paxsenixFinished[key]?.takeIf {
                !it.result.isCancelled && !it.busy && it.queries.containsAll(wanted) &&
                    System.currentTimeMillis() - it.finishedAt < PAXSENIX_REUSE_MS
            }?.let { return it.result }
            lateinit var lookup: PaxsenixLookup
            val request = backgroundScope.async(start = kotlinx.coroutines.CoroutineStart.LAZY) {
                val startedAt = System.currentTimeMillis()
                var lyrics: String? = null
                var retry = 0
                while (true) {
                    val fetch = fetchPaxsenixAcross(queries, automatic = true)
                    lyrics = fetch.lyrics.firstOrNull()
                    if (lyrics != null || !fetch.busy) break
                    // Still busy: ask again later while this song is still wanted.
                    val pause = PAXSENIX_BUSY_RETRY_DELAYS_MS[minOf(retry, PAXSENIX_BUSY_RETRY_DELAYS_MS.lastIndex)]
                    val stillWanted = synchronized(paxsenixInFlight) {
                        paxsenixInFlight[key] === lookup && key in paxsenixRecentKeys
                    }
                    if (!stillWanted || System.currentTimeMillis() - startedAt + pause > PAXSENIX_BUSY_RETRY_WINDOW_MS) {
                        lookup.busy = true
                        break
                    }
                    delay(pause)
                    retry++
                }
                val found = lyrics
                if (!found.isNullOrBlank()) synchronized(cacheWriteLock) {
                    // Only the current lookup may write (not one a refresh replaced).
                    val current = synchronized(paxsenixInFlight) { paxsenixInFlight[key] === lookup }
                    val latest = peekCached(track)
                    if (current && (latest == null || (!latest.manuallySelected && paxsenixReplaces(found, latest)))) {
                        writeCache(track, LyricsResult(found, "Paxsenix"))
                    }
                }
                found
            }
            lookup = PaxsenixLookup(wanted, request)
            paxsenixInFlight[key] = lookup
            request.invokeOnCompletion { failure ->
                synchronized(paxsenixInFlight) {
                    if (paxsenixInFlight.remove(key, lookup) && failure == null && !lookup.busy) {
                        lookup.finishedAt = System.currentTimeMillis()
                        paxsenixFinished[key] = lookup
                        if (paxsenixFinished.size > 64) {
                            paxsenixFinished.entries.minByOrNull { it.value.finishedAt }?.let { paxsenixFinished.remove(it.key) }
                        }
                    }
                }
            }
            request.start()
            return request
        }
    }

    /**
     * Whether Paxsenix lyrics replace an automatic result: anything below
     * BetterLyrics always; BetterLyrics only when Paxsenix is timed at least
     * as finely; an older Paxsenix result only when this one is richer.
     */
    private fun paxsenixReplaces(lyrics: String, current: LyricsResult): Boolean = when (current.provider) {
        "Paxsenix" -> lyricsTimingPriority(lyrics) > lyricsTimingPriority(current.lyrics)
        "BetterLyrics" -> lyricsTimingPriority(lyrics) >= lyricsTimingPriority(current.lyrics)
        else -> true
    }

    suspend fun getLyrics(track: Track, forceRefresh: Boolean = false): String =
        getLyricsResult(track, forceRefresh).lyrics

    suspend fun refetchLyrics(track: Track): String = getLyrics(track, forceRefresh = true)

    suspend fun searchAll(track: Track): List<LyricsCandidate> = searchAll(track) { }

    suspend fun searchAll(
        track: Track,
        onProgress: suspend (List<LyricsCandidate>) -> Unit,
    ): List<LyricsCandidate> = withContext(Dispatchers.IO) {
        val cleaned = track.copy(title = cleanTitle(track.title))
        val queryTracks = resolvedQueryVariants(cleaned)
        coroutineScope {
            val paxsenixPending = async { fetchPaxsenixCandidatesAcross(queryTracks, automatic = false) }
            val lrcLibPending = async {
                queryTracks.map { query -> async { fetchLrcLibCandidates(query.title, query.artist) } }
                    .awaitAll().flatten().distinct()
            }
            val kuGouPending = async {
                queryTracks.map { query -> async { fetchKuGouCandidates(query.title, query.artist) } }
                    .awaitAll().flatten().distinct()
            }
            val betterLyricsPending = async { fetchBestAcross(queryTracks, ::fetchBetterLyrics) }
            val musixmatchPending = async {
                queryTracks.map { query -> async { fetchMusixmatchCandidates(query, includePlain = true) } }
                    .awaitAll().flatten().distinct()
            }
            val youTubeMusicPending = async { fetchYouTubeLyrics(cleaned.id) }
            // Each provider is shown as soon as it answers (Paxsenix can take a
            // while), always listed in the usual provider order.
            val order = searchProviderOrder
            val accumulated = mutableListOf<LyricsCandidate>()
            val publishLock = kotlinx.coroutines.sync.Mutex()
            fun ordered() = accumulated.sortedBy { order.indexOf(it.provider) }.distinctBy { it.provider to it.lyrics }
            suspend fun publish(items: List<LyricsCandidate>) = publishLock.withLock {
                accumulated += items
                onProgress(ordered())
            }
            listOf(
                async { publish(runCatching { paxsenixPending.await() }.getOrDefault(emptyList()).map { LyricsCandidate("Paxsenix", it) }) },
                async {
                    publish(runCatching { betterLyricsPending.await() }.getOrNull()
                        ?.takeIf(String::isNotBlank)?.let { listOf(LyricsCandidate("BetterLyrics", it)) }.orEmpty())
                },
                async { publish(runCatching { musixmatchPending.await() }.getOrDefault(emptyList()).map { LyricsCandidate("Musixmatch", it) }) },
                async { publish(runCatching { kuGouPending.await() }.getOrDefault(emptyList()).map { LyricsCandidate("KuGou", it) }) },
                async { publish(runCatching { lrcLibPending.await() }.getOrDefault(emptyList()).map { LyricsCandidate("LRCLIB", it) }) },
                async {
                    publish(runCatching { youTubeMusicPending.await() }.getOrNull()
                        ?.takeIf(String::isNotBlank)?.let { listOf(LyricsCandidate("YouTube Music", it)) }.orEmpty())
                },
            ).awaitAll()
            publishLock.withLock { ordered() }
        }
    }

    /** Providers of the manual search, in the order their results are listed. */
    val allProviders = listOf("Paxsenix", "BetterLyrics", "Musixmatch", "KuGou", "LRCLIB", "YouTube Music")
    /** The providers turned on, in their fixed priority order. */
    val searchProviderOrder: List<String> get() = allProviders.filter(::providerEnabled)

    /**
     * The manual search, provider by provider: [onProvider] gets each
     * provider's candidates (possibly none) as soon as it answers, so a search
     * can be resumed later for just the providers that found nothing.
     */
    suspend fun searchProviders(
        track: Track,
        providers: Collection<String>,
        onProvider: suspend (provider: String, candidates: List<LyricsCandidate>) -> Unit,
    ) = withContext(Dispatchers.IO) {
        val cleaned = track.copy(title = cleanTitle(track.title))
        val queryTracks = resolvedQueryVariants(cleaned)
        coroutineScope {
            providers.distinct().map { provider ->
                async {
                    val found: List<String> = runCatching {
                        when (provider) {
                            "Paxsenix" -> fetchPaxsenixCandidatesAcross(queryTracks, automatic = false)
                            "BetterLyrics" -> listOfNotNull(fetchBestAcross(queryTracks, ::fetchBetterLyrics)?.takeIf(String::isNotBlank))
                            "Musixmatch" -> queryTracks.map { query -> async { fetchMusixmatchCandidates(query, includePlain = true) } }
                                .awaitAll().flatten().distinct()
                            "KuGou" -> queryTracks.map { query -> async { fetchKuGouCandidates(query.title, query.artist) } }
                                .awaitAll().flatten().distinct()
                            "LRCLIB" -> queryTracks.map { query -> async { fetchLrcLibCandidates(query.title, query.artist) } }
                                .awaitAll().flatten().distinct()
                            "YouTube Music" -> listOfNotNull(fetchYouTubeLyrics(cleaned.id)?.takeIf(String::isNotBlank))
                            else -> emptyList()
                        }
                    }.getOrDefault(emptyList())
                    onProvider(provider, found.map { LyricsCandidate(provider, it) })
                }
            }.awaitAll()
        }
    }

    private fun cleanTitle(title: String): String = title
        .replace(Regex("(?i)\\(official.*?\\)|\\[official.*?\\]|\\s+(ft|feat)\\..*"), "")
        .trim()

    private suspend fun fetchPaxsenixCandidatesAcross(tracks: List<Track>, automatic: Boolean): List<String> =
        fetchPaxsenixAcross(tracks, automatic).lyrics

    /** Like [fetchPaxsenixCandidatesAcross], also telling whether Paxsenix was busy for some match. */
    private suspend fun fetchPaxsenixAcross(tracks: List<Track>, automatic: Boolean): PaxsenixFetch = coroutineScope {
        // Start every metadata spelling at once, but consume the completed
        // results in query order. For non-Latin tracks this means a valid
        // Latin/English-title match wins; the original title is the fallback.
        val pending = tracks.map { query -> async { fetchPaxsenixCandidates(query, automatic) } }
        val collected = mutableListOf<String>()
        var busy = false
        for ((index, deferred) in pending.withIndex()) {
            val fetch = deferred.await()
            if (fetch.busy) busy = true
            val candidates = fetch.lyrics.sortedByDescending(::lyricsTimingPriority)
            collected += candidates
            // A word-timed match of the preferred spelling needs no later one.
            if (automatic && candidates.firstOrNull()?.let { lyrics -> WindowsRichLyrics.parse(lyrics).any { it.words.isNotEmpty() } } == true) {
                pending.drop(index + 1).forEach { it.cancel() }
                break
            }
        }
        PaxsenixFetch(collected.distinct(), busy)
    }

    private suspend fun fetchBestAcross(
        tracks: List<Track>,
        fetch: suspend (Track) -> String?,
    ): String? = coroutineScope {
        tracks.map { query -> async { runCatching { fetch(query) }.getOrNull() } }
            .awaitAll()
            .firstOrNull { !it.isNullOrBlank() }
    }

    /**
     * Lyrics of the matching Apple Music songs. The automatic lookup tries the
     * best matches one by one and stops at the first word-timed lyric; the
     * manual search collects more versions to choose from.
     */
    private suspend fun fetchPaxsenixCandidates(track: Track, automatic: Boolean): PaxsenixFetch {
        val results = mutableListOf<String>()
        var busy = false
        val finished = withTimeoutOrNull(PAXSENIX_TOTAL_TIMEOUT_MS) {
            val knownId = synchronized(aliasAppleIds) { aliasAppleIds[aliasKey(track)] }
            val found = searchAppleMusic(track)
            val candidates = if (knownId == null || found.any { it.id == knownId }) found
            else listOf(AppleLyricsCandidate(knownId, track.title, track.artist, "", track.durationMs)) + found
            for (candidate in candidates.take(if (automatic) 3 else 6)) {
                val response = when (val reply = fetchPaxsenixResponse(candidate.id)) {
                    is PaxsenixReply.Found -> reply.json
                    PaxsenixReply.Busy -> { busy = true; continue }
                    PaxsenixReply.Missing -> continue
                }
                // A single Paxsenix response may expose several representations.
                // Compare all of them instead of letting a line-synced TTML payload
                // hide a richer word-synced eLRC/content payload.
                val parsed = runCatching {
                    listOfNotNull(
                        response["ttmlContent"]?.jsonPrimitive?.contentOrNull?.takeIf(String::isNotBlank)
                            ?.let(WindowsRichLyrics::ttmlToEnhancedLrc)?.takeIf(String::isNotBlank),
                        response["elrcMultiPerson"]?.jsonPrimitive?.contentOrNull?.takeIf(String::isNotBlank),
                        response["elrc"]?.jsonPrimitive?.contentOrNull?.takeIf(String::isNotBlank),
                        paxsenixContentToLrc(response)?.takeIf(String::isNotBlank),
                        response["plain"]?.jsonPrimitive?.contentOrNull?.takeIf(String::isNotBlank),
                    ).maxByOrNull(::lyricsTimingPriority)
                }.getOrNull()
                if (parsed != null) {
                    synchronized(results) { results += parsed }
                    if (automatic && WindowsRichLyrics.parse(parsed).any { it.words.isNotEmpty() }) break
                }
            }
        }
        // Running out of time also counts as busy (Paxsenix did not answer).
        if (finished == null) busy = true
        return PaxsenixFetch(synchronized(results) { results.distinct().sortedByDescending(::lyricsTimingPriority) }, busy)
    }

    /**
     * One Paxsenix lyric response, retried a few times while the service says
     * it is busy. A busy service (or no answer at all) is reported as such, so
     * it is asked again later instead of being taken as "no lyrics".
     */
    private suspend fun fetchPaxsenixResponse(id: String): PaxsenixReply {
        repeat(5) { attempt ->
            val response = runCatching {
                client.get("https://lyrics.paxsenix.org/apple-music/lyrics") {
                    parameter("id", id)
                    header("User-Agent", "RecordLabs-Windows/0.1.1")
                    timeout {
                        requestTimeoutMillis = PAXSENIX_REQUEST_TIMEOUT_MS
                        socketTimeoutMillis = PAXSENIX_REQUEST_TIMEOUT_MS
                    }
                }
            }.getOrNull()
            // A cancelled lookup stops here instead of counting as busy.
            currentCoroutineContext().ensureActive()
            when (response?.status?.value) {
                200 -> return runCatching { response!!.body<JsonObject>() }.getOrNull()
                    ?.let { PaxsenixReply.Found(it) } ?: PaxsenixReply.Missing
                null, 429, 502, 503, 504 -> if (attempt < 4) delay(2_000L + 1_000L * attempt)
                else -> return PaxsenixReply.Missing
            }
        }
        return PaxsenixReply.Busy
    }

    /** Word-timed lyrics always outrank line-only lyrics; coverage breaks ties. */
    internal fun lyricsTimingPriority(lyrics: String): Int {
        val lines = WindowsRichLyrics.parse(lyrics)
        val wordTimedLines = lines.count { it.words.isNotEmpty() }
        val timedWords = lines.sumOf { it.words.size }
        // Payloads carrying real [mm:ss.xx] stamps must always outrank untimed
        // plain text, whose rows are only synthetic 3-second steps.
        val hasLineTimestamps = Regex("(?m)^\\s*\\[\\d{1,3}:\\d{2}[.:]\\d{2,3}\\]").containsMatchIn(lyrics)
        return (if (timedWords > 0) 1_000_000 else 0) +
            (if (hasLineTimestamps) 500_000 else 0) +
            wordTimedLines * 1_000 + timedWords * 10 + lines.size
    }

    /**
     * Non-Latin metadata is queried in a Latin alias and its original form
     * concurrently. Results are consumed in this same order, so an English or
     * Latin-title match wins and the native-script title remains the fallback.
     * The artist travels with every query and is validated by each provider.
     */
    internal fun lyricsQueryVariants(track: Track): List<Track> {
        val original = track.copy(title = cleanTitle(track.title))
        val hasNonLatin = (original.title + original.artist).hasNonLatinLetter()
        if (!hasNonLatin) return listOf(original)

        fun latinAlias(value: String): String? {
            val bracketAlias = Regex("[\\[(（【]([^\\]\\)）】]+)[\\]\\)）】]")
                .findAll(value)
                .map { it.groupValues[1].trim() }
                .firstOrNull { alias -> alias.length >= 2 && Regex("[A-Za-z]").containsMatchIn(alias) }
            return bracketAlias ?: WindowsRomanizer.romanize(value)?.takeIf(String::isNotBlank)
        }

        val latinTitle = latinAlias(original.title) ?: original.title
        val latinArtist = latinAlias(original.artist) ?: original.artist
        val latin = original.copy(title = latinTitle, artist = latinArtist)
        return listOf(latin, original).distinctBy {
            canonicalTitle(it.title) to it.artist.lowercase(Locale.ROOT).trim()
        }
    }

    /**
     * Query variants with the song's English title and artist (as Apple Music
     * lists it for non-Latin songs, e.g. 晴天 / 周杰倫 -> Sunny Day / Jay Chou)
     * first, then the original title and artist. Both are fetched at the same
     * time and the English one wins when both match. Without an English
     * title the romanized or bracketed alias is used, as before.
     */
    private suspend fun resolvedQueryVariants(track: Track): List<Track> {
        val variants = lyricsQueryVariants(track)
        val original = variants.last()
        if (variants.size < 2) return variants
        val english = runCatching { withTimeoutOrNull(6_000L) { englishAlias(original) } }.getOrNull() ?: return variants
        return listOf(english, original).distinctBy {
            canonicalTitle(it.title) to it.artist.lowercase(Locale.ROOT).trim()
        }
    }

    private suspend fun englishAlias(original: Track): Track? {
        val key = aliasKey(original)
        synchronized(englishAliases) { if (englishAliases.containsKey(key)) return englishAliases[key] }
        val alias = findEnglishAlias(original)
        synchronized(englishAliases) { englishAliases[key] = alias }
        return alias
    }

    private suspend fun findEnglishAlias(original: Track): Track? {
        val text = original.title + original.artist
        // Apple Music names the same songs in the script's own language here.
        val nativeLanguage = when {
            text.hasScript(Character.UnicodeScript.HANGUL) -> "ko"
            text.hasScript(Character.UnicodeScript.HIRAGANA, Character.UnicodeScript.KATAKANA) -> "ja"
            text.hasScript(Character.UnicodeScript.HAN) -> "zh-Hant-TW"
            text.hasScript(Character.UnicodeScript.CYRILLIC) -> "ru"
            text.hasScript(Character.UnicodeScript.ARABIC) -> "ar"
            else -> return null
        }
        var token = appleMusicToken ?: fetchAppleMusicToken().also { appleMusicToken = it }
        // A failed request is not remembered as "no English title".
        var response = appleSearch(token, "${original.title} ${original.artist}")
        if (response == null || response.containsKey("errors")) {
            token = fetchAppleMusicToken().also { appleMusicToken = it }
            response = appleSearch(token, "${original.title} ${original.artist}")
        }
        val root = response?.takeIf { !it.containsKey("errors") && it.containsKey("results") } ?: error("Apple Music search failed")
        val resources = root["resources"]?.jsonObject?.get("songs")?.jsonObject ?: return null
        val ids = root["results"]?.jsonObject?.get("songs")?.jsonObject?.get("data")?.jsonArray
            ?.mapNotNull { it.jsonObject["id"]?.jsonPrimitive?.contentOrNull }
            .orEmpty()
            .take(10)
        if (ids.isEmpty()) return null
        val native = appleSongs(token, ids, nativeLanguage) ?: error("Apple Music lookup failed")
        fun comparable(value: String) = hanToTraditional(value)
        val best = ids.mapNotNull { id ->
            val attributes = native[id] ?: return@mapNotNull null
            val nativeTitle = attributes["name"]?.jsonPrimitive?.contentOrNull.orEmpty()
            val nativeArtist = attributes["artistName"]?.jsonPrimitive?.contentOrNull.orEmpty()
            if (!titlesReferToSameSong(comparable(original.title), comparable(nativeTitle))) return@mapNotNull null
            if (!artistsReferToSameArtist(comparable(original.artist), comparable(nativeArtist))) return@mapNotNull null
            val english = resources[id]?.jsonObject?.get("attributes")?.jsonObject ?: return@mapNotNull null
            val durationMs = english["durationInMillis"]?.jsonPrimitive?.longOrNull ?: 0L
            val closeness = if (original.durationMs > 0 && durationMs > 0) abs(original.durationMs - durationMs) else 60_000L
            AliasMatch(id, english, closeness, canonicalTitle(nativeTitle) == canonicalTitle(original.title))
        }.minWithOrNull(compareBy<AliasMatch> { !it.exactTitle }.thenBy { it.durationGapMs }) ?: return null
        val latin = Regex("[A-Za-z]")
        val englishTitle = best.english["name"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
        val englishArtist = best.english["artistName"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
        // A part Apple keeps in its own script falls back to the earlier alias.
        val fallback = lyricsQueryVariants(original).first()
        val title = englishTitle.takeIf { latin.containsMatchIn(it) } ?: fallback.title
        val artist = englishArtist.takeIf { latin.containsMatchIn(it) } ?: fallback.artist
        val alias = original.copy(title = cleanTitle(title), artist = artist)
        // The matched song itself, for Paxsenix: its listing may not match a
        // romanized title when Apple keeps the original one.
        synchronized(aliasAppleIds) { aliasAppleIds[aliasKey(alias)] = best.id }
        return alias
    }

    private class AliasMatch(val id: String, val english: JsonObject, val durationGapMs: Long, val exactTitle: Boolean)

    private fun aliasKey(track: Track) = "${track.title}\u0000${track.artist}"

    private val hansToHant by lazy { runCatching { com.ibm.icu.text.Transliterator.getInstance("Hans-Hant") }.getOrNull() }

    /** Simplified Chinese as Traditional, so 后来 and 後來 compare as the same title. */
    private fun hanToTraditional(value: String): String =
        if (!value.hasScript(Character.UnicodeScript.HAN)) value
        else synchronized(hansToHant ?: return value) { hansToHant?.transliterate(value) ?: value }

    private suspend fun appleSongs(token: String, ids: List<String>, language: String): Map<String, JsonObject>? = runCatching {
        client.get("https://amp-api.music.apple.com/v1/catalog/us/songs") {
            parameter("ids", ids.joinToString(","))
            parameter("l", language)
            parameter("platform", "web")
            header("Authorization", "Bearer $token")
            header("Origin", "https://music.apple.com")
            header("Referer", "https://music.apple.com/")
            header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
        }.body<JsonObject>()["data"]?.jsonArray?.mapNotNull { item ->
            val song = item.jsonObject
            val id = song["id"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            val attributes = song["attributes"]?.jsonObject ?: return@mapNotNull null
            id to attributes
        }?.toMap()
    }.getOrNull()

    private suspend fun searchAppleMusic(track: Track): List<AppleLyricsCandidate> {
        var token = appleMusicToken ?: fetchAppleMusicToken().also { appleMusicToken = it }
        var response = appleSearch(token, "${track.title} ${track.artist}")
        if (response == null) {
            appleMusicToken = null
            token = fetchAppleMusicToken()
            appleMusicToken = token
            response = appleSearch(token, "${track.title} ${track.artist}")
        }
        val root = response ?: return emptyList()
        val resources = root["resources"]?.jsonObject?.get("songs")?.jsonObject ?: return emptyList()
        val ids = root["results"]?.jsonObject?.get("songs")?.jsonObject?.get("data")?.jsonArray
            ?.mapNotNull { it.jsonObject["id"]?.jsonPrimitive?.contentOrNull }
            .orEmpty()
        return ids.mapNotNull { id ->
            val attributes = resources[id]?.jsonObject?.get("attributes")?.jsonObject ?: return@mapNotNull null
            AppleLyricsCandidate(
                id = id,
                title = attributes["name"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                artist = attributes["artistName"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                album = attributes["albumName"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                durationMs = attributes["durationInMillis"]?.jsonPrimitive?.longOrNull ?: 0L,
            )
        }
            // Artist or duration similarity must never make a completely different
            // song title eligible (for example Revolving Door for Just Keep Watching).
            // The artist is equally authoritative: a same-title cover must not
            // borrow the original performer's lyric (or vice versa).
            .filter {
                (titlesReferToSameSong(track.title, it.title) || titlesReferToSameSong(hanToTraditional(track.title), hanToTraditional(it.title))) &&
                    (artistsReferToSameArtist(track.artist, it.artist) ||
                        artistsReferToSameArtist(hanToTraditional(track.artist), hanToTraditional(it.artist)))
            }
            .sortedByDescending { scoreCandidate(track, it) }
    }

    private suspend fun appleSearch(token: String, query: String): JsonObject? = runCatching {
        client.get("https://amp-api.music.apple.com/v1/catalog/us/search") {
            parameter("term", query)
            parameter("types", "songs")
            parameter("limit", 25)
            parameter("l", "en-US")
            parameter("platform", "web")
            parameter("format[resources]", "map")
            parameter("include[songs]", "artists")
            header("Authorization", "Bearer $token")
            header("Origin", "https://music.apple.com")
            header("Referer", "https://music.apple.com/")
            header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
        }.body<JsonObject>()
    }.getOrNull()

    /**
     * The music.apple.com web token, shared with other Apple Music lookups
     * (artist logos). [refresh] fetches a new one, e.g. after it expired.
     */
    suspend fun appleMusicWebToken(refresh: Boolean = false): String {
        if (!refresh) appleMusicToken?.let { return it }
        return fetchAppleMusicToken().also { appleMusicToken = it }
    }

    private suspend fun fetchAppleMusicToken(): String {
        val home = client.get("https://beta.music.apple.com").bodyAsText()
        val asset = Regex("/assets/index~[^/]+\\.js").find(home)?.value
            ?: error("Apple Music web token asset not found")
        val script = client.get("https://beta.music.apple.com$asset").bodyAsText()
        return Regex("eyJ[A-Za-z0-9_\\-=]+\\.[A-Za-z0-9_\\-=]+\\.[A-Za-z0-9_\\-=]+").find(script)?.value
            ?: error("Apple Music web token not found")
    }

    private fun scoreCandidate(track: Track, candidate: AppleLyricsCandidate): Int {
        val title = canonicalTitle(track.title)
        val candidateTitle = canonicalTitle(candidate.title)
        var score = 0
        if (candidateTitle == title) score += 100
        else if (candidateTitle.contains(title) || title.contains(candidateTitle)) score += 45
        if (artistsReferToSameArtist(track.artist, candidate.artist)) score += 90
        if (track.album.isNotBlank() && candidate.album.equals(track.album, true)) score += 45
        if (track.durationMs > 0 && candidate.durationMs > 0) {
            score += when (abs(track.durationMs - candidate.durationMs)) {
                in 0..2_000 -> 80
                in 2_001..5_000 -> 40
                in 5_001..10_000 -> 10
                else -> -60
            }
        }
        return score
    }

    private fun titlesReferToSameSong(expected: String, candidate: String): Boolean {
        val expectedTitle = canonicalTitle(expected)
        val candidateTitle = canonicalTitle(candidate)
        if (expectedTitle.isBlank() || candidateTitle.isBlank()) return false
        if (expectedTitle == candidateTitle) return true
        val expectedTokens = expectedTitle.split(' ').filter(String::isNotBlank).toSet()
        val candidateTokens = candidateTitle.split(' ').filter(String::isNotBlank).toSet()
        val shared = expectedTokens.intersect(candidateTokens).size
        val coverage = shared.toDouble() / maxOf(expectedTokens.size, candidateTokens.size)
        return coverage >= 0.85 && shared >= minOf(2, expectedTokens.size)
    }

    private fun canonicalTitle(value: String): String = cleanTitle(value)
        .replace(Regex("(?i)\\s*[\\[(](from|official|audio|video|lyrics?|visuali[sz]er|remaster(ed)?|explicit).*?[\\])]") , "")
        .lowercase(Locale.ROOT)
        .replace(Regex("[^\\p{L}\\p{N}]+"), " ")
        .trim()

    private fun canonicalArtist(value: String): String = java.text.Normalizer
        .normalize(value, java.text.Normalizer.Form.NFKD)
        .replace(Regex("\\p{M}+"), "")
        .lowercase(Locale.ROOT)
        .replace(Regex("[^\\p{L}\\p{N}]+"), " ")
        .trim()

    private fun paxsenixContentToLrc(response: JsonObject): String? {
        val content = response["content"] as? JsonArray ?: return null
        return buildString {
            content.forEach { item ->
                val line = item.jsonObject
                val start = line["timestamp"]?.jsonPrimitive?.longOrNull ?: return@forEach
                val wordItems = line["text"]?.jsonArray.orEmpty().mapNotNull { wordValue ->
                    val word = wordValue.jsonObject
                    val text = word["text"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                    val wordStart = word["timestamp"]?.jsonPrimitive?.longOrNull ?: start
                    val wordEnd = word["endtime"]?.jsonPrimitive?.longOrNull ?: wordStart + 500
                    TimedLyricWord(text, wordStart, wordEnd)
                }
                val text = wordItems.joinToString(" ") { it.text }.trim()
                val marker = when {
                    line["background"]?.jsonPrimitive?.contentOrNull == "true" -> "{bg}"
                    line["oppositeTurn"]?.jsonPrimitive?.contentOrNull == "true" -> "{agent:v2}"
                    else -> "{agent:v1}"
                }
                if (text.isNotBlank()) {
                    append(formatTime(start)).append(marker).append(text).append('\n')
                    if (wordItems.isNotEmpty()) {
                        append('<')
                        wordItems.forEachIndexed { index, word ->
                            append(word.text).append(':').append(word.startMs / 1000.0).append(':').append(word.endMs / 1000.0)
                            if (index < wordItems.lastIndex) append('|')
                        }
                        append(">\n")
                    }
                }

                // Paxsenix can attach backing vocals to a main content row via
                // backgroundText. Android turns this into another LyricsEntry;
                // emitting it inline would make positioning impossible.
                val backgroundItems = line["backgroundText"]?.jsonArray.orEmpty().mapNotNull { wordValue ->
                    val word = wordValue.jsonObject
                    val backgroundWord = word["text"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                    val wordStart = word["timestamp"]?.jsonPrimitive?.longOrNull ?: start
                    val wordEnd = word["endtime"]?.jsonPrimitive?.longOrNull ?: wordStart + 500
                    TimedLyricWord(backgroundWord, wordStart, wordEnd)
                }
                val backgroundText = backgroundItems.joinToString(" ") { it.text }.trim()
                if (backgroundText.isNotBlank()) {
                    val backgroundStart = backgroundItems.minOfOrNull(TimedLyricWord::startMs) ?: start
                    append(formatTime(backgroundStart)).append("{bg}").append(backgroundText).append('\n')
                    append('<')
                    backgroundItems.forEachIndexed { index, word ->
                        append(word.text).append(':').append(word.startMs / 1000.0).append(':').append(word.endMs / 1000.0)
                        if (index < backgroundItems.lastIndex) append('|')
                    }
                    append(">\n")
                }
            }
        }
    }

    private suspend fun fetchBetterLyrics(track: Track): String? = withTimeoutOrNull(15_000L) {
        runCatching {
            val response: JsonObject = client.get("https://lyrics-api.boidu.dev/getLyrics") {
                parameter("s", track.title)
                parameter("a", track.artist)
                if (track.durationMs > 0) parameter("d", track.durationMs / 1000)
                if (track.album.isNotBlank()) parameter("al", track.album)
                header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64)")
            }.body()
            response["ttml"]?.jsonPrimitive?.contentOrNull?.let(WindowsRichLyrics::ttmlToEnhancedLrc)
        }.getOrNull()
    }

    // Musixmatch (third provider, after Paxsenix and BetterLyrics). Uses the
    // Android player identity: an anonymous user token, then the matched
    // song's word-timed "richsync", else its line-timed subtitles. Plain
    // (untimed) lyrics are only offered in the manual search, so they never
    // win over a synced lyric of a later provider.
    private const val MUSIXMATCH_BASE = "https://apic.musixmatch.com/ws/1.1/"
    private const val MUSIXMATCH_APP_ID = "android-player-v1.0"
    private const val MUSIXMATCH_USER_AGENT = "Dalvik/2.1.0 (Linux; U; Android 13; Pixel 6 Build/TQ3A.230805.001)"
    /** A refused token request (rate limit / captcha) is not repeated for this long. */
    private const val MUSIXMATCH_MINT_BACKOFF_MS = 10 * 60_000L
    @Volatile private var musixmatchUserToken: String? = null
    @Volatile private var musixmatchMintBlockedUntil = 0L
    private val musixmatchTokenLock = kotlinx.coroutines.sync.Mutex()
    private val musixmatchTokenFile: File get() = File(WindowsStorage.directory, "musixmatch-token.txt")

    private class MusixmatchMessage(val status: Int, val hint: String?, val body: JsonObject?)
    private class MusixmatchTrack(val commontrackId: Long, val hasRichsync: Boolean, val hasSubtitles: Boolean, val hasLyrics: Boolean)

    /** Musixmatch hands out an all-zero (or similar) dummy token when it refuses. */
    private fun validMusixmatchToken(value: String?): Boolean =
        value != null && value.length >= 20 && value.toSet().size > 2

    private suspend fun musixmatchToken(renewing: String? = null): String? = musixmatchTokenLock.withLock {
        val known = musixmatchUserToken
        if (renewing == null) {
            if (validMusixmatchToken(known)) return@withLock known
            runCatching { musixmatchTokenFile.readText().trim() }.getOrNull()
                ?.takeIf(::validMusixmatchToken)?.let { musixmatchUserToken = it; return@withLock it }
        } else if (validMusixmatchToken(known) && known != renewing) {
            // Another request renewed it meanwhile.
            return@withLock known
        }
        if (System.currentTimeMillis() < musixmatchMintBlockedUntil) return@withLock null
        val guid = java.util.UUID.randomUUID().toString().replace("-", "").take(16)
        val minted = musixmatchCall("token.get", null, "guid" to guid)
            ?.takeIf { it.status == 200 }?.body?.get("user_token")?.jsonPrimitive?.contentOrNull
        if (!validMusixmatchToken(minted)) {
            musixmatchMintBlockedUntil = System.currentTimeMillis() + MUSIXMATCH_MINT_BACKOFF_MS
            return@withLock null
        }
        musixmatchUserToken = minted
        runCatching { musixmatchTokenFile.writeText(minted!!) }
        minted
    }

    private suspend fun musixmatchCall(method: String, token: String?, vararg query: Pair<String, Any>): MusixmatchMessage? = runCatching {
        val text = client.get(MUSIXMATCH_BASE + method) {
            parameter("app_id", MUSIXMATCH_APP_ID)
            parameter("format", "json")
            if (token != null) parameter("usertoken", token)
            query.forEach { (key, value) -> parameter(key, value) }
            header("User-Agent", MUSIXMATCH_USER_AGENT)
        }.bodyAsText()
        val message = jsonCodec.parseToJsonElement(text).jsonObject["message"] as? JsonObject ?: return@runCatching null
        val header = message["header"] as? JsonObject
        MusixmatchMessage(
            status = header?.get("status_code")?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 0,
            hint = header?.get("hint")?.jsonPrimitive?.contentOrNull,
            // An empty answer comes as [] instead of an object.
            body = message["body"] as? JsonObject,
        )
    }.getOrNull()

    /** A call with the user token, renewing it once when Musixmatch says it expired. */
    private suspend fun musixmatchAuthorized(method: String, vararg query: Pair<String, Any>): MusixmatchMessage? {
        val token = musixmatchToken() ?: return null
        val answer = musixmatchCall(method, token, *query) ?: return null
        if (answer.status != 401 || answer.hint != "renew") return answer
        val renewed = musixmatchToken(renewing = token) ?: return null
        return musixmatchCall(method, renewed, *query)
    }

    private suspend fun musixmatchMatch(track: Track): MusixmatchTrack? {
        val query = buildList<Pair<String, Any>> {
            add("q_track" to track.title)
            add("q_artist" to track.artist)
            if (track.durationMs > 0) add("q_duration" to track.durationMs / 1000)
        }
        val answer = musixmatchAuthorized("matcher.track.get", *query.toTypedArray())?.takeIf { it.status == 200 } ?: return null
        val item = answer.body?.get("track") as? JsonObject ?: return null
        fun text(key: String) = item[key]?.jsonPrimitive?.contentOrNull.orEmpty()
        fun flag(key: String) = text(key) == "1"
        if (flag("instrumental")) return null
        // The same song by the same artist, as for every other provider.
        if (!titlesReferToSameSong(track.title, text("track_name")) &&
            !titlesReferToSameSong(hanToTraditional(track.title), hanToTraditional(text("track_name")))
        ) return null
        if (!artistsReferToSameArtist(track.artist, text("artist_name")) &&
            !artistsReferToSameArtist(hanToTraditional(track.artist), hanToTraditional(text("artist_name")))
        ) return null
        val lengthSec = text("track_length").toLongOrNull() ?: 0L
        if (track.durationMs > 0 && lengthSec > 0 && abs(track.durationMs / 1000 - lengthSec) > 15) return null
        val id = text("commontrack_id").toLongOrNull() ?: return null
        return MusixmatchTrack(id, flag("has_richsync"), flag("has_subtitles"), flag("has_lyrics"))
    }

    private suspend fun fetchMusixmatchLyrics(track: Track): String? =
        fetchMusixmatchCandidates(track, includePlain = false).firstOrNull()

    /** Word-timed first, then line-timed, then (manual search only) plain lyrics. */
    private suspend fun fetchMusixmatchCandidates(track: Track, includePlain: Boolean): List<String> = withTimeoutOrNull(15_000L) {
        val match = musixmatchMatch(track) ?: return@withTimeoutOrNull emptyList()
        val found = mutableListOf<String>()
        if (match.hasRichsync) {
            (musixmatchAuthorized("track.richsync.get", "commontrack_id" to match.commontrackId)
                ?.takeIf { it.status == 200 }?.body?.get("richsync") as? JsonObject)
                ?.get("richsync_body")?.jsonPrimitive?.contentOrNull
                ?.let(::musixmatchRichsyncToLrc)?.takeIf(String::isNotBlank)?.let(found::add)
        }
        if (match.hasSubtitles && (found.isEmpty() || includePlain)) {
            (musixmatchAuthorized("track.subtitle.get", "commontrack_id" to match.commontrackId, "subtitle_format" to "lrc")
                ?.takeIf { it.status == 200 }?.body?.get("subtitle") as? JsonObject)
                ?.get("subtitle_body")?.jsonPrimitive?.contentOrNull
                ?.takeIf { Regex("(?m)^\\s*\\[\\d{1,3}:\\d{2}[.:]\\d{2,3}\\]").containsMatchIn(it) }
                ?.let(found::add)
        }
        if (includePlain && match.hasLyrics) {
            val lyrics = musixmatchAuthorized("track.lyrics.get", "commontrack_id" to match.commontrackId)
                ?.takeIf { it.status == 200 }?.body?.get("lyrics") as? JsonObject
            if (lyrics != null && lyrics["restricted"]?.jsonPrimitive?.contentOrNull != "1") {
                lyrics["lyrics_body"]?.jsonPrimitive?.contentOrNull
                    ?.lines()
                    // Musixmatch appends a usage notice and a tracking number.
                    ?.filterNot { line -> line.trim().startsWith("*******") || Regex("^\\(\\d{6,}\\)$").matches(line.trim()) }
                    ?.joinToString("\n")?.trim()?.takeIf(String::isNotBlank)?.let(found::add)
            }
        }
        found.distinct()
    } ?: emptyList()

    /**
     * Musixmatch richsync (lines with per-character-group offsets) in the
     * app's enhanced LRC: a "[mm:ss.xx]line" row followed by its
     * "<word:start:end|...>" timing row, as produced for TTML lyrics.
     */
    internal fun musixmatchRichsyncToLrc(body: String): String? = runCatching {
        val rows = jsonCodec.parseToJsonElement(body).jsonArray
        buildString {
            for (row in rows) {
                val line = row as? JsonObject ?: continue
                val startSec = line["ts"]?.jsonPrimitive?.contentOrNull?.toDoubleOrNull() ?: continue
                val endSec = line["te"]?.jsonPrimitive?.contentOrNull?.toDoubleOrNull() ?: startSec
                val parts = (line["l"] as? JsonArray).orEmpty().mapNotNull { value ->
                    val part = value as? JsonObject ?: return@mapNotNull null
                    val chars = part["c"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                    val offset = part["o"]?.jsonPrimitive?.contentOrNull?.toDoubleOrNull() ?: return@mapNotNull null
                    chars to offset
                }
                val text = (line["x"]?.jsonPrimitive?.contentOrNull ?: parts.joinToString("") { it.first })
                    .replace(Regex("\\s+"), " ").trim()
                if (text.isBlank()) continue
                val words = parts.mapIndexedNotNull { index, (chars, offset) ->
                    val word = chars.trim().replace('|', '/').replace('<', '(').replace('>', ')')
                    if (word.isEmpty()) return@mapIndexedNotNull null
                    val start = ((startSec + offset) * 1000).toLong()
                    val end = (parts.getOrNull(index + 1)?.let { startSec + it.second } ?: endSec).times(1000).toLong()
                    TimedLyricWord(word, start, end.coerceAtLeast(start + 40L))
                }
                val lineStartMs = (startSec * 1000).toLong()
                append(String.format(Locale.US, "[%02d:%02d.%02d]", lineStartMs / 60_000, (lineStartMs / 1_000) % 60, (lineStartMs % 1_000) / 10))
                append(text).append('\n')
                if (words.isNotEmpty()) {
                    append('<')
                    words.forEachIndexed { index, word ->
                        append(word.text).append(':').append(word.startMs / 1000.0).append(':').append(word.endMs / 1000.0)
                        if (index < words.lastIndex) append('|')
                    }
                    append(">\n")
                }
            }
        }.trim().takeIf(String::isNotBlank)
    }.getOrNull()

    private suspend fun fetchLrcLibLyrics(title: String, artist: String, durationSec: Long): String? = withTimeoutOrNull(6_000L) {
        runCatching {
            val direct: LrcLibResponse = client.get("https://lrclib.net/api/get") {
                parameter("track_name", title)
                parameter("artist_name", artist)
                if (durationSec > 0) parameter("duration", durationSec)
            }.body()
            if (!direct.trackName.isNullOrBlank() && !titlesReferToSameSong(title, direct.trackName)) {
                return@runCatching null
            }
            if (!direct.artistName.isNullOrBlank() && !artistsReferToSameArtist(artist, direct.artistName)) {
                return@runCatching null
            }
            direct.syncedLyrics?.takeIf(String::isNotBlank) ?: direct.plainLyrics?.takeIf(String::isNotBlank)
        }.getOrNull()
    }

    private suspend fun fetchLrcLibCandidates(title: String, artist: String): List<String> = withTimeoutOrNull(8_000L) {
        runCatching {
            client.get("https://lrclib.net/api/search") {
                parameter("track_name", title)
                parameter("artist_name", artist)
            }.body<JsonArray>().mapNotNull { value ->
                val item = value.jsonObject
                val resultTitle = item["trackName"]?.jsonPrimitive?.contentOrNull.orEmpty()
                val resultArtist = item["artistName"]?.jsonPrimitive?.contentOrNull.orEmpty()
                if (!titlesReferToSameSong(title, resultTitle) || !artistsReferToSameArtist(artist, resultArtist)) {
                    return@mapNotNull null
                }
                item["syncedLyrics"]?.jsonPrimitive?.contentOrNull?.takeIf(String::isNotBlank)
                    ?: item["plainLyrics"]?.jsonPrimitive?.contentOrNull?.takeIf(String::isNotBlank)
            }.distinct().take(12)
        }.getOrDefault(emptyList())
    } ?: emptyList()

    private suspend fun fetchKuGouLyrics(title: String, artist: String): String? =
        fetchKuGouCandidates(title, artist).firstOrNull()

    private suspend fun fetchKuGouCandidates(title: String, artist: String): List<String> = withTimeoutOrNull(10_000L) {
        runCatching {
            val response: JsonObject = client.get("http://krcs.kugou.com/search") {
                parameter("ver", 1); parameter("man", "yes"); parameter("client", "mobi")
                parameter("keyword", "$artist - $title"); parameter("duration", 0); parameter("hash", "")
            }.body()
            response["candidates"]?.jsonArray.orEmpty().take(10).mapNotNull { value ->
                val candidate = value.jsonObject
                val resultTitle = candidate["song"]?.jsonPrimitive?.contentOrNull
                    ?: candidate["songname"]?.jsonPrimitive?.contentOrNull
                    ?: candidate["filename"]?.jsonPrimitive?.contentOrNull?.substringAfter(" - ")
                val resultArtist = candidate["singer"]?.jsonPrimitive?.contentOrNull
                    ?: candidate["singername"]?.jsonPrimitive?.contentOrNull
                    ?: candidate["filename"]?.jsonPrimitive?.contentOrNull?.substringBefore(" - ")
                if (!resultTitle.isNullOrBlank() && !titlesReferToSameSong(title, resultTitle)) return@mapNotNull null
                if (!resultArtist.isNullOrBlank() && !artistsReferToSameArtist(artist, resultArtist)) return@mapNotNull null
                val id = candidate["id"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                val accessKey = candidate["accesskey"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                runCatching {
                    val lyrics: JsonObject = client.get("http://lyrics.kugou.com/download") {
                        parameter("ver", 1); parameter("client", "pc"); parameter("id", id)
                        parameter("accesskey", accessKey); parameter("fmt", "lrc"); parameter("charset", "utf8")
                    }.body()
                    val encoded = lyrics["content"]?.jsonPrimitive?.contentOrNull ?: return@runCatching null
                    String(java.util.Base64.getDecoder().decode(encoded), Charsets.UTF_8)
                }.getOrNull()?.takeIf(String::isNotBlank)
            }.distinct()
        }.getOrDefault(emptyList())
    } ?: emptyList()

    private suspend fun fetchYouTubeLyrics(mediaId: String): String? = withTimeoutOrNull(6_000L) {
        runCatching {
            fallbackRepository.lyrics(Track(id = mediaId, title = "", artist = ""))
                .joinToString("\n") { it.text }
    }.getOrNull()
    }

    internal fun artistsReferToSameArtist(expected: String, candidate: String): Boolean {
        val expectedArtist = canonicalArtist(expected)
        val candidateArtist = canonicalArtist(candidate)
        if (expectedArtist.isBlank()) return true
        if (candidateArtist.isBlank()) return false
        if (expectedArtist == candidateArtist) return true

        fun parts(value: String): List<Set<String>> = value
            .split(Regex("(?i)\\s+(?:feat\\.?|featuring|with|x)\\s+|[&,;/×]+"))
            .map { part ->
                canonicalArtist(part).split(' ')
                    .filter { token -> token.isNotBlank() && token !in setOf("the", "official", "topic", "vevo") }
                    .toSet()
            }
            .filter(Set<String>::isNotEmpty)

        fun partMatches(left: Set<String>, right: Set<String>): Boolean {
            if (left == right) return true
            val shared = left.intersect(right).size
            if (shared == 0) return false
            // Single-word artist names (BTS, LANY, Adele, etc.) must match
            // exactly; one shared generic word can no longer validate an
            // unrelated artist.
            if (left.size == 1 || right.size == 1) return false
            val leftCoverage = shared.toDouble() / left.size
            val rightCoverage = shared.toDouble() / right.size
            return leftCoverage >= 0.8 && rightCoverage >= 0.6
        }

        val expectedParts = parts(expected)
        val candidateParts = parts(candidate)
        if (expectedParts.isEmpty() || candidateParts.isEmpty()) return false
        // Candidate metadata may append a featured performer, but every artist
        // explicitly present in the requested track must still be represented.
        return expectedParts.all { wanted -> candidateParts.any { found -> partMatches(wanted, found) } }
    }

    private fun formatTime(timeMs: Long): String = String.format(
        Locale.US, "[%02d:%02d.%02d]", timeMs / 60_000, (timeMs / 1_000) % 60, (timeMs % 1_000) / 10,
    )
}
