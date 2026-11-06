package com.recordlabs.desktop.data

import com.recordlabs.desktop.platform.*

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.timeout
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.cancel
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

data class GoogleAccountProfile(
    val name: String,
    val photoUrl: String?,
    val email: String? = null,
    val channelHandle: String? = null,
)
data class GoogleLibrarySnapshot(
    val liked: List<Track>,
    val playlists: List<WindowsPlaylist>,
)

class WindowsMusicRepository : CatalogRepository, LyricsRepository, AutoCloseable {
    private data class CachedStream(val url: String, val userAgent: String, val validUntilMs: Long)
    /** OkHttp's own User-Agent: what the original Android app streams with. */
    private val ANDROID_STREAM_USER_AGENT = "okhttp/4.12.0"
    private data class CachedSearch(val value: SearchResults, val validUntilMs: Long)
    private data class CachedHome(val value: HomeFeed, val validUntilMs: Long)
    private data class PlaybackClient(
        val name: String,
        val version: String,
        val id: String,
        val userAgent: String,
        val contextExtras: String = "",
        /**
         * Web/TV clients return URLs whose `n` (and sometimes signature) must be
         * solved with player.js. Mobile/VR/visionOS URLs are served directly and
         * never need it, so they must not wait on the player.js/Deno warm-up.
         */
        val requiresJsPlayer: Boolean = true,
        /** Sends an anonymous visitor id, which VISIONOS needs to return formats. */
        val sendVisitorData: Boolean = false,
    )

    private val streamCache = concurrentMapOf<String, CachedStream>()
    private val searchCache = concurrentMapOf<String, CachedSearch>()
    private val homeCache = concurrentMapOf<String, CachedHome>()
    private val client = HttpClient(ktorEngine()) {
        install(HttpTimeout) {
            requestTimeoutMillis = 12_000
            connectTimeoutMillis = 8_000
            socketTimeoutMillis = 12_000
        }
        install(ContentNegotiation) {
            json(Json { ignoreUnknownKeys = true; isLenient = true })
        }
    }

    override suspend fun home(): HomeFeed = home(params = null)

    // Settings > Content > Hide explicit songs: applied to what the public calls return.
    private val hidingExplicit: Boolean get() = AppSettings.hideExplicit.value
    private fun List<Track>.withoutExplicit(): List<Track> = if (hidingExplicit) filterNot(Track::explicit) else this
    private fun HomeFeed.withoutExplicit(): HomeFeed = if (!hidingExplicit) this else copy(
        shelves = shelves.map { shelf -> shelf.copy(items = shelf.items.filterNot { it.track?.explicit == true }) }
            .filter { it.items.isNotEmpty() },
    )
    private fun SearchResults.withoutExplicit(): SearchResults = if (!hidingExplicit) this else copy(
        tracks = tracks.withoutExplicit(),
        summaries = summaries.map { it.copy(items = it.items.withoutExplicit()) }.filter { it.items.isNotEmpty() },
        topResult = topResult?.takeUnless { it.track?.explicit == true },
    )
    private fun CatalogPageData.withoutExplicit(): CatalogPageData = if (!hidingExplicit) this else copy(
        tracks = tracks.withoutExplicit(),
        shelves = shelves.map { shelf -> shelf.copy(items = shelf.items.filterNot { (it as? Track)?.explicit == true }) },
    )

    // The public calls below run on a background thread: the replies are
    // downloaded and parsed where the call runs, which kept the UI busy.

    /** Mirrors Android's YouTube.home(params): one canonical home request, including server chips. */
    suspend fun home(params: String?): HomeFeed =
        withContext(IoDispatcher) { homeOnCaller(params).withoutExplicit() }

    private suspend fun homeOnCaller(params: String?): HomeFeed {
        // Region and "More content" are part of the key: changing them must not show the old Home.
        val cacheKey = "${params.orEmpty()}|${AppSettings.contentRegion.value}|${WindowsPreferences.moreContent()}"
        homeCache[cacheKey]?.takeIf { it.validUntilMs > currentTimeMillis() }?.let { return it.value }
        val response = client.postJson(
            "https://music.youtube.com/youtubei/v1/browse?key=$API_KEY",
            browseBody(browseId = "FEmusic_home", params = params),
            // Profile > More content off: Home without the account's personal picks.
            withAccount = WindowsPreferences.moreContent(),
        )
        saveHomeResponseForDebug(response, firstPage = params == null)
        return response.toHomeFeed().also {
            homeCache[cacheKey] = CachedHome(it, currentTimeMillis() + 10 * 60_000L)
        }
    }

    /**
     * The last Home reply as YouTube sent it (all pages), to see which sections an
     * account gets (e.g. concerts near you) and how they are built:
     * <data folder>/home-feed-debug.json.
     */
    private fun saveHomeResponseForDebug(response: JsonObject, firstPage: Boolean) {
        runCatching {
            val file = File(AppPlatform.dataRoot, "home-feed-debug.json")
            if (firstPage) file.writeText(response.toString() + "\n")
            else if (file.length() < 8_000_000L) file.appendText(response.toString() + "\n")
        }
    }

    suspend fun homeContinuation(token: String): HomeFeed =
        withContext(IoDispatcher) { homeContinuationOnCaller(token).withoutExplicit() }

    private suspend fun homeContinuationOnCaller(token: String): HomeFeed {
        val response = client.postJson(
            "https://music.youtube.com/youtubei/v1/browse?key=$API_KEY",
            browseBody(continuation = token),
            withAccount = WindowsPreferences.moreContent(),
        )
        saveHomeResponseForDebug(response, firstPage = false)
        return response.toHomeFeed()
    }

    suspend fun homeSupplement(): List<HomeShelf> =
        withContext(IoDispatcher) { HomeFeed(shelves = homeSupplementOnCaller()).withoutExplicit().shelves }

    private suspend fun homeSupplementOnCaller(): List<HomeShelf> = coroutineScope {
        val explore = async {
            runCatching {
                val response = client.postJson(
                    "https://music.youtube.com/youtubei/v1/browse?key=$API_KEY",
                    browseBody(browseId = "FEmusic_explore"),
                )
                response.homeShelves()
            }.getOrDefault(emptyList())
        }
        explore.await().distinctBy { it.title }
    }

    /** Desktop equivalent of Android's DB-backed Quick Picks, Daily Discover and Keep Listening. */
    suspend fun personalizedHome(history: List<Track>, liked: List<Track>): List<HomeShelf> =
        withContext(IoDispatcher) { personalizedHomeOnCaller(history, liked) }

    private suspend fun personalizedHomeOnCaller(history: List<Track>, liked: List<Track>): List<HomeShelf> = coroutineScope {
        val recent = history.distinctBy { it.id }.take(KEEP_LISTENING_SIZE)
        // One radio per seed, shared when Quick picks and Daily discover use the same song.
        val radios = HashMap<String, kotlinx.coroutines.Deferred<List<Track>>>()
        fun radioFor(seedId: String) = radios.getOrPut(seedId) {
            async { runCatching { nextRadioQueue(seedId) }.getOrDefault(emptyList()) }
        }
        val quickPicksRadio = recent.firstOrNull()?.let { seed -> radioFor(seed.id) }
        // A few discoveries from each recently liked song (topped up with recent
        // plays when only a few songs are liked), taken in turns so one song
        // does not fill the whole shelf.
        val likedSeeds = liked.distinctBy { it.id }
        val seeds = (likedSeeds.take(DAILY_DISCOVER_SEEDS) + recent.take(DAILY_DISCOVER_SEEDS))
            .distinctBy { it.id }
            .take(DAILY_DISCOVER_SEEDS)
        val likedIds = likedSeeds.mapTo(HashSet()) { it.id }
        val discoveries = seeds.map { seed -> seed to radioFor(seed.id) }.map { (seed, radio) ->
            radio.await()
                .filter { it.id != seed.id && it.id !in likedIds }
                .take(DAILY_DISCOVER_PER_SEED)
        }
        val dailyDiscover = buildList {
            for (round in 0 until DAILY_DISCOVER_PER_SEED) discoveries.forEach { list -> list.getOrNull(round)?.let(::add) }
        }.distinctBy { it.id }.take(DAILY_DISCOVER_SIZE)

        buildList {
            quickPicksRadio?.await().orEmpty().take(QUICK_PICKS_SIZE).takeIf { it.isNotEmpty() }?.let { tracks ->
                add(HomeShelf("Quick picks", tracks.map { it.toHomeItem() }, HomeShelfLayout.List))
            }
            dailyDiscover.takeIf { it.isNotEmpty() }?.let { tracks ->
                add(HomeShelf("Daily discover", tracks.map { it.toHomeItem() }))
            }
            recent.takeIf { it.isNotEmpty() }?.let { tracks ->
                add(HomeShelf("Keep listening", tracks.map { it.toHomeItem() }))
            }
        }
    }

    override suspend fun search(query: String, filter: String?): SearchResults =
        withContext(IoDispatcher) { searchOnCaller(query, filter).withoutExplicit() }

    private suspend fun searchOnCaller(query: String, filter: String?): SearchResults {
        val cacheKey = "${query.trim().lowercase()}|${filter.orEmpty().lowercase()}|${AppSettings.contentRegion.value}"
        searchCache[cacheKey]?.takeIf { it.validUntilMs > currentTimeMillis() }?.let { return it.value }
        val filterParam = when (filter?.lowercase()) {
            "songs" -> "EgWKAQIIAWoKEAkQBRAKEAMQBA=="
            "videos" -> "EgWKAQIQAWoKEAkQChAFEAMQBA=="
            "albums" -> "EgWKAQIYAWoKEAkQChAFEAMQBA=="
            "artists" -> "EgWKAQIgAWoKEAkQChAFEAMQBA=="
            "playlists" -> "EgeKAQQoADgBagwQDhAKEAMQBRAJEAQ="
            else -> null
        }
        val searchBody = buildJsonObject {
            put("context", clientContext())
            put("query", query)
            filterParam?.let { put("params", it) }
        }.toString()
        val response = client.postJson(
            "https://music.youtube.com/youtubei/v1/search?key=$API_KEY",
            searchBody,
        )

        val filterChips = listOf("Songs", "Videos", "Albums", "Artists", "Playlists")

        if (filter == null || filter.equals("All", ignoreCase = true)) {
            val catalogItems = response.catalogItems()
            val summaries = mutableListOf<SearchSummaryShelf>()
            val allItems = mutableListOf<Track>()
            
            val shelves = response.findObjects("musicCardShelfRenderer")
            for (shelf in shelves) {
                val cardSubject = shelf["title"]?.allRunText().orEmpty()
                val items = shelf.findObjects("musicResponsiveListItemRenderer")
                    .mapNotNull { trackFromSearchItem(it, cardSubject) }
                    .distinctBy { it.id }
                if (items.isNotEmpty()) {
                    summaries.add(SearchSummaryShelf("Top result", items))
                    allItems.addAll(items)
                }
            }
            
            val sectionHeaders = response.findObjects("musicShelfRenderer")
            for (header in sectionHeaders) {
                val title = header.findString("title") ?: continue
                val items = header.findObjects("musicResponsiveListItemRenderer")
                    .mapNotNull(::trackFromSearchItem)
                if (items.isNotEmpty()) {
                    summaries.add(SearchSummaryShelf(title, items))
                    allItems.addAll(items)
                }
            }

            // YouTube Music currently A/B-tests a flat layout where every result is wrapped in
            // its own itemSectionRenderer. Android's model parser accepts both layouts; collect
            // responsive rows even when a Top result card already exists.
            val knownIds = allItems.mapTo(mutableSetOf()) { it.id }
            val groups = linkedMapOf<String, MutableList<Track>>()
            response.findObjects("musicResponsiveListItemRenderer").forEach { item ->
                val track = trackFromSearchItem(item) ?: return@forEach
                if (!knownIds.add(track.id)) return@forEach
                val mediaType = item.flexText(1).orEmpty().substringBefore("•").trim()
                val groupTitle = when {
                    mediaType.equals("Song", true) -> "Songs"
                    mediaType.equals("Video", true) -> "Videos"
                    mediaType.equals("Episode", true) -> "Episodes"
                    else -> "Other results"
                }
                groups.getOrPut(groupTitle) { mutableListOf() }.add(track)
                allItems.add(track)
            }

            val groupOrder = listOf("Songs", "Videos", "Episodes", "Other results")
            groupOrder.forEach { title ->
                groups[title]?.takeIf { it.isNotEmpty() }?.let { items ->
                    summaries.add(SearchSummaryShelf(title, items))
                }
            }

            // Android's All page is backed by searchSummary, while its filter
            // tabs issue full Songs/Videos searches with continuations. Merge
            // the first full filtered pages into All so official audio/video
            // results are not lost when YouTube returns a short summary shelf.
            val supplemental = coroutineScope {
                val songs = async { runCatching { filteredSearchTracks(query, "EgWKAQIIAWoKEAkQBRAKEAMQBA==") }.getOrDefault(emptyList()) }
                val videos = async { runCatching { filteredSearchTracks(query, "EgWKAQIQAWoKEAkQChAFEAMQBA==") }.getOrDefault(emptyList()) }
                val albums = async { runCatching { filteredSearchCatalog(query, "EgWKAQIYAWoKEAkQChAFEAMQBA==") }.getOrDefault(emptyList()) }
                val artists = async { runCatching { filteredSearchCatalog(query, "EgWKAQIgAWoKEAkQChAFEAMQBA==") }.getOrDefault(emptyList()) }
                listOf(songs.await(), videos.await()) to (albums.await() to artists.await())
            }
            fun mergeShelf(title: String, extra: List<Track>) {
                if (extra.isEmpty()) return
                val shelfIndex = summaries.indexOfFirst { it.title.equals(title, true) }
                if (shelfIndex >= 0) {
                    summaries[shelfIndex] = summaries[shelfIndex].copy(
                        items = (summaries[shelfIndex].items + extra).distinctBy { it.id },
                    )
                } else summaries += SearchSummaryShelf(title, extra.distinctBy { it.id })
                allItems += extra
            }
            mergeShelf("Songs", supplemental.first[0])
            mergeShelf("Videos", supplemental.first[1])
            val officialSummaries = summaries.map { shelf ->
                if (shelf.title.equals("Songs", true) || shelf.title.equals("Top result", true)) {
                    shelf.copy(items = shelf.items.preferOfficial())
                } else shelf
            }
            val mergedAlbums = (catalogItems.filter { it.subtitle.startsWith("Album", true) && it.isAlbumBrowseItem() } + supplemental.second.first)
                .distinctBy(CatalogItem::browseId).sortedByExactTitle(query)
            val mergedArtists = (catalogItems.filter { it.subtitle.startsWith("Artist", true) || it.browseId.startsWith("UC") } + supplemental.second.second)
                .distinctBy(CatalogItem::browseId).sortedByExactTitle(query)
            
            val searchResults = SearchResults(
                tracks = allItems.distinctBy { it.id }.preferOfficial(),
                albums = mergedAlbums,
                artists = mergedArtists,
                playlists = catalogItems.filter { it.subtitle.contains("Playlist", true) },
                summaries = officialSummaries,
                filters = listOf("All") + filterChips
            )
            
            // Warm the stream URL of the top results so a click starts at once.
            // URL only: downloading five full songs per search competed with the
            // song the user actually picked.
            kotlinx.coroutines.GlobalScope.launch(IoDispatcher) {
                searchResults.tracks.take(3).forEach { track ->
                    launch { warmStream(track) }
                }
            }
            
            return searchResults.also { searchCache[cacheKey] = CachedSearch(it, currentTimeMillis() + 10 * 60_000L) }
        } else {
            val tracks = response.findObjects("musicResponsiveListItemRenderer")
                .mapNotNull(::trackFromSearchItem)
                .map { track ->
                    if (filter.equals("Songs", true) && !track.isOfficial) track.copy(
                        musicVideoType = "MUSIC_VIDEO_TYPE_ATV",
                        isOfficial = true,
                    ) else track
                }
                .distinctBy { it.id }
            val catalogItems = response.catalogItems()
            val searchResults = SearchResults(
                tracks = tracks,
                albums = catalogItems.filter { it.subtitle.startsWith("Album", true) && it.isAlbumBrowseItem() },
                artists = catalogItems.filter { it.subtitle.startsWith("Artist", true) || it.browseId.startsWith("UC") && !it.isAlbumBrowseItem() },
                playlists = catalogItems.filter { it.subtitle.contains("Playlist", true) },
                filters = listOf("All") + filterChips
            )
            
            // Warm the stream URL of the top results so a click starts at once.
            // URL only: downloading five full songs per search competed with the
            // song the user actually picked.
            kotlinx.coroutines.GlobalScope.launch(IoDispatcher) {
                searchResults.tracks.take(3).forEach { track ->
                    launch { warmStream(track) }
                }
            }
            
            return searchResults.also { searchCache[cacheKey] = CachedSearch(it, currentTimeMillis() + 10 * 60_000L) }
        }
    }

    private suspend fun filteredSearchTracks(query: String, params: String): List<Track> {
        val body = buildJsonObject {
            put("context", clientContext())
            put("query", query)
            put("params", params)
        }.toString()
        return client.postJson("https://music.youtube.com/youtubei/v1/search?key=$API_KEY", body)
            .findObjects("musicResponsiveListItemRenderer")
            .mapNotNull(::trackFromSearchItem)
            // This response is explicitly the YouTube Music Songs filter—the
            // same source Android models as SongItem rather than VideoItem.
            .map { it.copy(musicVideoType = "MUSIC_VIDEO_TYPE_ATV", isOfficial = true) }
            .distinctBy { it.id }
            .sortedWith(compareByDescending<Track> { it.isOfficial }.thenBy { it.title.normalizedTitle() != query.normalizedTitle() })
    }

    private suspend fun filteredSearchCatalog(query: String, params: String): List<CatalogItem> {
        val body = buildJsonObject {
            put("context", clientContext())
            put("query", query)
            put("params", params)
        }.toString()
        val items = client.postJson("https://music.youtube.com/youtubei/v1/search?key=$API_KEY", body)
            .catalogItems()
        val typedItems = when (params) {
            "EgWKAQIYAWoKEAkQChAFEAMQBA==" -> items.filter { it.isAlbumBrowseItem() }
            "EgWKAQIgAWoKEAkQChAFEAMQBA==" -> items.filterNot { it.isAlbumBrowseItem() }
            else -> items
        }
        return typedItems
            .sortedByExactTitle(query)
    }

    /** Uses the same YouTube Music suggestion endpoint as Android's YouTube.searchSuggestions. */
    suspend fun searchSuggestions(query: String): List<String> =
        withContext(IoDispatcher) { searchSuggestionsOnCaller(query) }

    private suspend fun searchSuggestionsOnCaller(query: String): List<String> {
        if (query.isBlank()) return emptyList()
        val body = buildJsonObject {
            put("context", clientContext())
            put("input", query)
        }.toString()
        val response = client.postJson(
            "https://music.youtube.com/youtubei/v1/music/get_search_suggestions?key=$API_KEY",
            body,
        )
        return response.findObjects("searchSuggestionRenderer")
            .mapNotNull { it["suggestion"]?.allRunText()?.takeIf(String::isNotBlank) }
            .distinct()
    }

    /**
     * [includeSongs]: also run the Songs search for the song list (the compact
     * search page runs its own).
     */
    suspend fun searchSuggestionItems(query: String, includeSongs: Boolean = true): SearchResults =
        withContext(IoDispatcher) { searchSuggestionItemsOnCaller(query, includeSongs).withoutExplicit() }

    private suspend fun searchSuggestionItemsOnCaller(query: String, includeSongs: Boolean): SearchResults {
        if (query.isBlank()) return SearchResults()
        val body = buildJsonObject {
            put("context", clientContext())
            put("input", query)
        }.toString()
        val response = client.postJson("https://music.youtube.com/youtubei/v1/music/get_search_suggestions?key=$API_KEY", body)
        val catalogItems = response.catalogItems()
        val extras = coroutineScope {
            val albums = async { runCatching { filteredSearchCatalog(query, "EgWKAQIYAWoKEAkQChAFEAMQBA==") }.getOrDefault(emptyList()) }
            val artists = async { runCatching { filteredSearchCatalog(query, "EgWKAQIgAWoKEAkQChAFEAMQBA==") }.getOrDefault(emptyList()) }
            // YouTube Music's own best match, and its Songs results: the suggestion
            // endpoint often has no song at all for a less popular title ("10:36").
            val top = async { runCatching { searchTopResult(query) }.getOrNull() }
            val songs = async {
                if (includeSongs) runCatching { filteredSearchTracks(query, "EgWKAQIIAWoKEAkQBRAKEAMQBA==") }.getOrDefault(emptyList())
                else emptyList()
            }
            val songResults = songs.await()
            // A song as Top result: the same song's row from the Songs search
            // carries the full details (album, artists) when it is there.
            val best = top.await()?.let { result ->
                val id = result.track?.id ?: return@let result
                songResults.firstOrNull { it.id == id }?.let { result.copy(track = it) } ?: result
            }
            SuggestionExtras(albums.await(), artists.await(), best, songResults)
        }
        val suggestedTracks = response.findObjects("musicResponsiveListItemRenderer").mapNotNull(::trackFromSearchItem).distinctBy { it.id }
        return SearchResults(
            tracks = rankSongsForQuery(query, suggestedTracks + extras.songs.take(SUGGESTED_SONGS)),
            albums = (catalogItems.filter { it.subtitle.startsWith("Album", true) } + extras.albums).distinctBy(CatalogItem::browseId).sortedByExactTitle(query),
            artists = (catalogItems.filter { it.subtitle.startsWith("Artist", true) || it.browseId.startsWith("UC") } + extras.artists).distinctBy(CatalogItem::browseId).sortedByExactTitle(query),
            playlists = catalogItems.filter { it.subtitle.contains("Playlist", true) },
            topResult = extras.top,
        )
    }

    /**
     * Search suggestions as they come in, for the search box's panel: the
     * suggestion terms together with the first matches (one request), then the
     * same list growing as the Top result, Songs, Artists and Albums searches
     * answer. Each value is the full picture so far. Throws when the first
     * request fails (the extra searches may fail on their own).
     */
    fun searchSuggestionsLive(query: String): kotlinx.coroutines.flow.Flow<Pair<List<String>, SearchResults>> =
        kotlinx.coroutines.flow.channelFlow {
            if (query.isBlank()) { send(emptyList<String>() to SearchResults()); return@channelFlow }
            val body = buildJsonObject {
                put("context", clientContext())
                put("input", query)
            }.toString()
            val response = client.postJson("https://music.youtube.com/youtubei/v1/music/get_search_suggestions?key=$API_KEY", body)
            val terms = response.findObjects("searchSuggestionRenderer")
                .mapNotNull { it["suggestion"]?.allRunText()?.takeIf(String::isNotBlank) }
                .distinct()
            val catalogItems = response.catalogItems()
            val suggestedTracks = response.findObjects("musicResponsiveListItemRenderer").mapNotNull(::trackFromSearchItem).distinctBy { it.id }
            var albums = emptyList<CatalogItem>()
            var artists = emptyList<CatalogItem>()
            var top: SearchTopResult? = null
            var songs = emptyList<Track>()
            fun snapshot(): SearchResults {
                // A song as Top result: the same song's row from the Songs search
                // carries the full details (album, artists) when it is there.
                val best = top?.let { result ->
                    val id = result.track?.id ?: return@let result
                    songs.firstOrNull { it.id == id }?.let { result.copy(track = it) } ?: result
                }
                return SearchResults(
                    tracks = rankSongsForQuery(query, suggestedTracks + songs.take(SUGGESTED_SONGS)),
                    albums = (catalogItems.filter { it.subtitle.startsWith("Album", true) } + albums).distinctBy(CatalogItem::browseId).sortedByExactTitle(query),
                    artists = (catalogItems.filter { it.subtitle.startsWith("Artist", true) || it.browseId.startsWith("UC") } + artists).distinctBy(CatalogItem::browseId).sortedByExactTitle(query),
                    playlists = catalogItems.filter { it.subtitle.contains("Playlist", true) },
                    topResult = best,
                )
            }
            val gate = kotlinx.coroutines.sync.Mutex()
            send(terms to snapshot())
            launch { runCatching { filteredSearchCatalog(query, "EgWKAQIYAWoKEAkQChAFEAMQBA==") }.getOrNull()?.let { found -> gate.withLock { albums = found; send(terms to snapshot()) } } }
            launch { runCatching { filteredSearchCatalog(query, "EgWKAQIgAWoKEAkQChAFEAMQBA==") }.getOrNull()?.let { found -> gate.withLock { artists = found; send(terms to snapshot()) } } }
            launch { runCatching { searchTopResult(query) }.getOrNull()?.let { found -> gate.withLock { top = found; send(terms to snapshot()) } } }
            launch { runCatching { filteredSearchTracks(query, "EgWKAQIIAWoKEAkQBRAKEAMQBA==") }.getOrNull()?.let { found -> gate.withLock { songs = found; send(terms to snapshot()) } } }
        }.map { (terms, results) -> terms to results.withoutExplicit() }.flowOn(IoDispatcher)

    private class SuggestionExtras(
        val albums: List<CatalogItem>,
        val artists: List<CatalogItem>,
        val top: SearchTopResult?,
        val songs: List<Track>,
    )

    private class CachedTopResult(val value: SearchTopResult?, val validUntilMs: Long)

    private val topResultCache = concurrentMapOf<String, CachedTopResult>()

    /**
     * The "Top result" card of YouTube Music's search for [query]: the artist
     * for an artist name, the song for a song title, and so on. Null when the
     * search shows no such card.
     */
    suspend fun searchTopResult(query: String): SearchTopResult? = withContext(IoDispatcher) {
        val cacheKey = query.trim().lowercase()
        topResultCache[cacheKey]?.takeIf { it.validUntilMs > currentTimeMillis() }?.let { return@withContext it.value }
        val body = buildJsonObject {
            put("context", clientContext())
            put("query", query)
        }.toString()
        val response = client.postJson("https://music.youtube.com/youtubei/v1/search?key=$API_KEY", body)
        val card = response.findObjects("musicCardShelfRenderer").firstOrNull()
        val result = card?.let(::topResultFromCard)
        if (topResultCache.size > 200) topResultCache.clear()
        topResultCache[cacheKey] = CachedTopResult(result, currentTimeMillis() + 10 * 60_000L)
        result
    }

    private fun topResultFromCard(card: JsonObject): SearchTopResult? {
        val titleRun = (card["title"] as? JsonObject)?.get("runs")?.let { it as? JsonArray }?.firstOrNull() as? JsonObject ?: return null
        val title = titleRun["text"]?.jsonPrimitive?.contentOrNull?.takeIf(String::isNotBlank) ?: return null
        val navigation = titleRun["navigationEndpoint"] as? JsonObject ?: return null
        // Joined first, then split on the dots: "A & B" stays one artist.
        val subtitle = (card["subtitle"] as? JsonObject)?.get("runs")?.let { it as? JsonArray }
            ?.joinToString("") { (it as? JsonObject)?.get("text")?.jsonPrimitive?.contentOrNull.orEmpty() }
            .orEmpty()
            .trim()
        val subtitleParts = subtitle.split(Regex(" • |  | \\u2022 ")).map(String::trim).filter(String::isNotBlank)
        val artwork = (card["thumbnail"] as? JsonObject)?.bestArtworkUrl()
        val watch = navigation["watchEndpoint"] as? JsonObject
        if (watch != null) {
            val videoId = watch["videoId"]?.jsonPrimitive?.contentOrNull ?: return null
            val mediaType = subtitleParts.firstOrNull().orEmpty()
            val details = subtitleParts.drop(1)
            val artist = details.firstOrNull { !it.looksLikeDurationOrStats() }.orEmpty().ifBlank { "YouTube Music" }
            val album = if (mediaType.equals("Song", true)) {
                details.drop(1).firstOrNull { !it.looksLikeDurationOrStats() }.orEmpty()
            } else ""
            val duration = details.lastOrNull { it.matches(Regex("\\d{1,2}:\\d{2}(?::\\d{2})?")) }?.toDurationMs()?.takeIf { it > 0L } ?: 0L
            val musicVideoType = watch.findString("musicVideoType")
            val cardWithoutRows = JsonObject(card.filterKeys { it != "contents" })
            val track = Track(
                id = videoId,
                title = title,
                artist = artist,
                album = album,
                artworkUrl = artwork ?: "https://music.youtube.com/img/avatar_single_500.png",
                durationMs = duration,
                explicit = cardWithoutRows.toString().contains("MUSIC_EXPLICIT_BADGE"),
                musicVideoType = musicVideoType,
                isOfficial = musicVideoType == "MUSIC_VIDEO_TYPE_ATV",
            )
            return SearchTopResult(kind = if (mediaType.equals("Video", true)) "Video" else "Song", track = track)
        }
        val browse = navigation["browseEndpoint"] as? JsonObject ?: return null
        val browseId = browse["browseId"]?.jsonPrimitive?.contentOrNull ?: return null
        val pageType = browse.findString("pageType")
        val kind = catalogKindFor(browseId, pageType) { "" }
        if (kind !in setOf("Artist", "Album", "Playlist")) return null
        return SearchTopResult(kind = kind, item = CatalogItem(browseId, title, subtitle, artwork))
    }

    /**
     * [expandArtistTopSongs] false: an artist page comes back with only the
     * Top songs its own page lists, so it can be shown at once; the full list
     * is then fetched with [artistTopSongs].
     */
    suspend fun catalogPage(requestedBrowseId: String, kind: String, params: String? = null, expandArtistTopSongs: Boolean = true): CatalogPageData =
        withContext(IoDispatcher) { catalogPageOnCaller(requestedBrowseId, kind, params, expandArtistTopSongs).withoutExplicit() }

    /**
     * An artist's full Top songs list (its "Show All" playlist), saved for the
     * next visit ([savedArtistTopSongs]). Throws when it cannot be loaded.
     * [timeoutMs] lets a slow connection take longer than usual: the list is
     * one large answer that a weak connection may not finish in 12 s.
     */
    suspend fun artistTopSongs(moreEndpoint: String, moreParams: String?, timeoutMs: Long? = null): List<Track> =
        withContext(IoDispatcher) {
            catalogPageOnCaller(moreEndpoint, "Playlist", moreParams, true, timeoutMs).tracks.distinctBy { it.id }
                .also { if (it.isNotEmpty()) saveArtistTopSongs(moreEndpoint, it) }
                .withoutExplicit()
        }

    /** The full Top songs list saved the last time this artist page loaded it, if any. */
    suspend fun savedArtistTopSongs(moreEndpoint: String): List<Track>? = withContext(IoDispatcher) {
        runCatching {
            topSongsFile(moreEndpoint).takeIf { it.isFile }?.readText()
                ?.let { topSongsJson.decodeFromString(kotlinx.serialization.builtins.ListSerializer(Track.serializer()), it) }
                ?.takeIf { it.isNotEmpty() }
        }.getOrNull()?.withoutExplicit()
    }

    private val topSongsJson = Json { ignoreUnknownKeys = true }

    private fun topSongsFile(moreEndpoint: String): File {
        val name = digest("sha-1", moreEndpoint.encodeToByteArray())
            .joinToString("") { "%02x".fmt(it) }
        return File(File(AppStorage.directory, "artist-top-songs").apply { mkdirs() }, "$name.json")
    }

    private fun saveArtistTopSongs(moreEndpoint: String, tracks: List<Track>) {
        runCatching {
            val file = topSongsFile(moreEndpoint)
            val temp = File(file.parentFile, file.name + ".tmp")
            temp.writeText(topSongsJson.encodeToString(kotlinx.serialization.builtins.ListSerializer(Track.serializer()), tracks))
            if (!temp.renameTo(file)) { file.writeText(temp.readText()); temp.delete() }
        }
    }

    private suspend fun catalogPageOnCaller(requestedBrowseId: String, kind: String, params: String?, expandArtistTopSongs: Boolean = true, timeoutMs: Long? = null): CatalogPageData {
        // An artist page's "Show All" target (discography, videos, playlists...).
        if (kind.equals("Shelf", true)) return artistShelfPage(requestedBrowseId, params)
        // Playlist pages are only served for "VL" browse ids.
        val browseId = if (kind.equals("Playlist", true) &&
            (requestedBrowseId.startsWith("PL") || requestedBrowseId.startsWith("RDCLAK"))
        ) "VL$requestedBrowseId" else requestedBrowseId
        val body = buildJsonObject {
            // Artist pages carry a visitor id: their "Show All" links point at a
            // page snapshot that only resolves for the same visitor.
            put("context", if (kind.equals("Artist", true)) visitorBrowseContext() else clientContext())
            put("browseId", browseId)
            if (params != null) put("params", params)
        }.toString()
        val response = client.postJson("https://music.youtube.com/youtubei/v1/browse?key=$API_KEY", body, timeoutMs)
                val trackRoot = if (kind.equals("Album", true)) {
            response.findObjects("musicShelfRenderer").firstOrNull() ?: response
        } else if (kind.equals("Playlist", true)) {
            response.findObjects("musicPlaylistShelfRenderer").firstOrNull() ?: response
        } else {
            response.findObjects("musicShelfRenderer").firstOrNull { it.findObjects("musicResponsiveListItemRenderer").isNotEmpty() } ?: response
        }
        val firstTracks = trackRoot.findObjects("musicResponsiveListItemRenderer")
            .mapNotNull(::trackFromSearchItem).toMutableList()
        if (kind.equals("Shelf", true)) {
            firstTracks += response.findObjects("musicTwoRowItemRenderer").mapNotNull(::trackFromTwoRow)
        }
        var continuation = trackRoot.findObjects("nextContinuationData").firstNotNullOfOrNull { it.findString("continuation") }
            ?: trackRoot.findObjects("continuationEndpoint").firstNotNullOfOrNull { it.findString("continuation") }
        val continuedTracks = mutableListOf<Track>()
        val continuedCatalogs = mutableListOf<CatalogItem>()
        var continuationPages = 0
        while (continuation != null && continuationPages < 5) {
            val token = continuation
            val continuedResponse = runCatching {
                client.postJson(
                    "https://music.youtube.com/youtubei/v1/browse?key=$API_KEY",
                    browseBody(continuation = token),
                    timeoutMs,
                )
            }.getOrNull() ?: break
            continuedTracks += continuedResponse.findObjects("musicResponsiveListItemRenderer").mapNotNull(::trackFromSearchItem)
            continuedCatalogs += continuedResponse.catalogItems()
            continuation = continuedResponse.findObjects("nextContinuationData").firstNotNullOfOrNull { it.findString("continuation") }
                ?: continuedResponse.findObjects("continuationEndpoint").firstNotNullOfOrNull { it.findString("continuation") }
            continuationPages++
        }
                        var tracks = (firstTracks + continuedTracks).distinctBy { it.id }
        var catalogs = response.catalogItems() + continuedCatalogs
        val shelves = mutableListOf<CatalogShelf>()

                if (kind.equals("Artist", true)) {
            val sectionList = response.findObjects("sectionListRenderer").firstOrNull()
            val contents = sectionList?.get("contents")?.jsonArray?.mapNotNull { it.jsonObject }
                ?.mapNotNull { it["musicShelfRenderer"]?.jsonObject ?: it["musicCarouselShelfRenderer"]?.jsonObject }
                ?: (response.findObjects("musicShelfRenderer") + response.findObjects("musicCarouselShelfRenderer"))

            for (shelfRenderer in contents) {
                val title = shelfRenderer.findObjects("musicCarouselShelfBasicHeaderRenderer").firstOrNull()?.findString("text")
                    ?: shelfRenderer["title"]?.findString("text")
                    ?: shelfRenderer["header"]?.findString("text")
                    ?: continue
                
                val button = shelfRenderer.findObjects("moreContentButton").firstOrNull()
                    ?: shelfRenderer.findObjects("bottomEndpoint").firstOrNull()
                val browseId = button?.findString("browseId")
                val params = button?.findString("params")

                val items = mutableListOf<Any>()
                                items.addAll(shelfRenderer.findObjects("musicResponsiveListItemRenderer").mapNotNull(::trackFromSearchItem))
                items.addAll(shelfRenderer.findObjects("musicTwoRowItemRenderer").mapNotNull {
                    val cBrowseId = it["navigationEndpoint"]?.findString("browseId")
                        ?: it["title"]?.findString("browseId")
                        ?: it.findObjects("flexColumns").firstOrNull()?.findString("browseId")
                        ?: it.findString("browseId") ?: return@mapNotNull null
                    val cTitle = it.flexText(0) ?: it["title"]?.findString("text") ?: it.findString("text") ?: return@mapNotNull null
                                        val cSubtitle = it.flexText(1) ?: it["subtitle"]?.allRunText().orEmpty()
                    CatalogItem(cBrowseId, cTitle, cSubtitle, it.bestArtworkUrl())
                })

                if (items.isNotEmpty()) {
                    shelves.add(CatalogShelf(title = title, items = items, moreEndpoint = browseId, moreParams = params))
                }
            }
            
            val topSongsShelfIndex = shelves.indexOfFirst { it.title.equals("Top songs", ignoreCase = true) || it.title.equals("Lagu teratas", ignoreCase = true) }
            if (topSongsShelfIndex != -1) {
                val topSongsShelf = shelves[topSongsShelfIndex]
                if (topSongsShelf.moreEndpoint != null && expandArtistTopSongs) {
                    val fullTopSongs = runCatching { catalogPage(topSongsShelf.moreEndpoint, "Playlist", topSongsShelf.moreParams) }.getOrNull()
                    if (fullTopSongs != null && fullTopSongs.tracks.isNotEmpty()) {
                        shelves[topSongsShelfIndex] = topSongsShelf.copy(items = fullTopSongs.tracks)
                        tracks = fullTopSongs.tracks.distinctBy { it.id }
                    }
                }
            }
        } else if (kind.equals("Browse", true) || kind.equals("Playlist", true)) {
             val gridItems = response.findObjects("gridRenderer").flatMap { it.findObjects("musicTwoRowItemRenderer") }
             catalogs += gridItems.mapNotNull {
                val cBrowseId = it["navigationEndpoint"]?.findString("browseId") ?: it.findString("browseId") ?: return@mapNotNull null
                val cTitle = it.flexText(0) ?: it.findString("text") ?: return@mapNotNull null
                val cSubtitle = it.flexText(1) ?: it["subtitle"]?.allRunText().orEmpty()
                CatalogItem(cBrowseId, cTitle, cSubtitle, it.bestArtworkUrl())
             }
        }
                val title = listOf("musicImmersiveHeaderRenderer", "musicVisualHeaderRenderer", "musicDetailHeaderRenderer", "musicResponsiveHeaderRenderer", "musicEditablePlaylistDetailHeaderRenderer")
            .firstNotNullOfOrNull { key ->
                // The header's own title runs; a deep "title" search can land on
                // unrelated menu entries such as the playlist sort option "Top voted".
                response.findObjects(key).firstOrNull()?.get("title")?.allRunText()?.takeIf(String::isNotBlank)
            }
            ?: response.findObjects("musicImmersiveHeaderRenderer").firstOrNull()?.findString("title")
            ?: response.findObjects("musicVisualHeaderRenderer").firstOrNull()?.findString("title")
            ?: response.findObjects("musicDetailHeaderRenderer").firstOrNull()?.findString("title")
            ?: response.findObjects("musicResponsiveHeaderRenderer").firstOrNull()?.findString("title")
            ?: response.findString("title")
            ?: "Unknown "
                val header = response.findObjects("musicImmersiveHeaderRenderer").firstOrNull()
            ?: response.findObjects("musicVisualHeaderRenderer").firstOrNull()
            ?: response.findObjects("musicDetailHeaderRenderer").firstOrNull()
            ?: response.findObjects("musicResponsiveHeaderRenderer").firstOrNull()
        val pageArtwork = header?.bestArtworkUrl() ?: response.bestArtworkUrl()
        val headerRuns = header?.get("subtitle")?.jsonObject?.get("runs")?.jsonArray?.mapNotNull { it.jsonObject } ?: emptyList()
        val isAlbumPage = kind.equals("Album", true)
        // Album headers name the artist above the title ("straplineTextOne");
        // their subtitle is "Album • 2024", whose second part is the year.
        val albumArtist = if (isAlbumPage) {
            header?.get("straplineTextOne")?.allRunText().orEmpty().trim()
        } else ""
        val subtitleParts = header?.get("subtitle")?.allRunText().orEmpty()
            .split(Regex(" • |  | \\u2022 | \\uFFFD")).map(String::trim).filter(String::isNotBlank)
        val albumYear = if (isAlbumPage) subtitleParts.lastOrNull { it.matches(Regex("\\d{4}")) }.orEmpty() else ""
        val pageArtist = albumArtist.ifBlank {
            (headerRuns.firstOrNull { it.containsKey("navigationEndpoint") }?.get("text")?.jsonPrimitive?.contentOrNull
                ?: subtitleParts.drop(1).firstOrNull().orEmpty())
                .takeUnless { isAlbumPage && it.isNotAlbumArtistText() }.orEmpty()
        }
        // Album rows carry their length in a fixed column, not in the subtitle.
        val albumRowDurations = if (isAlbumPage) {
            trackRoot.findObjects("musicResponsiveListItemRenderer").mapNotNull { row ->
                val id = row.findString("videoId") ?: return@mapNotNull null
                val length = row.findObjects("fixedColumns").firstNotNullOfOrNull { column ->
                    column.allRunText().takeIf { it.matches(Regex("\\d{1,2}:\\d{2}(?::\\d{2})?")) }
                }?.toDurationMs()?.takeIf { it > 0L } ?: return@mapNotNull null
                id to length
            }.toMap()
        } else emptyMap()
        val hydratedTracks = tracks.map { track ->
            track.copy(
                durationMs = track.durationMs.takeIf { it > 0L } ?: albumRowDurations[track.id] ?: 0L,
                artist = track.artist.takeUnless { it == "YouTube Music" }.orEmpty().ifBlank { pageArtist.ifBlank { "YouTube Music" } },
                album = track.album.ifBlank { if (kind.equals("Album", true)) title else "" },
                artworkUrl = track.artworkUrl?.takeUnless {
                    it.isBlank() || it.contains("avatar_single_500")
                }.orEmpty().ifBlank { pageArtwork.orEmpty() },
            )
        }
        return CatalogPageData(
            title = title,
            subtitle = header?.let { it.findString("subtitle").takeUnless { s -> s.isNullOrEmpty() } ?: it.get("secondSubtitle")?.allRunText() }.orEmpty(),
            description = header?.get("description")?.let { it.jsonObject["musicDescriptionShelfRenderer"]?.jsonObject?.findString("description") ?: it.findString("description") }.orEmpty(),
            artworkUrl = pageArtwork,
            tracks = hydratedTracks,
            artist = if (isAlbumPage) pageArtist else "",
            year = albumYear,
                        albums = catalogs.filter { it.subtitle.startsWith("Album", true) || it.subtitle.startsWith("Single", true) }.takeIf { kind.equals("Album", true) } ?: catalogs,
            shelves = shelves,
        )
    }

    /**
     * Every item behind an artist shelf's "Show All" (Albums, Singles & EPs,
     * Videos, Live performances, Playlists by...), following all pages.
     *
     * These pages and their continuation tokens point at a server-side page
     * snapshot that YouTube only resolves for a request carrying a visitor id;
     * without one it answers with an empty page or "Something went wrong".
     */
    private suspend fun artistShelfPage(browseId: String, params: String?): CatalogPageData {
        val context = visitorBrowseContext()
        fun body(continuation: String? = null) = buildJsonObject {
            put("context", context)
            if (continuation != null) {
                put("continuation", continuation)
            } else {
                put("browseId", browseId)
                params?.let { put("params", it) }
            }
        }.toString()
        val url = "https://music.youtube.com/youtubei/v1/browse?key=$API_KEY"
        val first = client.postJson(url, body())
        val tracks = mutableListOf<Track>()
        val catalogs = mutableListOf<CatalogItem>()
        fun collect(page: JsonObject) {
            tracks += page.findObjects("musicResponsiveListItemRenderer").mapNotNull(::trackFromSearchItem)
            tracks += page.findObjects("musicTwoRowItemRenderer").mapNotNull(::trackFromTwoRow)
            catalogs += page.catalogItems()
        }
        fun nextToken(page: JsonObject): String? =
            page.findObjects("nextContinuationData").firstNotNullOfOrNull { it.findString("continuation") }
                ?: page.findObjects("continuationCommand").firstNotNullOfOrNull { it.findString("token") }
        collect(first)
        val seenTokens = HashSet<String>()
        var token = nextToken(first)
        var pages = 0
        while (token != null && seenTokens.add(token) && pages < MAX_SHELF_PAGES) {
            val page = runCatching { client.postJson(url, body(continuation = token)) }.getOrNull() ?: break
            val before = tracks.size + catalogs.size
            collect(page)
            if (tracks.size + catalogs.size == before) break
            token = nextToken(page)
            pages++
        }
        val header = first.findObjects("musicHeaderRenderer").firstOrNull()
            ?: first.findObjects("musicResponsiveHeaderRenderer").firstOrNull()
            ?: first.findObjects("musicImmersiveHeaderRenderer").firstOrNull()
        return CatalogPageData(
            title = header?.get("title")?.allRunText()?.takeIf(String::isNotBlank) ?: "Unknown ",
            subtitle = "",
            artworkUrl = header?.bestArtworkUrl() ?: catalogs.firstNotNullOfOrNull { it.artworkUrl } ?: tracks.firstNotNullOfOrNull { it.artworkUrl },
            tracks = tracks.distinctBy { it.id },
            albums = catalogs.distinctBy { it.browseId },
        )
    }

    /** [clientContext] plus a visitor id (the account's, else the shared anonymous one). */
    private suspend fun visitorBrowseContext(): JsonObject {
        val visitorData = WindowsPreferences.googleSession()?.visitorData?.takeIf(String::isNotBlank)
            ?: playbackVisitorData()
        return buildJsonObject {
            put("client", buildJsonObject {
                clientContext()["client"]?.jsonObject?.forEach { (key, value) -> put(key, value) }
                visitorData?.let { put("visitorData", it) }
            })
        }
    }

    /**
     * Album of a song, as YouTube Music itself links it ("Go to album"). Music
     * videos have no album of their own, so their song version is looked up
     * first. Null when there is none (the caller then searches by name).
     */
    suspend fun albumBrowseIdForTrack(videoId: String, title: String, artist: String): String? =
        withContext(IoDispatcher) { albumBrowseIdForTrackOnCaller(videoId, title, artist) }

    private suspend fun albumBrowseIdForTrackOnCaller(videoId: String, title: String, artist: String): String? = runCatching {
        albumFromWatchNext(videoId) ?: run {
            val wantedTitle = title.withoutVideoTags().normalizedTitle()
            val wantedArtist = artist.primaryArtistName().normalizedTitle()
            if (wantedTitle.isBlank()) return@run null
            val song = search("${title.withoutVideoTags()} ${artist.primaryArtistName()}", "Songs").tracks
                .filter { it.id != videoId }
                .firstOrNull { candidate ->
                    candidate.title.withoutVideoTags().normalizedTitle() == wantedTitle &&
                        candidate.artist.normalizedTitle().let { found ->
                            wantedArtist.isBlank() || (found.isNotBlank() && (found.contains(wantedArtist) || wantedArtist.contains(found)))
                        }
                } ?: return@run null
            albumFromWatchNext(song.id)
        }
    }.getOrNull()

    /**
     * The artist page of a song's own artist (its byline link on YouTube
     * Music), so a click on "Tinashe" never lands on another artist whose name
     * merely contains it. With several artists, the one named [artistName]
     * (else the first). Null when the song has no artist link.
     */
    suspend fun artistBrowseIdForTrack(videoId: String, artistName: String): String? = withContext(IoDispatcher) {
        runCatching {
            val response = client.postJson(
                "https://music.youtube.com/youtubei/v1/next?key=$API_KEY",
                buildJsonObject {
                    put("context", clientContext())
                    put("videoId", videoId)
                    put("isAudioOnly", true)
                    put("enablePersistentPlaylistPanel", true)
                }.toString(),
            )
            val item = response.findObjects("playlistPanelVideoRenderer")
                .firstOrNull { it["videoId"]?.jsonPrimitive?.contentOrNull == videoId } ?: return@runCatching null
            val artistRuns = (item["longBylineText"] ?: item["shortBylineText"])
                ?.jsonObject?.get("runs")?.jsonArray?.mapNotNull { it as? JsonObject }.orEmpty()
                .mapNotNull { run ->
                    val endpoint = run["navigationEndpoint"]?.findObjects("browseEndpoint")?.firstOrNull() ?: return@mapNotNull null
                    val browseId = endpoint["browseId"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                    val pageType = endpoint.findString("pageType")
                    if (pageType != "MUSIC_PAGE_TYPE_ARTIST" && pageType != "MUSIC_PAGE_TYPE_USER_CHANNEL" && !browseId.startsWith("UC")) return@mapNotNull null
                    run["text"]?.jsonPrimitive?.contentOrNull.orEmpty() to browseId
                }
            val wanted = artistName.normalizedTitle()
            (artistRuns.firstOrNull { it.first.normalizedTitle() == wanted } ?: artistRuns.firstOrNull())?.second
        }.getOrNull()
    }

    private val counterpartCache = concurrentMapOf<String, Pair<TrackCounterpart?, Long>>()

    /** Song / video timelines worked out this session (shared by every repository). */
    private object CounterpartAlignments {
        val scope = CoroutineScope(SupervisorJob() + IoDispatcher)
        /**
         * Decoding and comparing run on one low-priority thread, so they never
         * take time from playback, video or the UI (phones, WSA).
         */
        val worker = lowPriorityWorker("record-labs-alignment")
        val done = concurrentMapOf<String, List<CounterpartSegment>>()
        val jobs = concurrentMapOf<String, Deferred<List<CounterpartSegment>?>>()
    }

    /** Remembers [counterpart] for [videoId] (a switch already knows the way back). */
    fun rememberCounterpart(videoId: String, counterpart: TrackCounterpart) {
        counterpartCache[videoId] = counterpart to currentTimeMillis() + 6 * 60 * 60_000L
    }

    /**
     * The song's music video, or the music video's song, as YouTube Music's
     * player switches between them (null when it has no other version). Read
     * from the watch page: its first queue row is then a wrapper holding both.
     */
    suspend fun trackCounterpart(track: Track): TrackCounterpart? = withContext(IoDispatcher) {
        val videoId = track.id
        counterpartCache[videoId]?.takeIf { it.second > currentTimeMillis() }?.let { return@withContext it.first }
        val result = runCatching {
            val response = client.postJson(
                "https://music.youtube.com/youtubei/v1/next?key=$API_KEY",
                buildJsonObject {
                    put("context", clientContext())
                    put("videoId", videoId)
                    put("isAudioOnly", true)
                    put("enablePersistentPlaylistPanel", true)
                }.toString(),
            )
            response.findObjects("playlistPanelVideoWrapperRenderer").firstNotNullOfOrNull { wrapper ->
                counterpartFromWrapper(wrapper, videoId)
            } ?: counterpartFromSearch(track)
        }.onFailure { logError("Record Labs: counterpart of $videoId failed: ${it.message}") }
        // A failed request is tried again later; "no other version" is remembered.
        if (result.isSuccess) counterpartCache[videoId] = result.getOrNull() to currentTimeMillis() + 6 * 60 * 60_000L
        result.getOrNull()
    }

    /**
     * Without YouTube Music's own pair (it only sends it to some sessions), the
     * other version is looked up: a music video's song in the Songs search, a
     * song's official music video in the Videos search. Title (without
     * "(Official Video)" and the like) and artist must both agree.
     */
    private suspend fun counterpartFromSearch(track: Track): TrackCounterpart? {
        if (track.localPath != null) return null
        val title = track.title.withoutVideoTags().normalizedTitle()
        val artist = track.artist.primaryArtistName().normalizedTitle()
        if (title.isBlank() || artist.isBlank()) return null
        fun matches(candidate: Track): Boolean {
            val candidateTitle = candidate.title.withoutVideoTags().normalizedTitle()
            val candidateArtist = candidate.artist.normalizedTitle()
            val titleMatches = candidateTitle == title ||
                // "Artist - Song (Official Video)" style video titles.
                candidateTitle.removePrefix(artist).trim() == title
            return candidate.id != track.id && titleMatches && candidateArtist.contains(artist)
        }
        val isVideo = track.musicVideoType == "MUSIC_VIDEO_TYPE_OMV" || track.musicVideoType == "MUSIC_VIDEO_TYPE_UGC"
        val found = if (isVideo) {
            filteredSearchTracks("${track.title.withoutVideoTags()} ${track.artist}", "EgWKAQIIAWoKEAkQBRAKEAMQBA==")
                .filter(Track::isOfficial)
                .firstOrNull(::matches)
        } else {
            client.postJson(
                "https://music.youtube.com/youtubei/v1/search?key=$API_KEY",
                buildJsonObject {
                    put("context", clientContext())
                    put("query", "${track.title} ${track.artist}")
                    put("params", "EgWKAQIQAWoKEAkQChAFEAMQBA==")
                }.toString(),
            ).findObjects("musicResponsiveListItemRenderer")
                // Official music videos only (not lyric videos or covers).
                .filter { it.findString("musicVideoType") == "MUSIC_VIDEO_TYPE_OMV" }
                .mapNotNull { trackFromSearchItem(it)?.copy(musicVideoType = "MUSIC_VIDEO_TYPE_OMV") }
                .firstOrNull(::matches)
                ?.let { it.copy(isOfficial = false) }
        } ?: return null
        return TrackCounterpart(found)
    }

    /**
     * [counterpart] with its timeline lined up to [current] by listening to
     * both (see [AudioAlignment]) when YouTube did not say where the song sits
     * in the video. The result is kept for the session; on failure the pair is
     * returned as it was (playback then carries on at the same position).
     */
    suspend fun alignCounterpart(current: Track, counterpart: TrackCounterpart): TrackCounterpart {
        if (counterpart.segments.isNotEmpty()) return counterpart
        val currentIsVideo = current.hasMusicVideo
        val songId = if (currentIsVideo) counterpart.track.id else current.id
        val videoId = if (currentIsVideo) current.id else counterpart.track.id
        val key = "$songId|$videoId"
        val segments = CounterpartAlignments.done[key] ?: run {
            val job = CounterpartAlignments.jobs.getOrPut(key) {
                CounterpartAlignments.scope.async { computeAlignment(songId, videoId) }
            }
            val result = runCatching { job.await() }.getOrNull()
            CounterpartAlignments.jobs.remove(key, job)
            // "Could not line up" is remembered too (an empty list): no second download.
            (result ?: emptyList()).also { CounterpartAlignments.done[key] = it }
        }
        if (segments.isEmpty()) return counterpart
        // [segments] go from the song to the video.
        return if (currentIsVideo) TrackCounterpart(current, segments).reversed(counterpart.track)
        else TrackCounterpart(counterpart.track, segments)
    }

    private suspend fun computeAlignment(songId: String, videoId: String): List<CounterpartSegment>? = coroutineScope {
        val song = async { opusAudioPcm(songId) }
        val video = async { opusAudioPcm(videoId) }
        val songPcm = song.await() ?: return@coroutineScope null
        val videoPcm = video.await() ?: return@coroutineScope null
        withContext(CounterpartAlignments.worker) { MediaPlatform.align(songPcm, videoPcm) }
            .also { logError("Record Labs: lined up $songId with $videoId: $it") }
    }

    /** The smallest Opus audio stream of [videoId] (about 1 MB a song), decoded for [AudioAlignment]. */
    private suspend fun opusAudioPcm(videoId: String): ShortArray? = withContext(IoDispatcher) {
        runCatching {
            val client = PLAYBACK_CLIENTS.firstOrNull { it.name == "VISIONOS" } ?: return@runCatching null
            val visitorData = playbackVisitorData() ?: return@runCatching null
            val extras = client.contextExtras.takeIf { it.isNotBlank() }?.let { ",$it" }.orEmpty()
            val bodyStr = """{"context":{"client":{"clientName":"${client.name}","clientVersion":"${client.version}","hl":"en","gl":"US"$extras,"visitorData":${JsonPrimitive(visitorData)}}},"videoId":"$videoId","contentCheckOk":true,"racyCheckOk":true}"""
            val request = OkRequest.Builder()
                .url("https://music.youtube.com/youtubei/v1/player?prettyPrint=false")
                .header("Content-Type", "application/json")
                .header("User-Agent", client.userAgent)
                .header("X-YouTube-Client-Name", client.id)
                .header("X-YouTube-Client-Version", client.version)
                .header("X-Goog-Visitor-Id", visitorData)
                .header("Origin", "https://music.youtube.com")
                .post(bodyStr.toOkBody("application/json"))
                .build()
            val response = httpClient.newCall(request).execute().use { it.body?.string() }
                ?.let { Json.parseToJsonElement(it).jsonObject } ?: return@runCatching null
            val format = response.findArrays("adaptiveFormats").asSequence()
                .flatMap { it.asSequence() }
                .filterIsInstance<JsonObject>()
                .filter { it["mimeType"]?.jsonPrimitive?.contentOrNull.orEmpty().let { mime -> mime.startsWith("audio/webm") && mime.contains("opus") } }
                .filter { it["url"] != null && it["contentLength"] != null }
                .minByOrNull { it["bitrate"]?.jsonPrimitive?.contentOrNull?.toLongOrNull() ?: Long.MAX_VALUE }
                ?: return@runCatching null
            val url = format["url"]!!.jsonPrimitive.content
            val length = format["contentLength"]!!.jsonPrimitive.content.toLong()
            if (length <= 0 || length > 30L * 1024 * 1024) return@runCatching null
            // In 1 MB pieces (a single big request is slowed down to playback speed).
            val bytes = ByteSink(length.toInt())
            var at = 0L
            while (at < length) {
                val end = minOf(at + 1_048_576L, length) - 1
                val piece = OkRequest.Builder()
                    .url("$url&range=$at-$end")
                    .header("User-Agent", client.userAgent)
                    .build()
                val data = httpClient.newCall(piece).execute().use { if (it.isSuccessful) it.body?.bytes() else null }
                    ?: return@runCatching null
                if (data.isEmpty()) return@runCatching null
                bytes.write(data)
                at += data.size
            }
            val data = bytes.toByteArray()
            kotlinx.coroutines.runBlocking(CounterpartAlignments.worker) { MediaPlatform.decodeWebmOpus(data) }
                .takeIf { it.size > MediaPlatform.alignmentSampleRate * 10 }
        }.onFailure { logError("Record Labs: audio for lining up $videoId failed: ${it.message}") }
            .getOrNull()
    }

    private fun counterpartFromWrapper(wrapper: JsonObject, videoId: String): TrackCounterpart? {
        val primary = wrapper["primaryRenderer"]?.findObjects("playlistPanelVideoRenderer")?.firstOrNull() ?: return null
        val entry = (wrapper["counterpart"] as? JsonArray)?.firstOrNull() as? JsonObject ?: return null
        val other = entry["counterpartRenderer"]?.findObjects("playlistPanelVideoRenderer")?.firstOrNull() ?: return null
        val primaryId = primary.findString("videoId")
        val otherId = other.findString("videoId")
        // The current song is either side of the pair.
        val (mine, theirs, flipped) = when (videoId) {
            primaryId -> Triple(primary, other, false)
            otherId -> Triple(other, primary, true)
            else -> return null
        }
        val myType = mine.findString("musicVideoType")
        val track = trackFromPlaylistPanelItem(theirs)?.let { found ->
            // A row without its type: the opposite of the current one.
            if (found.musicVideoType != null) found
            else if (myType == "MUSIC_VIDEO_TYPE_ATV") found.copy(musicVideoType = "MUSIC_VIDEO_TYPE_OMV", isOfficial = false)
            else found.copy(musicVideoType = "MUSIC_VIDEO_TYPE_ATV", isOfficial = true)
        } ?: return null
        if (track.id == videoId) return null
        // segmentMap: parts of the music with their start in each version.
        fun JsonObject.millis(vararg words: String): Long? = entries.firstOrNull { (key, _) ->
            words.all { key.contains(it, ignoreCase = true) }
        }?.value?.jsonPrimitive?.contentOrNull?.toLongOrNull()
        val segments = entry["segmentMap"]?.findObjects("segment").orEmpty()
                .mapNotNull { segment ->
                    val primaryStart = segment.millis("primary", "start") ?: return@mapNotNull null
                    val counterpartStart = segment.millis("counterpart", "start") ?: return@mapNotNull null
                    val duration = segment.millis("duration") ?: return@mapNotNull null
                    if (flipped) CounterpartSegment(counterpartStart, primaryStart, duration)
                    else CounterpartSegment(primaryStart, counterpartStart, duration)
                }
        return TrackCounterpart(track, segments)
    }

    private suspend fun albumFromWatchNext(videoId: String): String? {
        val response = client.postJson(
            "https://music.youtube.com/youtubei/v1/next?key=$API_KEY",
            buildJsonObject {
                put("context", clientContext())
                put("videoId", videoId)
                put("isAudioOnly", true)
                put("enablePersistentPlaylistPanel", true)
            }.toString(),
        )
        val item = response.findObjects("playlistPanelVideoRenderer")
            .firstOrNull { it["videoId"]?.jsonPrimitive?.contentOrNull == videoId } ?: return null
        // The byline's album link first, then the item's "Go to album" menu entry.
        val byline = item["longBylineText"]?.findObjects("browseEndpoint").orEmpty()
        return (byline + item.findObjects("browseEndpoint"))
            .firstOrNull { endpoint ->
                endpoint.findString("pageType") == "MUSIC_PAGE_TYPE_ALBUM" &&
                    endpoint["browseId"]?.jsonPrimitive?.contentOrNull?.startsWith("MPRE") == true
            }
            ?.get("browseId")?.jsonPrimitive?.contentOrNull
    }

    /** "Song (Official Video)" -> "Song": drops video/lyrics/audio tags from a title. */
    private fun String.withoutVideoTags(): String =
        replace(Regex("""\s*[(\[][^)\]]*(official|video|lyric|audio|visuali[sz]er|\bmv\b|m/v)[^)\]]*[)\]]""", RegexOption.IGNORE_CASE), "")
            .trim()

    /** "A ft. B", "A & B", "A, B" -> "A". */
    private fun String.primaryArtistName(): String =
        split(Regex("""\s*(,|&|\bft\b\.?|\bfeat\b\.?|\bx\b|\bwith\b)\s*""", RegexOption.IGNORE_CASE)).firstOrNull()?.trim().orEmpty()

    suspend fun searchCategory(category: String): HomeFeed =
        withContext(IoDispatcher) { searchCategoryOnCaller(category).withoutExplicit() }

    private suspend fun searchCategoryOnCaller(category: String): HomeFeed = coroutineScope {
        val response = client.postJson(
            "https://music.youtube.com/youtubei/v1/browse?key=$API_KEY",
            """{"context":{"client":{"clientName":"WEB_REMIX","clientVersion":"$CLIENT_VERSION","hl":"en","gl":"US"}},"browseId":"FEmusic_home"}""",
        )
        HomeFeed(shelves = response.homeShelves())
    }

    suspend fun validateGoogleSession(): Boolean =
        withContext(IoDispatcher) { validateGoogleSessionOnCaller() }

    /**
     * Why the last [validateGoogleSession] said no, for login troubleshooting
     * (request error, or the reply's top-level keys / error status; never
     * account data). Null after a success.
     */
    @kotlin.concurrent.Volatile var lastValidationProblem: String? = null
        private set

    private suspend fun validateGoogleSessionOnCaller(): Boolean {
        if (WindowsPreferences.googleSession() == null) {
            lastValidationProblem = "no saved session"
            return false
        }
        // Android: checked like the original Android app (its InnerTube account request).
        // A network problem is not a signed-out account: only a definite "no" counts.
        StreamHooks.validateSession?.let { validate ->
            val verdict = runCatching { validate() }.getOrNull()
            lastValidationProblem = if (verdict == false) "account request refused the session" else null
            return verdict != false
        }
        suspend fun ask(): Boolean? {
            val response = runCatching {
                client.postJson(
                    "https://music.youtube.com/youtubei/v1/account/account_menu?key=$API_KEY",
                    buildJsonObject { put("context", clientContext()) }.toString(),
                )
            }.onFailure {
                lastValidationProblem = "request failed: ${it::class.simpleName}: ${it.message.orEmpty().take(160)}"
            }.getOrNull() ?: return null
            val error = response["error"] as? JsonObject
            val signedIn = response.findObjects("accountItemSectionRenderer").isNotEmpty() ||
                response.findObjects("activeAccountHeaderRenderer").isNotEmpty() ||
                response.findStrings("email").any { it.contains('@') } ||
                // The menu changes shape now and then: a reply with actions, no error and no
                // "Sign in" button is a signed-in menu too.
                (error == null && response["actions"] != null && response.findObjects("signInEndpoint").isEmpty())
            lastValidationProblem = if (signedIn) null else {
                val errorText = error?.let { " error=${it["code"]}/${it["status"]}" }.orEmpty()
                val signInShown = if (response.findObjects("signInEndpoint").isNotEmpty()) " (signed out)" else ""
                "no account in reply: keys=${response.keys.take(8)}$errorText$signInShown"
            }
            return signedIn
        }
        val first = ask() ?: return false
        if (first) return true
        // A personal account's page id sent along made YouTube Music answer as signed out
        // on some first sign-ins: ask once more without it, and keep it off if that works.
        val session = WindowsPreferences.googleSession()
        if (session != null && session.dataSyncId.isNotBlank() && WindowsPreferences.googleUsePageId()) {
            WindowsPreferences.setGoogleUsePageId(false)
            val without = ask()
            if (without == true) return true
            WindowsPreferences.setGoogleUsePageId(true)
        }
        return false
    }

    suspend fun googleAccountProfile(): GoogleAccountProfile? =
        withContext(IoDispatcher) { googleAccountProfileOnCaller() }

    private suspend fun googleAccountProfileOnCaller(): GoogleAccountProfile? {
        if (WindowsPreferences.googleSession() == null) return null
        // Android: the original Android app's account request (its InnerTube client).
        StreamHooks.accountProfile?.let { fetch ->
            runCatching { fetch() }.getOrNull()?.let { profile ->
                WindowsPreferences.saveGoogleAccountProfile(profile.name, profile.photoUrl, profile.email, profile.channelHandle)
                return profile
            }
        }
        val menu = runCatching {
            client.postJson(
                "https://music.youtube.com/youtubei/v1/account/account_menu?key=$API_KEY",
                buildJsonObject { put("context", clientContext()) }.toString(),
            )
        }.getOrNull()
        menu?.let(::profileFromAccountMenu)?.let { return it }
        // Sessions from a browser the app cannot look into (Firefox on Linux) had no
        // account read from the login page, and the menu above came back without the
        // account: signed in, but no name and no picture. Two more places with the
        // account (the account list, and YouTube's account switcher).
        val accounts = runCatching {
            client.postJson(
                "https://music.youtube.com/youtubei/v1/account/accounts_list?key=$API_KEY",
                buildJsonObject { put("context", clientContext()) }.toString(),
            )
        }.getOrNull()
        accounts?.let(::profileFromAccountItems)?.let { return it }
        val switcher = runCatching { accountSwitcher() }.getOrNull()
        switcher?.let(::profileFromAccountItems)?.let { return it }
        menu?.let { saveAccountMenuForDebug(it, "account-menu-debug.json") }
        accounts?.let { saveAccountMenuForDebug(it, "accounts-list-debug.json") }
        switcher?.let { saveAccountMenuForDebug(it, "account-switcher-debug.json") }
        return null
    }

    /** YouTube's account switcher (the account picker on youtube.com), with the session's cookies. */
    private suspend fun accountSwitcher(): JsonObject? {
        val session = WindowsPreferences.googleSession() ?: return null
        val text = client.get("https://www.youtube.com/getAccountSwitcherEndpoint") {
            header("Accept", "application/json")
            header("Accept-Language", "en-US,en;q=0.9")
            header("User-Agent", DEFAULT_STREAM_USER_AGENT)
            header("Origin", "https://www.youtube.com")
            header("X-Origin", "https://www.youtube.com")
            header("Referer", "https://www.youtube.com/")
            header("Cookie", session.cookie)
            header("Authorization", session.sapisidHash("https://www.youtube.com"))
            header("X-Goog-AuthUser", "0")
        }.bodyAsText()
        // The reply starts with )]}' (protection against reading it as a script).
        val json = text.substringAfter(")]}'").trim()
        return runCatching { Json.parseToJsonElement(json).jsonObject }.getOrNull()
    }

    /** The signed-in account in a list of accounts (accounts_list, the account switcher). */
    private fun profileFromAccountItems(response: JsonObject): GoogleAccountProfile? {
        val items = response.findObjects("accountItem") + response.findObjects("accountItemRenderer")
        val item = items.firstOrNull { it["isSelected"]?.jsonPrimitive?.contentOrNull == "true" } ?: items.firstOrNull() ?: return null
        val name = item["accountName"]?.displayText().orEmpty()
        if (name.isBlank()) return null
        val photo = (item["accountPhoto"] as? JsonObject)?.bestArtworkUrl()
        val handle = item["channelHandle"]?.displayText()?.takeIf(String::isNotBlank)
        val email = (item["accountByline"]?.displayText() ?: item["email"]?.displayText())?.takeIf { it.contains('@') }
        return GoogleAccountProfile(name, photo, email, handle).also {
            WindowsPreferences.saveGoogleAccountProfile(it.name, it.photoUrl, it.email, it.channelHandle)
        }
    }

    /** Text in either of YouTube's forms: runs ({"runs": [{"text"}]}) or {"simpleText"}. */
    private fun JsonElement.displayText(): String =
        allRunText().ifBlank { findString("simpleText").orEmpty() }.ifBlank { findString("content").orEmpty() }.trim()

    /**
     * The account (name, picture, email, handle) in an account_menu reply: from the app's
     * own request, or from the login browser's page (see the desktop login).
     */
    fun accountProfileFromMenu(json: String): GoogleAccountProfile? {
        val response = runCatching { Json.parseToJsonElement(json).jsonObject }.getOrNull() ?: return null
        return profileFromAccountMenu(response).also { if (it == null) saveAccountMenuForDebug(response, "account-menu-page-debug.json") }
    }

    /** The reply kept for a look when no account could be read from it (its shape changed). */
    private fun saveAccountMenuForDebug(response: JsonObject, name: String) {
        runCatching { File(AppPlatform.dataRoot, name).writeText(response.toString().take(400_000)) }
    }

    private fun profileFromAccountMenu(response: JsonObject): GoogleAccountProfile? {
        val header = response.findObjects("activeAccountHeaderRenderer").firstOrNull()
            ?: response.findObjects("googleAccountHeaderRenderer").firstOrNull()
            ?: response.findObjects("accountHeaderRenderer").firstOrNull()
            ?: return null
        // A name as {"simpleText"} (not only as runs) was not read: no account shown.
        val name = header["accountName"]?.displayText().orEmpty()
            .ifBlank { header["name"]?.displayText().orEmpty() }
            .ifBlank { header.findString("name").orEmpty() }
        if (name.isBlank()) return null
        val email = header["email"]?.displayText().orEmpty()
            .ifBlank { header.findStrings("email").firstOrNull { it.contains('@') }.orEmpty() }
            .takeIf(String::isNotBlank)
        val channelHandle = header["channelHandle"]?.displayText().orEmpty()
            .ifBlank { header.findString("channelHandle").orEmpty() }
            .trim()
            .takeIf(String::isNotBlank)
        val photo = (header["accountPhoto"] as? JsonObject)?.bestArtworkUrl() ?: header.bestArtworkUrl()
        return GoogleAccountProfile(name, photo, email, channelHandle).also {
            WindowsPreferences.saveGoogleAccountProfile(it.name, it.photoUrl, it.email, it.channelHandle)
        }
    }

    /** Uses the same account browse ids as Android's SyncUtils/AccountViewModel. */
    suspend fun googleLibrary(): GoogleLibrarySnapshot =
        withContext(IoDispatcher) { googleLibraryOnCaller() }

    private suspend fun googleLibraryOnCaller(): GoogleLibrarySnapshot = coroutineScope {
        check(WindowsPreferences.googleSession() != null) { "Google account is not connected" }
        val likedSongs = async {
            // Unfiltered: the library import keeps explicit likes even with Hide explicit on.
            runCatching { catalogPageOnCaller("LM", "Playlist", null).tracks }.getOrDefault(emptyList())
        }
        val librarySongs = async {
            runCatching { browseLibraryTracks("FEmusic_liked_videos") }.getOrDefault(emptyList())
        }
        val playlists = async {
            runCatching {
                val response = client.postJson(
                    "https://music.youtube.com/youtubei/v1/browse?key=$API_KEY",
                    browseBody(browseId = "FEmusic_liked_playlists"),
                )
                response.catalogItems()
                    .filter { it.subtitle.contains("Playlist", true) }
                    .distinctBy { it.browseId }
                    .map { item -> WindowsPlaylist(item.title, browseId = item.browseId) }
            }.getOrDefault(emptyList())
        }
        GoogleLibrarySnapshot(
            liked = (likedSongs.await() + librarySongs.await()).distinctBy { it.id },
            playlists = playlists.await(),
        )
    }

    private suspend fun browseLibraryTracks(browseId: String): List<Track> {
        var response = client.postJson(
            "https://music.youtube.com/youtubei/v1/browse?key=$API_KEY",
            browseBody(browseId = browseId),
        )
        val tracks = mutableListOf<Track>()
        var pages = 0
        while (true) {
            tracks += response.findObjects("musicResponsiveListItemRenderer")
                .mapNotNull(::trackFromSearchItem)
            val continuation = response.findObjects("nextContinuationData")
                .firstNotNullOfOrNull { it.findString("continuation") }
                ?: response.findObjects("continuationEndpoint")
                    .firstNotNullOfOrNull { it.findString("continuation") }
                ?: break
            if (++pages >= 8) break
            response = client.postJson(
                "https://music.youtube.com/youtubei/v1/browse?key=$API_KEY",
                browseBody(continuation = continuation),
            )
        }
        return tracks.distinctBy { it.id }.preferOfficial()
    }

    suspend fun nextRadioQueue(videoId: String, retryWithOfficialSeed: Boolean = true): List<Track> =
        withContext(IoDispatcher) { nextRadioQueueOnCaller(videoId, retryWithOfficialSeed).withoutExplicit() }

    private suspend fun nextRadioQueueOnCaller(videoId: String, retryWithOfficialSeed: Boolean): List<Track> {
        suspend fun verifyCandidates(candidates: List<Track>): List<Track> = coroutineScope {
            candidates.filterNot { it.id == videoId }.distinctBy { it.id }.take(24)
                .map { candidate -> async { resolveOfficialTrack(candidate) } }
                .mapNotNull { it.await() }
                .filterNot { it.id == videoId }
                .distinctBy { it.id }
        }
        var response = runCatching {
            client.postJson(
                "https://music.youtube.com/youtubei/v1/next?key=$API_KEY",
                """{"context":{"client":{"clientName":"WEB_REMIX","clientVersion":"$CLIENT_VERSION","hl":"en","gl":"US"}},"videoId":"$videoId","enablePersistentPlaylistPanel":true}""",
            )
        }.getOrNull()
        if (response == null) {
            val verified = verifyCandidates(runCatching { MediaPlatform.newPipeRelatedTracks(videoId) }.getOrDefault(emptyList()))
            if (verified.isNotEmpty()) return verified
            if (retryWithOfficialSeed) {
                val officialSeed = resolveOfficialSeed(videoId)
                if (officialSeed != null && officialSeed.id != videoId) return nextRadioQueue(officialSeed.id, false)
            }
            return emptyList()
        }

        var firstItems = response.findObjects("playlistPanelVideoRenderer")
            .mapNotNull(::trackFromPlaylistPanelItem)
            .filterNot { it.id == videoId }
        // A fresh YouTube Music `next` request commonly returns only the seed
        // row and an automixPreviewVideoRenderer. Android's YouTubeQueue follows
        // that RDAMVM endpoint; without doing so desktop appeared to have an
        // empty queue until another network request happened at track end.
        if (firstItems.isEmpty()) {
            val automix = response.findObjects("watchPlaylistEndpoint")
                .firstOrNull { endpoint -> endpoint.findString("playlistId")?.startsWith("RDAMVM") == true }
            val playlistId = automix?.findString("playlistId")
            if (playlistId != null) {
                val automixResponse = runCatching {
                    client.postJson(
                        "https://music.youtube.com/youtubei/v1/next?key=$API_KEY",
                        buildJsonObject {
                            put("context", clientContext())
                            put("videoId", videoId)
                            put("playlistId", playlistId)
                            automix.findString("params")?.let { put("params", it) }
                            put("enablePersistentPlaylistPanel", true)
                        }.toString(),
                    )
                }.getOrNull()
                if (automixResponse != null) {
                    response = automixResponse
                    firstItems = response.findObjects("playlistPanelVideoRenderer")
                        .mapNotNull(::trackFromPlaylistPanelItem)
                        .filterNot { it.id == videoId }
                }
            }
        }
        var continuation = response.findObjects("nextContinuationData").firstNotNullOfOrNull { it.findString("continuation") }
            ?: response.findObjects("continuationEndpoint").firstNotNullOfOrNull { it.findString("continuation") }
        val continuedItems = mutableListOf<Track>()
        var continuationPages = 0
        // Android's YouTubeQueue keeps consuming continuation pages before the
        // visible list runs dry. Preload a comparable working set here so Next
        // never has to wait for the final row to finish before fetching again.
        while (continuation != null && continuationPages < 5 && firstItems.size + continuedItems.size < 60) {
            val token = continuation
            val continuedResponse = runCatching {
                client.postJson(
                    "https://music.youtube.com/youtubei/v1/next?key=$API_KEY",
                    buildJsonObject {
                        put("context", clientContext())
                        put("continuation", token)
                    }.toString(),
                )
            }.getOrNull() ?: break
            continuedItems += continuedResponse.findObjects("playlistPanelVideoRenderer")
                .mapNotNull(::trackFromPlaylistPanelItem)
            continuation = continuedResponse.findObjects("nextContinuationData")
                .firstNotNullOfOrNull { it.findString("continuation") }
                ?: continuedResponse.findObjects("continuationEndpoint")
                    .firstNotNullOfOrNull { it.findString("continuation") }
            continuationPages++
        }

        // ATV is YouTube Music's official audio track type. OMV and rows with
        // no music type are deliberately excluded from autoplay.
        var rawItems = (firstItems + continuedItems)
            .filter { it.id != videoId }
            .distinctBy { it.id }
        if (rawItems.isEmpty()) {
            rawItems = runCatching { MediaPlatform.newPipeRelatedTracks(videoId) }.getOrDefault(emptyList())
        }
        val officialItems = rawItems
            .filter { it.isOfficial && it.id != videoId }
            .distinctBy { it.id }
        if (officialItems.isNotEmpty()) return officialItems

        // Some `next` response variants omit musicVideoType from every panel
        // row. Resolve those rows through the Songs tab, but never return the
        // original unverified video row.
        val verified = verifyCandidates(rawItems)
        if (verified.isNotEmpty()) return verified
        if (retryWithOfficialSeed) {
            val officialSeed = resolveOfficialSeed(videoId)
            if (officialSeed != null && officialSeed.id != videoId) return nextRadioQueue(officialSeed.id, false)
        }
        return emptyList()
    }

    private suspend fun resolveOfficialSeed(videoId: String): Track? {
        val metadata = runCatching {
            client.get("https://www.youtube.com/oembed") {
                parameter("url", "https://www.youtube.com/watch?v=$videoId")
                parameter("format", "json")
            }.body<JsonObject>()
        }.getOrNull() ?: return null
        val title = metadata["title"]?.jsonPrimitive?.contentOrNull ?: return null
        val artist = metadata["author_name"]?.jsonPrimitive?.contentOrNull.orEmpty()
        return resolveOfficialTrack(Track(videoId, title, artist))
    }

    suspend fun resolveOfficialTrack(track: Track): Track? =
        withContext(IoDispatcher) { resolveOfficialTrackOnCaller(track) }

    private suspend fun resolveOfficialTrackOnCaller(track: Track): Track? {
        if (track.isOfficial) return track
        val candidates = runCatching {
            filteredSearchTracks("${track.title} ${track.artist}", "EgWKAQIIAWoKEAkQBRAKEAMQBA==")
        }.getOrDefault(emptyList()).filter(Track::isOfficial)
        val normalizedTitle = track.title.normalizedTitle()
        val normalizedArtist = track.artist.normalizedTitle()
        return candidates.maxByOrNull { candidate ->
            var score = 0
            if (candidate.title.normalizedTitle() == normalizedTitle) score += 100
            else if (candidate.title.normalizedTitle().contains(normalizedTitle)) score += 35
            if (candidate.artist.normalizedTitle().contains(normalizedArtist) ||
                normalizedArtist.contains(candidate.artist.normalizedTitle())) score += 70
            if (track.durationMs > 0 && kotlin.math.abs(track.durationMs - candidate.durationMs) <= 5_000L) score += 35
            score
        }
    }

    // Album rows that YouTube Music serves as music videos (OMV/UGC), mapped to
    // the album's audio track; a row with no confident match maps to itself.
    private val albumAudioCache = concurrentMapOf<String, Track>()

    /** True when an album page row is a music video rather than the album's song. */
    fun needsAlbumAudio(track: Track): Boolean =
        track.localPath == null && !track.isOfficial &&
            (track.musicVideoType == "MUSIC_VIDEO_TYPE_OMV" || track.musicVideoType == "MUSIC_VIDEO_TYPE_UGC")

    /**
     * Some albums (e.g. Dua Lipa's "Radical Optimism") list every row as its
     * official music video. The album should play its songs, so each such row
     * is matched to the same song in the YouTube Music Songs search (title,
     * album artist and length must agree). Rows without a confident match are
     * kept as they are. The result has the same order and size as [tracks].
     */
    suspend fun albumAudioTracks(tracks: List<Track>, albumTitle: String, albumArtist: String): List<Track> =
        withContext(IoDispatcher) {
            val gate = kotlinx.coroutines.sync.Semaphore(6)
            coroutineScope {
                tracks.map { track ->
                    async {
                        if (!needsAlbumAudio(track)) return@async track
                        albumAudioCache[track.id]?.let { return@async it }
                        val matched = gate.withPermit {
                            withTimeoutOrNull(12_000L) { albumAudioMatch(track, albumTitle, albumArtist) }
                        }
                        // Only definite answers are remembered: a failed or timed-out
                        // search may succeed when the album is opened again.
                        if (matched != null && matched.isSuccess) albumAudioCache[track.id] = matched.getOrNull() ?: track
                        matched?.getOrNull() ?: track
                    }
                }.map { it.await() }
            }
        }

    /** Success(null) = searched, no match; null/failure = the search itself failed. */
    private suspend fun albumAudioMatch(track: Track, albumTitle: String, albumArtist: String): Result<Track?> = runCatching {
        // YouTube Music's own pair first: the song that this very music video belongs
        // to (its Song/Video switch). Exact, also for titles a search would miss.
        val paired = runCatching { trackCounterpart(track) }.getOrNull()?.track
        if (paired != null && paired.id != track.id &&
            (paired.isOfficial || paired.musicVideoType == "MUSIC_VIDEO_TYPE_ATV")
        ) {
            return@runCatching track.copy(
                id = paired.id,
                title = track.title,
                artist = paired.artist.ifBlank { track.artist },
                durationMs = paired.durationMs.takeIf { it > 0L } ?: track.durationMs,
                explicit = track.explicit || paired.explicit,
                musicVideoType = "MUSIC_VIDEO_TYPE_ATV",
                isOfficial = true,
                artworkUrl = paired.artworkUrl?.takeUnless { it.isBlank() || it.contains("avatar_single_500") } ?: track.artworkUrl,
            )
        }
        fun String.key() = lowercase().replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim()
        // The row's own artist (e.g. on compilations), else the album artist.
        val artist = track.artist.takeUnless { it.isBlank() || it == "YouTube Music" } ?: albumArtist
        val title = track.title.key()
        val artistKey = artist.key()
        // Without an artist a title alone could match someone else's song.
        if (title.isBlank() || artistKey.isBlank()) return@runCatching null
        val candidates = filteredSearchTracks("${track.title} $artist".trim(), "EgWKAQIIAWoKEAkQBRAKEAMQBA==")
            .filter { it.isOfficial && it.id != track.id }
        candidates.mapNotNull { candidate ->
            val candidateTitle = candidate.title.key()
            val candidateArtist = candidate.artist.key()
            // Every artist of the row must be credited ("A & B" is not "A" alone:
            // a duet must never turn into the solo version).
            val wantedArtists = artist.artistNames()
            val foundArtists = candidate.artist.artistNames()
            val artistMatches = candidateArtist.isNotBlank() && wantedArtists.isNotEmpty() &&
                wantedArtists.all { name -> foundArtists.any { it == name } || candidateArtist.contains(name) } &&
                foundArtists.all { name -> wantedArtists.any { it == name } || artistKey.contains(name) }
            if (!artistMatches) return@mapNotNull null
            val lengthKnown = track.durationMs > 0L && candidate.durationMs > 0L
            val lengthGap = if (lengthKnown) kotlin.math.abs(track.durationMs - candidate.durationMs) else 0L
            // A video can run a little longer than the song (intro/outro), but a
            // very different length is another version (live, remix, extended).
            if (lengthKnown && lengthGap > 20_000L) return@mapNotNull null
            var score = when {
                candidateTitle == title -> 100
                candidateTitle.startsWith(title) || title.startsWith(candidateTitle) -> if (lengthKnown && lengthGap <= 3_000L) 60 else return@mapNotNull null
                else -> return@mapNotNull null
            }
            if (candidate.album.key() == albumTitle.key()) score += 30
            if (lengthKnown) score += (20 - (lengthGap / 1_000L).toInt()).coerceAtLeast(0)
            candidate to score
        }.maxByOrNull { it.second }?.first?.let { candidate ->
            track.copy(
                id = candidate.id,
                title = track.title,
                artist = candidate.artist.ifBlank { track.artist },
                durationMs = candidate.durationMs.takeIf { it > 0L } ?: track.durationMs,
                explicit = track.explicit || candidate.explicit,
                musicVideoType = "MUSIC_VIDEO_TYPE_ATV",
                isOfficial = true,
                artworkUrl = candidate.artworkUrl?.takeUnless { it.isBlank() || it.contains("avatar_single_500") } ?: track.artworkUrl,
            )
        }
    }

    /** Resolves a playable progressive/adaptive audio URL through the InnerTube player endpoint. */
    suspend fun streamUrl(track: Track): String? = withContext(IoDispatcher) {
        streamUrlInternal(track, cacheDownloadDelayMs = 0L)
    }

    /**
     * Same result as [streamUrl], for the track the user just asked to hear.
     *
     * Media Foundation streams through [WindowsAudioProxy] while the full-file
     * disk cache is filled in the background. Starting both downloads at the
     * same instant makes them compete for bandwidth exactly while the first
     * audio bytes are needed, so the cache download is started a moment later.
     */
    suspend fun playbackStreamUrl(track: Track): String? = withContext(IoDispatcher) {
        streamUrlInternal(track, cacheDownloadDelayMs = PLAYBACK_CACHE_DOWNLOAD_DELAY_MS)
    }

    /**
     * Background warm-up for tracks the user is likely to click (search results).
     * Only resolves and caches the stream URL: no audio download, no yt-dlp process.
     */
    suspend fun warmStream(track: Track) {
        // Android resolves when a song starts, like the original Android app (no search warm-ups).
        if (StreamHooks.streamUri != null) return
        if (!track.localPath.isNullOrBlank()) return
        if (MediaPlatform.cachedSongUri(track.id) != null) return
        runCatching { resolveStream(track.id, allowSlowFallback = false, reason = "warmup") }
    }

    private suspend fun streamUrlInternal(track: Track, cacheDownloadDelayMs: Long): String? {
        track.localPath?.let { path ->
            return MediaPlatform.localFileUri(path)
        }
        MediaPlatform.cachedSongUri(track.id)?.let { return it }

        // Android: the player resolves and streams the song itself (original Android app engine).
        StreamHooks.streamUri?.let { return it(track.id) }

        // resolveStream returns the 10-minute cached URL without new probes
        // (it was verified when first resolved), joins a resolution that is
        // already running for this id, or starts the source race.
        val resolved = resolveStream(track.id, allowSlowFallback = true, reason = "play") ?: return null
        if (cacheDownloadDelayMs > 0L) {
            MediaPlatform.prefetchLater(track.id, resolved.url, cacheDownloadDelayMs)
        } else {
            MediaPlatform.completePrefetchUri(track.id, resolved.url)?.let { return it }
        }
        return MediaPlatform.playerUrl(resolved.url, resolved.userAgent)
    }

    private data class InFlightStream(val result: Deferred<CachedStream?>, val allowSlowFallback: Boolean)

    // Resolutions run outside the caller's coroutine so a click can join a
    // warm-up that is already running instead of starting a second full race.
    private val streamResolveScope = CoroutineScope(SupervisorJob() + IoDispatcher)
    private val inFlightStreams = concurrentMapOf<String, InFlightStream>()

    private suspend fun resolveStream(videoId: String, allowSlowFallback: Boolean, reason: String): CachedStream? {
        streamCache[videoId]?.takeIf { it.validUntilMs > currentTimeMillis() }?.let { return it }
        val started = nanoTime()
        var joined = true
        // A finished resolution can stay in the map for a moment until its
        // completion handler removes it; never join one that is already done.
        inFlightStreams[videoId]?.takeIf { it.result.isCompleted }?.let { inFlightStreams.remove(videoId, it) }
        val inFlight = inFlightStreams[videoId] ?: run {
            val created = InFlightStream(
                streamResolveScope.async(start = CoroutineStart.LAZY) {
                    raceStreamSources(videoId, allowSlowFallback, reason)
                },
                allowSlowFallback,
            )
            val existing = inFlightStreams.putIfAbsent(videoId, created)
            if (existing != null) {
                created.result.cancel()
                existing
            } else {
                joined = false
                created.result.invokeOnCompletion { inFlightStreams.remove(videoId, created) }
                created.result.start()
                created
            }
        }
        val result = if (joined && allowSlowFallback && !inFlight.allowSlowFallback) {
            joinWarmupForPlayback(videoId, inFlight, reason)
        } else {
            inFlight.result.await()
        }
        if (joined) {
            MediaPlatform.recordTiming(videoId, "$reason-joined", if (result != null) "shared" else "none", (nanoTime() - started) / 1_000_000)
        }
        return result
    }

    /**
     * A play request that finds a search warm-up already running for the same
     * song. The warm-up skips yt-dlp, so waiting on it alone would delay (or,
     * if one of its sources never returns, block) the yt-dlp fallback a play
     * request is entitled to. Give the warm-up the same 1.5 s head start the
     * normal race gives InnerTube/NewPipe, then run the complete chain with
     * yt-dlp immediately and take whichever finds a stream first.
     */
    private suspend fun joinWarmupForPlayback(
        videoId: String,
        warmupEntry: InFlightStream,
        reason: String,
    ): CachedStream? {
        val warmup = warmupEntry.result
        val early = withTimeoutOrNull(YT_DLP_START_DELAY_MS) { warmup.await() }
        if (early != null) return early
        val full = streamResolveScope.async {
            raceStreamSources(videoId, allowSlowFallback = true, reason = reason, ytDlpDelayMs = 0L)
        }
        // Later requests for this song (Connect mirror, next-track warm-up, a
        // retry) join this complete race instead of starting another yt-dlp.
        val fullEntry = InFlightStream(full, allowSlowFallback = true)
        val registered = inFlightStreams.replace(videoId, warmupEntry, fullEntry) ||
            inFlightStreams.putIfAbsent(videoId, fullEntry) == null
        if (registered) full.invokeOnCompletion { inFlightStreams.remove(videoId, fullEntry) }
        if (warmup.isCompleted) return full.await()
        val (fromWarmup, value) = select<Pair<Boolean, CachedStream?>> {
            warmup.onAwait { true to it }
            full.onAwait { false to it }
        }
        return when {
            value != null -> {
                if (fromWarmup) full.cancel()
                value
            }
            // The warm-up finished empty: the complete chain is still running.
            fromWarmup -> full.await()
            // The complete chain (a superset of the warm-up) found nothing.
            else -> null
        }
    }

    private suspend fun raceStreamSources(
        videoId: String,
        allowSlowFallback: Boolean,
        reason: String,
        ytDlpDelayMs: Long = YT_DLP_START_DELAY_MS,
    ): CachedStream? {
        val started = nanoTime()
        val now = currentTimeMillis()
        var winnerSource = "none"
        // Android: only the original Android app's resolver (the desktop sources would
        // compete with it for the network and CPU). Its result also warms the player.
        StreamHooks.streamResolver?.let { resolver ->
            val url = runCatching { resolver(videoId) }.getOrNull()
            val stream = url?.let { CachedStream(it, ANDROID_STREAM_USER_AGENT, now + 10 * 60_000L) }
            if (stream != null) streamCache[videoId] = stream
            MediaPlatform.recordTiming(videoId, reason, if (stream != null) "Android" else "none", (nanoTime() - started) / 1_000_000)
            return stream
        }
        val winner = CompletableDeferred<Pair<String, CachedStream>?>()
        // The candidates run in their own scope instead of coroutineScope {}:
        // coroutineScope only returns once *every* child has finished, and the
        // blocking NewPipe / yt-dlp work cannot be cancelled. The first playable
        // URL was therefore held back until the slowest losing source (up to the
        // 15 s yt-dlp timeout) gave up. Losers now finish in the background.
        val raceJob = SupervisorJob()
        val race = CoroutineScope(IoDispatcher + raceJob)
        val resolved = try {
            // 0. Android: the original Android app's resolver (poToken, cipher web view).
            val androidJob = race.async {
                val resolver = StreamHooks.streamResolver ?: return@async
                val candidate = runCatching { resolver(videoId) }.getOrNull()?.let { url ->
                    CachedStream(url, ANDROID_STREAM_USER_AGENT, now + 10 * 60_000L)
                }
                if (candidate != null) winner.complete("Android" to candidate)
            }

            // 1. Race all InnerTube clients
            // Settings > Stream sources decides which sources take part.
            val innerTubeJobs = PLAYBACK_CLIENTS.filter { AppSettings.isStreamSourceEnabled(it.name) }.map { playbackClient ->
                race.async {
                    val candidate = fetchInnerTubeUrl(videoId, playbackClient)?.let { url ->
                        // One retry: a single slow CDN edge must not throw away an
                        // otherwise valid URL (the other sources keep racing meanwhile).
                        val playable = MediaPlatform.canStartStream(url, playbackClient.userAgent) ||
                            run { delay(300); MediaPlatform.canStartStream(url, playbackClient.userAgent) }
                        if (playable) CachedStream(url, playbackClient.userAgent, now + 10 * 60_000L) else null
                    }
                    if (candidate != null) winner.complete("${playbackClient.name}/${playbackClient.version}" to candidate)
                }
            }

            // 2. Race NewPipe in parallel (fast Java fallback)
            val newPipeJob = race.async {
                if (!AppSettings.isStreamSourceEnabled("NEWPIPE")) return@async
                val candidate = MediaPlatform.newPipeAudioStream(videoId)?.let { url ->
                    if (MediaPlatform.canStartStream(url, DEFAULT_STREAM_USER_AGENT)) {
                        CachedStream(url, DEFAULT_STREAM_USER_AGENT, now + 10 * 60_000L)
                    } else null
                }
                if (candidate != null) winner.complete("NewPipe" to candidate)
            }

            // 3. Fallback to yt-dlp (slow subprocess) only if others fail or are very slow
            val ytDlpJob = race.async {
                if (!allowSlowFallback || !AppSettings.isStreamSourceEnabled("YTDLP")) return@async
                if (ytDlpDelayMs > 0L) delay(ytDlpDelayMs)
                if (winner.isCompleted) return@async
                val candidate = MediaPlatform.ytDlpAudioStream(videoId)?.let { url ->
                    if (MediaPlatform.canStartStream(url, DEFAULT_STREAM_USER_AGENT)) {
                        CachedStream(url, DEFAULT_STREAM_USER_AGENT, now + 10 * 60_000L)
                    } else null
                }
                if (candidate != null) winner.complete("yt-dlp" to candidate)
            }

            // Nobody found a stream: report "none" once every source has finished.
            race.launch {
                runCatching { androidJob.await() }
                innerTubeJobs.forEach { runCatching { it.await() } }
                runCatching { newPipeJob.await() }
                runCatching { ytDlpJob.await() }
                winner.complete(null)
            }

            winner.await()
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            null
        } finally {
            // Stops pending client requests and the delayed yt-dlp start. Work
            // that is already blocking on the network ends on its own.
            raceJob.cancel()
        }
        if (resolved != null) {
            winnerSource = resolved.first
            streamCache[videoId] = resolved.second
        }
        MediaPlatform.recordTiming(videoId, reason, winnerSource, (nanoTime() - started) / 1_000_000)
        return resolved?.second
    }

    /**
     * Anonymous visitor id for clients such as VISIONOS. Fetched once per app
     * session from the public YouTube Music page (the same value the web app
     * uses), falling back to the signed-in session's visitor id.
     */
    // Shared by every repository instance (login checks create short-lived
    // ones), so the page is fetched once per app session, not per instance.
    private var anonymousVisitorData: String?
        get() = sharedVisitorData
        set(value) { sharedVisitorData = value }

    private suspend fun playbackVisitorData(): String? {
        anonymousVisitorData?.let { return it }
        val fetched = visitorDataLock.withLock {
            anonymousVisitorData ?: if (currentTimeMillis() >= visitorDataRetryAfterMs) {
                // NonCancellable: a short-lived repository closing mid-fetch must
                // not discard the id every other instance is waiting for.
                withContext(kotlinx.coroutines.NonCancellable) { fetchAnonymousVisitorData() }.also { value ->
                    if (value != null) anonymousVisitorData = value
                    else visitorDataRetryAfterMs = currentTimeMillis() + 10_000L
                }
            } else null
        }
        return fetched ?: WindowsPreferences.googleSession()?.visitorData?.takeIf(String::isNotBlank)
    }

    private suspend fun fetchAnonymousVisitorData(): String? = withContext(IoDispatcher) {
        runCatching {
            val request = OkRequest.Builder()
                .url("https://music.youtube.com/")
                .header("User-Agent", DEFAULT_STREAM_USER_AGENT)
                .header("Accept-Language", "en-US,en;q=0.9")
                .build()
            val html = httpClient.newCall(request).execute().use { it.body?.string() }.orEmpty()
            VISITOR_DATA_PATTERN.find(html)?.groupValues?.get(1)?.takeIf(String::isNotBlank)
        }.getOrNull()
    }

    private class CachedMusicVideo(val stream: Any, val validUntilMs: Long)

    private val musicVideoCache = concurrentMapOf<String, CachedMusicVideo>()

    /** One picture stream a player response offers (fragmented MP4 with a sidx index). */
    private class MusicVideoFormat(
        val url: String,
        val codec: String,
        val width: Int,
        val height: Int,
        /** YouTube's quality step ("1080p" also for a 1920x800 picture). */
        val quality: Int,
        val contentLength: Long,
        val indexEnd: Long,
        val userAgent: String,
    )

    /** A music video's player response for one client: its picture streams and caption tracks. */
    private class MusicVideoPlayerInfo(
        val formats: List<MusicVideoFormat>,
        val captions: List<MusicVideoCaptionTrack>,
        val validUntilMs: Long,
    )

    private val musicVideoPlayerCache = concurrentMapOf<String, MusicVideoPlayerInfo>()
    private val musicVideoCaptionCache = concurrentMapOf<String, List<MusicVideoCaptionCue>>()

    /**
     * Player response of [videoId] for [client] (cached for an hour, like the
     * streams): the MP4 picture streams that can be served per fragment, and
     * the caption tracks made by the uploader (auto-generated ones left out).
     */
    private suspend fun musicVideoPlayerInfo(videoId: String, client: PlaybackClient): MusicVideoPlayerInfo? = withContext(IoDispatcher) {
        val cacheKey = "${client.name}/${client.version}/$videoId"
        musicVideoPlayerCache[cacheKey]?.takeIf { it.validUntilMs > currentTimeMillis() }?.let { return@withContext it }
        runCatching {
            val visitorData = playbackVisitorData() ?: return@runCatching null
            val extras = client.contextExtras.takeIf { it.isNotBlank() }?.let { ",$it" }.orEmpty()
            val bodyStr = """{"context":{"client":{"clientName":"${client.name}","clientVersion":"${client.version}","hl":"en","gl":"US"$extras,"visitorData":${JsonPrimitive(visitorData)}}},"videoId":"$videoId","contentCheckOk":true,"racyCheckOk":true}"""
            val request = OkRequest.Builder()
                .url("https://music.youtube.com/youtubei/v1/player?prettyPrint=false")
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .header("User-Agent", client.userAgent)
                .header("X-Goog-Api-Format-Version", "1")
                .header("X-YouTube-Client-Name", client.id)
                .header("X-YouTube-Client-Version", client.version)
                .header("X-Goog-Visitor-Id", visitorData)
                .header("Origin", "https://music.youtube.com")
                .header("Referer", "https://music.youtube.com/")
                .post(bodyStr.toOkBody("application/json"))
                .build()
            val responseStr = httpClient.newCall(request).execute().use { it.body?.string() } ?: return@runCatching null
            val response = Json.parseToJsonElement(responseStr).jsonObject
            val formats = response
                .findArrays("adaptiveFormats").asSequence()
                .flatMap { it.asSequence() }
                .filterIsInstance<JsonObject>()
                .mapNotNull { format ->
                    val mimeType = format["mimeType"]?.jsonPrimitive?.contentOrNull.orEmpty()
                    if (!mimeType.startsWith("video/mp4")) return@mapNotNull null
                    // HDR pictures would come out washed out through the RGB frame copy.
                    val transfer = (format["colorInfo"] as? JsonObject)?.get("transferCharacteristics")?.jsonPrimitive?.contentOrNull.orEmpty()
                    val label = format["qualityLabel"]?.jsonPrimitive?.contentOrNull.orEmpty()
                    if (label.contains("HDR", ignoreCase = true) || transfer.contains("2084") || transfer.contains("B67")) return@mapNotNull null
                    val codec = when {
                        mimeType.contains("avc1") -> "avc1"
                        mimeType.contains("av01") -> "av01"
                        else -> return@mapNotNull null
                    }
                    val height = format["height"]?.jsonPrimitive?.contentOrNull?.toIntOrNull()?.takeIf { it > 0 } ?: return@mapNotNull null
                    MusicVideoFormat(
                        url = format["url"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null,
                        codec = codec,
                        width = format["width"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 0,
                        height = height,
                        quality = QUALITY_LABEL_HEIGHT.find(label)?.groupValues?.get(1)?.toIntOrNull() ?: height,
                        contentLength = format["contentLength"]?.jsonPrimitive?.contentOrNull?.toLongOrNull() ?: return@mapNotNull null,
                        indexEnd = (format["indexRange"] as? JsonObject)?.get("end")?.jsonPrimitive?.contentOrNull?.toLongOrNull()
                            ?.takeIf { it in 1..(4L * 1024L * 1024L) } ?: return@mapNotNull null,
                        userAgent = client.userAgent,
                    )
                }
                .toList()
            val captions = response.findArrays("captionTracks").asSequence()
                .flatMap { it.asSequence() }
                .filterIsInstance<JsonObject>()
                // Auto-generated (speech recognition) tracks are left out.
                .filter { it["kind"]?.jsonPrimitive?.contentOrNull != "asr" }
                .mapNotNull { track ->
                    val baseUrl = track["baseUrl"]?.jsonPrimitive?.contentOrNull?.takeIf { it.startsWith("http") } ?: return@mapNotNull null
                    val language = track["languageCode"]?.jsonPrimitive?.contentOrNull?.takeIf(String::isNotBlank) ?: return@mapNotNull null
                    val name = track["name"]?.allRunText()?.takeIf(String::isNotBlank)
                        ?: (track["name"] as? JsonObject)?.get("simpleText")?.jsonPrimitive?.contentOrNull
                        ?: language
                    MusicVideoCaptionTrack(language, name, baseUrl)
                }
                .distinctBy { it.languageCode to it.name }
                .toList()
            MusicVideoPlayerInfo(formats, captions, currentTimeMillis() + 60 * 60_000L)
        }.getOrNull()?.also { info ->
            // An answer without picture streams (a passing bot check, say) is
            // not kept: the next try asks again.
            if (info.formats.isNotEmpty()) {
                if (musicVideoPlayerCache.size > 60) musicVideoPlayerCache.clear()
                musicVideoPlayerCache[cacheKey] = info
            }
        }
    }

    /** Client for the picture streams above 1080p and for captions (AV1 up to 4K, uploader captions). */
    private val extendedMusicVideoClients: List<PlaybackClient>
        get() = listOfNotNull(
            PLAYBACK_CLIENTS.lastOrNull { it.name == "ANDROID_VR" },
            PLAYBACK_CLIENTS.firstOrNull { it.name == "IOS" },
        )

    private suspend fun extendedMusicVideoInfo(videoId: String, needCaptions: Boolean = true): MusicVideoPlayerInfo? {
        var formats: List<MusicVideoFormat> = emptyList()
        var captions: List<MusicVideoCaptionTrack> = emptyList()
        var validUntilMs = Long.MAX_VALUE
        // Picture streams and captions each come from the first client that has them.
        for (client in extendedMusicVideoClients) {
            if (formats.isNotEmpty() && (captions.isNotEmpty() || !needCaptions)) break
            val info = musicVideoPlayerInfo(videoId, client) ?: continue
            if (formats.isEmpty() && info.formats.isNotEmpty()) formats = info.formats
            if (captions.isEmpty() && info.captions.isNotEmpty()) captions = info.captions
            validUntilMs = minOf(validUntilMs, info.validUntilMs)
        }
        if (formats.isEmpty() && captions.isEmpty()) return null
        return MusicVideoPlayerInfo(formats, captions, validUntilMs)
    }

    /**
     * Picture heights (360p up to 4K) a music video can be shown at, and the
     * caption tracks its uploader made, for the full-screen video's menus.
     */
    suspend fun musicVideoOptions(videoId: String): MusicVideoOptions =
        withContext(IoDispatcher) { musicVideoOptionsOnCaller(videoId) }

    private suspend fun musicVideoOptionsOnCaller(videoId: String): MusicVideoOptions = coroutineScope {
        val visionClient = PLAYBACK_CLIENTS.firstOrNull { it.name == "VISIONOS" }
        val vision = async { visionClient?.let { musicVideoPlayerInfo(videoId, it) } }
        val extended = async { extendedMusicVideoInfo(videoId) }
        val visionInfo = vision.await()
        val extendedInfo = extended.await()
        // Up to 1080p the picture comes from the first client (as before);
        // higher steps only from the second one.
        val standardFormats = visionInfo?.formats.orEmpty()
        val heights = (standardFormats.map { it.quality }.filter { it <= STANDARD_MUSIC_VIDEO_HEIGHT } +
            extendedInfo?.formats.orEmpty().map { it.quality }.filter { it > STANDARD_MUSIC_VIDEO_HEIGHT })
            .filter { it in MIN_MUSIC_VIDEO_HEIGHT..MAX_MUSIC_VIDEO_HEIGHT }
            .distinct()
            .sortedDescending()
        MusicVideoOptions(heights, extendedInfo?.captions.orEmpty())
    }

    /** The timed lines of a caption track (cached). */
    suspend fun musicVideoCaptions(track: MusicVideoCaptionTrack): List<MusicVideoCaptionCue> = withContext(IoDispatcher) {
        musicVideoCaptionCache[track.baseUrl]?.let { return@withContext it }
        val body = runCatching {
            val request = OkRequest.Builder()
                .url(track.baseUrl)
                .header("User-Agent", extendedMusicVideoClients.firstOrNull()?.userAgent ?: DEFAULT_STREAM_USER_AGENT)
                .build()
            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) null else response.body?.string()
            }
        }.getOrNull() ?: return@withContext emptyList()
        parseCaptionCues(body).also { cues ->
            if (cues.isNotEmpty()) {
                if (musicVideoCaptionCache.size > 32) musicVideoCaptionCache.clear()
                musicVideoCaptionCache[track.baseUrl] = cues
            }
        }
    }

    /**
     * Video-only (muted) picture stream of a music video, shown in place of the
     * artwork. Audio keeps playing from the normal audio stream; this is only
     * the image. Returns a loopback URL for [MusicVideoStreamServer] (see
     * [MusicVideoStream] for why the stream is served as per-fragment files).
     *
     * Uses YouTube's fragmented MP4 (H.264 preferred: cheap to decode; AV1 only
     * as a fallback) at the smallest height >= [targetHeight], and returns null
     * when the video has no usable picture stream. Heights above 1080p (only
     * offered in AV1) come from a second client when the video has them.
     */
    suspend fun musicVideoStreamUrl(videoId: String, targetHeight: Int): String? = withContext(IoDispatcher) {
        val key = "$videoId@$targetHeight"
        musicVideoCache[key]?.takeIf { it.validUntilMs > currentTimeMillis() }?.let {
            return@withContext MediaPlatform.musicVideoStreamUrl(it.stream)
        }
        val client = PLAYBACK_CLIENTS.firstOrNull { it.name == "VISIONOS" } ?: return@withContext null
        val visionFormats = musicVideoPlayerInfo(videoId, client)?.formats.orEmpty()
        // Up to 1080p exactly as before: by picture height.
        fun pick(candidates: List<MusicVideoFormat>, codec: String) = candidates
            .filter { it.codec == codec }
            .let { matching ->
                matching.filter { it.height >= targetHeight }.minByOrNull { it.height }
                    ?: matching.maxByOrNull { it.height }
            }
        val standard = pick(visionFormats, "avc1") ?: pick(visionFormats, "av01")
        // Above 1080p YouTube only offers AV1 (or VP9, which is not served
        // here) and only to some clients; take it when it is a higher step
        // than what the first client has, by YouTube's own quality label.
        val sharper = if (targetHeight > STANDARD_MUSIC_VIDEO_HEIGHT) {
            extendedMusicVideoInfo(videoId, needCaptions = false)?.formats.orEmpty()
                .filter { it.codec == "av01" || it.codec == "avc1" }
                .filter { it.quality in (STANDARD_MUSIC_VIDEO_HEIGHT + 1)..MAX_MUSIC_VIDEO_HEIGHT }
                .let { higher ->
                    higher.filter { it.quality >= targetHeight }.minByOrNull { it.quality }
                        ?: higher.maxByOrNull { it.quality }
                }
                ?.takeIf { it.quality > (standard?.quality ?: 0) }
        } else null
        // The sharper stream first; if it cannot be opened, the standard one.
        for (format in listOfNotNull(sharper, standard)) {
            val stream = openMusicVideoStream(videoId, format) ?: continue
            musicVideoCache[key] = CachedMusicVideo(stream, currentTimeMillis() + 60 * 60_000L)
            return@withContext MediaPlatform.musicVideoStreamUrl(stream)
        }
        null
    }

    private fun openMusicVideoStream(videoId: String, format: MusicVideoFormat): Any? = runCatching {
        // Init segment + sidx index: a few KB at the start of the file.
        val headRequest = OkRequest.Builder()
            .url(format.url)
            .header("User-Agent", format.userAgent)
            .header("Accept-Encoding", "identity")
            .header("Range", "bytes=0-${format.indexEnd}")
            .build()
        val head = httpClient.newCall(headRequest).execute().use { response ->
            if (!response.isSuccessful) null else response.body?.bytes()
        } ?: return@runCatching null
        MediaPlatform.openMusicVideoStream(videoId, format.url, format.userAgent, format.contentLength, head, format.width, format.height, format.quality)
    }.getOrNull()

    fun invalidateMusicVideo(videoId: String) {
        musicVideoCache.removeKeysIf { it.startsWith("$videoId@") }
        musicVideoPlayerCache.removeKeysIf { it.endsWith("/$videoId") }
    }

    /**
     * Saves [track] for offline listening and returns once the whole song is on
     * disk (throws when it could not be saved). The stream is resolved like for
     * playback (on Android with the original Android app's resolver) and fetched
     * with the User-Agent it was made for.
     */
    suspend fun downloadOffline(track: Track) = withContext(IoDispatcher) {
        if (track.localPath != null) error("This song is already on this device")
        if (!MediaPlatform.isDownloaded(track.id)) {
            // A song already fully in the playback cache is just moved over.
            if (!MediaPlatform.promoteCachedToOffline(track.id)) {
                val androidResolver = StreamHooks.streamResolver
                val (remoteUrl, userAgent) = if (androidResolver != null) {
                    (androidResolver(track.id) ?: error("Audio belum bisa disiapkan")) to null
                } else {
                    val stream = resolveStream(track.id, allowSlowFallback = true, reason = "download")
                        ?: error("Audio resmi belum tersedia")
                    stream.url to stream.userAgent
                }
                MediaPlatform.downloadOffline(track, remoteUrl, userAgent)
            } else {
                MediaPlatform.markSaved(track)
            }
        } else {
            MediaPlatform.markSaved(track)
        }
        runCatching { WindowsLyricsProviderRegistry.getLyricsResult(track) }
        runCatching { MediaPlatform.downloadOfflineArtwork(track) }
        Unit
    }

    private val httpClient = OkClient(connectTimeoutMs = 6_000L, readTimeoutMs = 10_000L)

    private suspend fun fetchInnerTubeUrl(videoId: String, playbackClient: PlaybackClient): String? = runCatching {
        val visitorData = if (playbackClient.sendVisitorData) {
            playbackVisitorData() ?: return@runCatching null
        } else null
        val visitorExtra = visitorData?.let { ",\"visitorData\":${JsonPrimitive(it)}" }.orEmpty()
        val extras = playbackClient.contextExtras.takeIf { it.isNotBlank() }?.let { ",$it" }.orEmpty()
        val bodyStr = """{"context":{"client":{"clientName":"${playbackClient.name}","clientVersion":"${playbackClient.version}","hl":"en","gl":"US"$extras$visitorExtra}},"videoId":"$videoId","contentCheckOk":true,"racyCheckOk":true}"""

        val request = OkRequest.Builder()
            .url("https://music.youtube.com/youtubei/v1/player?prettyPrint=false")
            .header("Content-Type", "application/json")
            .header("Accept", "application/json")
            .header("Accept-Language", "en-US,en;q=0.9")
            .header("Cache-Control", "no-cache")
            .header("User-Agent", playbackClient.userAgent)
            .header("X-Goog-Api-Format-Version", "1")
            .header("X-YouTube-Client-Name", playbackClient.id)
            .header("X-YouTube-Client-Version", playbackClient.version)
            .header("X-Origin", "https://music.youtube.com")
            .header("Origin", "https://music.youtube.com")
            .header("Referer", "https://music.youtube.com/")
            .apply { visitorData?.let { header("X-Goog-Visitor-Id", it) } }
            .post(bodyStr.toOkBody("application/json"))
            .build()
            
        val responseStr = withContext(IoDispatcher) {
            httpClient.newCall(request).execute().use { it.body?.string() }
        } ?: return@runCatching null
        
        val response = Json.parseToJsonElement(responseStr).jsonObject
        if (anonymousVisitorData == null) {
            // Every player response carries a fresh anonymous visitor id; keep one
            // so VISIONOS still works if the YouTube Music page could not be read.
            ((response["responseContext"] as? JsonObject)?.get("visitorData") as? JsonPrimitive)
                ?.contentOrNull?.takeIf(String::isNotBlank)
                ?.let { anonymousVisitorData = it }
        }
        val bestFormat = response.findArrays("adaptiveFormats").asSequence()
            .flatMap { it.asSequence() }
            .filterIsInstance<JsonObject>()
            .filter { it["mimeType"]?.jsonPrimitive?.contentOrNull?.startsWith("audio/") == true }
            .sortedWith(
                compareByDescending<JsonObject> {
                    // AVPlayer (iPhone) plays AAC (audio/mp4), not Opus in WebM.
                    it["mimeType"]?.jsonPrimitive?.contentOrNull?.startsWith(if (AppPlatform.isIos) "audio/mp4" else "audio/webm") == true
                }.thenByDescending {
                    val bitrate = it["bitrate"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 0
                    // Settings > Player > Audio quality "Low": the smallest stream first.
                    if (AppSettings.audioQuality.value == "low") -bitrate else bitrate
                },
            ).firstOrNull() ?: return@runCatching null

        val url = bestFormat["url"]?.jsonPrimitive?.contentOrNull
        // Direct URLs from clients that never use player.js are already playable.
        // Previously they still waited on the shared player.js/Deno lock, whose
        // first use downloads the watch page and player.js before any song starts.
        if (url != null && !playbackClient.requiresJsPlayer) return@runCatching url
        val deciphered = if (url != null) url else {
            val cipher = bestFormat["signatureCipher"]?.jsonPrimitive?.contentOrNull
            if (cipher != null) {
                MediaPlatform.deobfuscateStreamUrl(cipher)
            } else null
        }

        if (deciphered != null) {
            MediaPlatform.transformNParamInUrl(deciphered)
        } else null
    }.getOrNull()

    override suspend fun lyrics(track: Track): List<LyricLine> {
        val results: JsonArray = client.get("https://lrclib.net/api/search") {
            parameter("track_name", track.title)
            parameter("artist_name", track.artist)
            if (track.album.isNotBlank()) parameter("album_name", track.album)
        }.body()

        val item = results.firstOrNull()?.jsonObject ?: return emptyList()
        val synced = item["syncedLyrics"]?.jsonPrimitive?.contentOrNull
        if (!synced.isNullAsText() && synced != null) {
            return parseLrc(synced)
        }

        val plain = item["plainLyrics"]?.jsonPrimitive?.contentOrNull
        if (!plain.isNullAsText() && plain != null) {
            return plain.lineSequence()
                .filter { it.isNotBlank() }
                .map { LyricLine(0L, it.trim()) }
                .toList()
        }
        return emptyList()
    }

    override fun close() {
        streamResolveScope.cancel()
        client.close()
    }

    private fun trackFromSearchItem(item: JsonObject, fallbackArtist: String = ""): Track? {
        val videoId = item.findString("videoId") ?: return null
        val title = item.flexText(0) ?: item.findString("text") ?: "Unknown Title"
        val subtitle = item.flexText(1).orEmpty()
        val flexStreams = item.flexText(2)
        val flexAlbum = item.flexText(3)
        val subtitleParts = subtitle.split(Regex(" • |  | \\u2022 | \\uFFFD")).map(String::trim).filter(String::isNotBlank)
        val mediaType = subtitleParts.firstOrNull()?.lowercase()
        val metadata = subtitleParts.drop(1)
        val artist = when (mediaType) {
            "episode" -> metadata.lastOrNull()
            "song", "video" -> metadata.firstOrNull { !it.looksLikeDurationOrStats() }
            else -> subtitleParts.firstOrNull()
        }.orEmpty().ifBlank { fallbackArtist }.ifBlank { "YouTube Music" }
        val streams = flexStreams ?: ""
        val candidateAlbum = flexAlbum ?: if (mediaType in setOf("song", "video")) {
            metadata.dropWhile { it != artist }.drop(1).firstOrNull { !it.looksLikeDurationOrStats() }.orEmpty()
        } else ""
        val album = candidateAlbum.ifBlank { streams.takeIf { !it.looksLikeDurationOrStats() }.orEmpty() }
        val duration = subtitleParts.lastOrNull { it.matches(Regex("\\d{1,2}:\\d{2}(?::\\d{2})?")) }
            ?.toDurationMs()
            ?.takeIf { it > 0L }
            ?: 0L
        // InnerTube lists several thumbnail renditions. Picking the first URL
        // commonly locks Windows to a 60px cache entry after changing tracks.
        val artwork = item.bestArtworkUrl() ?: "https://music.youtube.com/img/avatar_single_500.png"
        val musicVideoType = item.findString("musicVideoType")
        val official = musicVideoType == "MUSIC_VIDEO_TYPE_ATV"
        val explicit = item.toString().contains("MUSIC_EXPLICIT_BADGE")
                return Track(
            id = videoId,
            title = title,
            artist = artist,
            album = album,
            streams = streams,
            artworkUrl = artwork,
            durationMs = duration,
            explicit = explicit,
            musicVideoType = musicVideoType,
            isOfficial = official,
        )
    }

    private fun Track.toHomeItem() = HomeItem(
        title = title,
        subtitle = listOf(artist, album).filter(String::isNotBlank).joinToString(" • "),
        artworkUrl = artworkUrl,
        track = this,
    )

    private fun trackFromPlaylistPanelItem(item: JsonObject): Track? {
        val videoId = item.findString("videoId") ?: return null
        val title = item["title"]?.allRunText()?.takeIf(String::isNotBlank)
            ?: item.findString("text")
            ?: "Unknown Title"
        val byline = item["shortBylineText"]?.allRunText().orEmpty()
            .ifBlank { item["longBylineText"]?.allRunText().orEmpty() }
            .ifBlank { item["subtitle"]?.allRunText().orEmpty() }
        val artist = byline.substringBefore("•").trim()
            .ifBlank { item.findString("artist").orEmpty() }
            .ifBlank { item.findString("byline").orEmpty() }
            .ifBlank { "YouTube Music" }
        val artwork = item.bestArtworkUrl() ?: "https://music.youtube.com/img/avatar_single_500.png"
        val durationText = item.findString("lengthText") ?: ""
        val explicit = item.toString().contains("MUSIC_EXPLICIT_BADGE")
        return Track(
            id = videoId,
            title = title,
            artist = artist,
            artworkUrl = artwork,
            durationMs = durationText.toDurationMs(),
            explicit = explicit,
            musicVideoType = item.findString("musicVideoType"),
            isOfficial = item.findString("musicVideoType") == "MUSIC_VIDEO_TYPE_ATV",
        )
    }

    private fun JsonObject.homeShelves(): List<HomeShelf> =
        findObjects("musicCarouselShelfRenderer").mapNotNull { shelf ->
            val title = shelf.findString("text") ?: shelf.findString("title") ?: "Recommended"
            val cardItems = shelf.findObjects("musicTwoRowItemRenderer").mapNotNull(::homeItemFromTwoRow)
            val listItems = shelf.findObjects("musicResponsiveListItemRenderer").mapNotNull(::homeItemFromResponsive)
            val items = (cardItems + listItems).distinctBy { it.track?.id ?: "${it.title}|${it.subtitle}" }
            rememberShelfMorePage(title, shelf)
            HomeShelf(title, items, if (listItems.size > cardItems.size) HomeShelfLayout.List else HomeShelfLayout.Cards)
                .takeIf { items.isNotEmpty() }
        }

    /** The page behind a home shelf's "More" button (browse id and params), by shelf title. */
    private val shelfMorePages = concurrentMapOf<String, Pair<String, String?>>()
    private class CachedShelfPage(val items: List<HomeItem>, val validUntilMs: Long)
    private val shelfPageCache = concurrentMapOf<String, CachedShelfPage>()

    private fun rememberShelfMorePage(title: String, shelf: JsonObject) {
        val browse = (shelf["header"] as? JsonObject)?.findObjects("moreContentButton")?.firstOrNull()
            ?.findObjects("navigationEndpoint")?.firstOrNull()
            ?.get("browseEndpoint") as? JsonObject
        val browseId = browse?.get("browseId")?.jsonPrimitive?.contentOrNull?.takeIf(String::isNotBlank)
        // Moods & genres is a page of category buttons, not more of the same items.
        if (browseId == null || browseId == "FEmusic_moods_and_genres") {
            shelfMorePages.remove(title)
            return
        }
        shelfMorePages[title] = browseId to browse["params"]?.jsonPrimitive?.contentOrNull
    }

    /**
     * Home shelves with more items: a shelf that has a "More" page on YouTube
     * Music (new releases, new music videos, charts...) is filled up from that
     * page, up to [limit] items. Shelves without one stay as they are.
     */
    suspend fun expandHomeShelves(shelves: List<HomeShelf>, limit: Int = EXPANDED_SHELF_SIZE): List<HomeShelf> = withContext(IoDispatcher) {
        coroutineScope {
            shelves.map { shelf ->
                async {
                    val page = shelfMorePages[shelf.title]
                    if (page == null || shelf.items.size >= limit) return@async shelf
                    // Only the same kind of items as the shelf has (songs or cards).
                    val songsShelf = shelf.items.count { it.track != null } * 2 > shelf.items.size
                    val extra = runCatching { shelfPageItems(page.first, page.second) }.getOrDefault(emptyList())
                        .filter { (it.track != null) == songsShelf }
                        .filterNot { hidingExplicit && it.track?.explicit == true }
                    if (extra.isEmpty()) return@async shelf
                    val merged = (shelf.items + extra)
                        .distinctBy { it.track?.id ?: it.browseId ?: "${it.title}|${it.subtitle}" }
                        .take(limit)
                    if (merged.size == shelf.items.size) shelf else shelf.copy(items = merged)
                }
            }.map { it.await() }
        }
    }

    private suspend fun shelfPageItems(browseId: String, params: String?): List<HomeItem> {
        val cacheKey = "$browseId|${params.orEmpty()}"
        shelfPageCache[cacheKey]?.takeIf { it.validUntilMs > currentTimeMillis() }?.let { return it.items }
        val body = buildJsonObject {
            put("context", clientContext())
            put("browseId", browseId)
            if (params != null) put("params", params)
        }.toString()
        val response = client.postJson("https://music.youtube.com/youtubei/v1/browse?key=$API_KEY", body)
        // The page's main list only (a grid of cards or the playlist's songs),
        // not related shelves further down.
        val section = response.findObjects("gridRenderer").firstOrNull()
            ?: response.findObjects("musicPlaylistShelfRenderer").firstOrNull()
            ?: response.findObjects("musicShelfRenderer").firstOrNull()
            ?: response.findObjects("musicCarouselShelfRenderer").firstOrNull()
            ?: return emptyList()
        val items = (section.findObjects("musicTwoRowItemRenderer").mapNotNull(::homeItemFromTwoRow) +
            section.findObjects("musicResponsiveListItemRenderer").mapNotNull(::homeItemFromResponsive))
        if (items.isNotEmpty()) shelfPageCache[cacheKey] = CachedShelfPage(items, currentTimeMillis() + 10 * 60_000L)
        return items
    }

    private fun JsonObject.toHomeFeed(): HomeFeed = HomeFeed(
        shelves = homeShelves(),
        continuation = homeContinuationToken(),
        chips = findObjects("chipCloudChipRenderer").mapNotNull { chip ->
            val title = chip.findString("text") ?: return@mapNotNull null
            val params = chip.findString("params") ?: return@mapNotNull null
            HomeChip(title, params)
        }.distinctBy { it.params },
    )

    private fun JsonObject.homeContinuationToken(): String? {
        val section = findObjects("sectionListRenderer").firstOrNull()
            ?: findObjects("sectionListContinuation").firstOrNull()
            ?: return null
        return section["continuations"]
            ?.findObjects("nextContinuationData")
            ?.firstNotNullOfOrNull { it.findString("continuation") }
    }

    private fun homeItemFromTwoRow(item: JsonObject): HomeItem? {
        val title = item["title"]?.findString("text") ?: item.flexText(0) ?: return null
        val subtitle = item["subtitle"]?.allRunText().orEmpty().ifBlank { item.flexText(1).orEmpty() }
        val artworkUrl = item.bestArtworkUrl() ?: "https://music.youtube.com/img/avatar_single_500.png"
        val track = trackFromTwoRow(item)
        val navigation = item["navigationEndpoint"] as? JsonObject
        // Concerts / live events near the listener (YouTube Music with Bandsintown):
        // the card links to the event's page instead of music.
        val externalUrl = navigation?.get("urlEndpoint")?.findString("url")?.takeIf { it.startsWith("http") }
        if (track == null && externalUrl != null && navigation?.get("browseEndpoint") == null) {
            return HomeItem(title = title, subtitle = subtitle, artworkUrl = artworkUrl, kind = "Event", url = externalUrl)
        }
        // Cards that only start a playlist (mixes) open that playlist's page when
        // it has one; checked before the deep lookup, which can hit a menu's
        // artist/album link instead of the card itself.
        val watchPlaylistBrowseId = if (track == null && navigation?.get("browseEndpoint") == null) {
            navigation?.get("watchPlaylistEndpoint")?.findString("playlistId")
                ?.takeIf(::isBrowsablePlaylistId)?.let { if (it.startsWith("VL")) it else "VL$it" }
        } else null
        val browseId = navigation?.get("browseEndpoint")?.findString("browseId")
            ?: watchPlaylistBrowseId
            ?: item["title"]?.findString("browseId") ?: item.findObjects("flexColumns").firstOrNull()?.findString("browseId") ?: item.findString("browseId")
        val kind = catalogKindFor(browseId, navigation?.findString("pageType")) {
            if (subtitle.contains("Playlist", true)) "Playlist" else if (subtitle.contains("Artist", true) || browseId?.startsWith("UC") == true) "Artist" else "Album"
        }
        return HomeItem(
            title = title,
            subtitle = subtitle,
            artworkUrl = artworkUrl,
            track = track,
            browseId = browseId,
            kind = kind,
        )
    }

    private fun homeItemFromResponsive(item: JsonObject): HomeItem? {
        val track = trackFromSearchItem(item)
        val title = item["title"]?.findString("text") ?: item.flexText(0) ?: track?.title ?: return null
        val subtitle = item.flexText(1).orEmpty()
        val navigation = item["navigationEndpoint"] as? JsonObject
        val browseId = navigation?.findString("browseId") ?: item["title"]?.findString("browseId") ?: item.findObjects("flexColumns").firstOrNull()?.findString("browseId") ?: item.findString("browseId")
        val kind = catalogKindFor(browseId, navigation?.findString("pageType")) {
            if (subtitle.contains("Playlist", true)) "Playlist" else if (subtitle.contains("Artist", true) || browseId?.startsWith("UC") == true) "Artist" else "Album"
        }
        return HomeItem(title, subtitle.ifBlank { track?.artist ?: "" }, track?.artworkUrl ?: item.bestArtworkUrl(), track, browseId, kind)
    }

    /** Playlist ids with a browse page (not one-off radios such as RDAMVM…). */
    private fun isBrowsablePlaylistId(playlistId: String): Boolean =
        playlistId.startsWith("PL") || playlistId.startsWith("RDCLAK") || playlistId.startsWith("RDTMAK") ||
            playlistId.startsWith("OLAK5uy_") || playlistId.startsWith("VL")

    private fun trackFromTwoRow(item: JsonObject): Track? {
        val navigation = item["navigationEndpoint"] as? JsonObject
        // A card is a song/video only when the card itself opens a watch page.
        // Album and playlist cards also embed a play button whose videoId is
        // their first track; reading "any videoId" turned those pages into a
        // single song.
        if (navigation?.get("browseEndpoint") != null) return null
        val watch = navigation?.get("watchEndpoint") as? JsonObject
        if (navigation != null && watch == null && navigation["watchPlaylistEndpoint"] != null) return null
        val videoId = watch?.findString("videoId") ?: item.findString("videoId") ?: return null
        val musicVideoType = (watch ?: item).findString("musicVideoType")
        val title = item["title"]?.findString("text") ?: item.flexText(0) ?: return null
        val subtitle = item["subtitle"]?.allRunText().orEmpty().ifBlank { item.flexText(1).orEmpty() }
        val artist = subtitle.substringBefore("•").trim().ifBlank { "YouTube Music" }
        val artworkUrl = item.bestArtworkUrl() ?: "https://music.youtube.com/img/avatar_single_500.png"
        val explicit = item.toString().contains("MUSIC_EXPLICIT_BADGE")
        return Track(
            id = videoId,
            title = title,
            artist = artist,
            artworkUrl = artworkUrl,
            durationMs = 0L,
            explicit = explicit,
            musicVideoType = musicVideoType,
            isOfficial = musicVideoType == "MUSIC_VIDEO_TYPE_ATV",
        )
    }

    private fun JsonObject.flexText(index: Int): String? =
        findObjects("flexColumns").getOrNull(index)?.allRunText()?.takeIf(String::isNotBlank)

    private fun JsonObject.flexTexts(): List<String> =
        findObjects("flexColumns").mapNotNull { it.allRunText().takeIf(String::isNotBlank) }

    private fun JsonElement.allRunText(): String =
        findStrings("text").joinToString("").trim()

    private fun parseLrc(raw: String): List<LyricLine> = raw.lineSequence().mapNotNull { line ->
        val timestamp = line.substringAfter('[', "").substringBefore(']', "")
        val text = line.substringAfter(']', "").trim()
        if (timestamp.isBlank() || text.isBlank()) return@mapNotNull null
        val minutes = timestamp.substringBefore(':').toLongOrNull() ?: 0L
        val seconds = timestamp.substringAfter(':').toDoubleOrNull() ?: 0.0
        LyricLine((minutes * 60_000 + (seconds * 1000)).toLong(), text)
    }.toList()

    private fun String.toDurationMs(): Long {
        val parts = split(':').mapNotNull { it.toLongOrNull() }
        return when (parts.size) {
            2 -> (parts[0] * 60 + parts[1]) * 1000L
            3 -> (parts[0] * 3600 + parts[1] * 60 + parts[2]) * 1000L
            else -> 0L
        }
    }

    private fun String.looksLikeDurationOrStats(): Boolean =
        matches(Regex("\\d{1,2}:\\d{2}(:\\d{2})?")) ||
            contains("play", ignoreCase = true) ||
            contains("view", ignoreCase = true) ||
            contains("subscriber", ignoreCase = true) ||
            matches(Regex("(?i)(jan|feb|mar|apr|may|jun|jul|aug|sep|oct|nov|dec).*"))

    /** "A & B", "A, B", "A feat. B", "A x B" -> normalized names of A and B. */
    private fun String.artistNames(): List<String> =
        split(Regex("\\s*(?:,|&|\\band\\b|\\bfeat\\.?|\\bft\\.?|\\bfeaturing\\b|\\bwith\\b|\\sx\\s)\\s*", RegexOption.IGNORE_CASE))
            .map { it.lowercase().replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim() }
            .filter(String::isNotBlank)

    /** A year or a release kind ("Album • 2024"), never an album's artist. */
    private fun String.isNotAlbumArtistText(): Boolean {
        val text = trim()
        return text.isBlank() || text.matches(Regex("\\d{4}")) ||
            text.lowercase() in setOf("album", "single", "ep", "playlist", "audiobook")
    }

    private fun String.normalizedTitle(): String = lowercase()
        .replace(Regex("[^a-z0-9]+"), " ")
        .trim()

    private fun List<CatalogItem>.sortedByExactTitle(query: String): List<CatalogItem> =
        sortedWith(compareBy<CatalogItem> { it.title.normalizedTitle() != query.normalizedTitle() }
            .thenBy { !it.title.contains(query, ignoreCase = true) })

    private fun List<Track>.preferOfficial(): List<Track> {
        val official = filter(Track::isOfficial)
        return if (official.isNotEmpty()) official else this
    }

    private fun JsonElement.findString(key: String): String? = when (this) {
        is JsonObject -> entries.firstNotNullOfOrNull { (k, v) ->
            if (k == key && v is JsonPrimitive && v.contentOrNull?.isNotBlank() == true) v.content
            else v.findString(key)
        }
        is JsonArray -> firstNotNullOfOrNull { it.findString(key) }
        else -> null
    }

    private fun JsonElement.findObjects(key: String): List<JsonObject> = when (this) {
        is JsonObject -> entries.flatMap { (k, v) ->
            if (k == key && v is JsonObject) listOf(v)
            else if (k == key && v is JsonArray) v.filterIsInstance<JsonObject>()
            else v.findObjects(key)
        }
        is JsonArray -> flatMap { it.findObjects(key) }
        else -> emptyList()
    }

    private fun JsonObject.bestArtworkUrl(): String? =
        findObjects("thumbnails")
            .mapNotNull { thumbnail ->
                val url = thumbnail["url"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                val width = thumbnail["width"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 0
                val height = thumbnail["height"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 0
                Triple(url, width, height)
            }
            .maxByOrNull { (_, width, height) -> width * height }
            ?.first

    private fun JsonObject.catalogItems(): List<CatalogItem> =
        (findObjects("musicResponsiveListItemRenderer") + findObjects("musicTwoRowItemRenderer"))
            .mapNotNull { item ->
                if (item.isSongItem()) return@mapNotNull null
                // A result row may contain artist, album and menu browse IDs.
                // Android uses the endpoint attached to the title column.
                val browseId = item["navigationEndpoint"]?.findString("browseId")
                    ?: item["title"]?.findString("browseId")
                    ?: item.findObjects("flexColumns").firstOrNull()?.findString("browseId")
                    ?: item.findString("browseId")
                    ?: return@mapNotNull null
                val title = item.flexText(0)
                    ?: item["title"]?.findString("text")
                    ?: item.findString("text")
                    ?: return@mapNotNull null
                val subtitle = item.flexText(1)
                    ?: item["subtitle"]?.allRunText().orEmpty()
                CatalogItem(browseId, title, subtitle, item.bestArtworkUrl())
            }
                        .filter { item ->
                item.subtitle.startsWith("Artist", true) || item.subtitle.startsWith("Album", true) ||
                    item.subtitle.startsWith("Single", true) || item.subtitle.contains("Playlist", true) ||
                    item.browseId.startsWith("UC") || item.isAlbumBrowseItem() || item.browseId.startsWith("VL")
            }
            .distinctBy(CatalogItem::browseId)

    /**
     * Whether a catalog/search entry is a song or video rather than an album,
     * playlist or artist. Album/playlist cards (musicTwoRowItemRenderer) can
     * embed a play button with a videoId, so for cards the card's own
     * navigation decides; list rows keep the original "has a videoId" rule.
     */
    private fun JsonObject.isSongItem(): Boolean {
        val navigation = this["navigationEndpoint"] as? JsonObject
        val isCard = this["title"] != null && this["subtitle"] != null && this["flexColumns"] == null
        if (isCard && navigation != null) {
            if (navigation["browseEndpoint"] != null) return false
            if (navigation["watchEndpoint"] != null) return true
        }
        return findString("videoId") != null
    }

    private fun CatalogItem.isAlbumBrowseItem(): Boolean =
        browseId.startsWith("MPRE", ignoreCase = true) ||
            browseId.startsWith("OLAK5uy_", ignoreCase = true)

    private fun JsonElement.findStrings(key: String): List<String> = when (this) {
        is JsonObject -> entries.flatMap { (k, v) ->
            if (k == key && v is JsonPrimitive && v.contentOrNull != null) listOf(v.content)
            else v.findStrings(key)
        }
        is JsonArray -> flatMap { it.findStrings(key) }
        else -> emptyList()
    }

    private fun JsonElement.findArrays(key: String): List<JsonArray> = when (this) {
        is JsonObject -> entries.flatMap { (k, v) ->
            if (k == key && v is JsonArray) listOf(v)
            else v.findArrays(key)
        }
        is JsonArray -> flatMap { it.findArrays(key) }
        else -> emptyList()
    }

    private suspend fun HttpClient.postJson(url: String, body: String, timeoutMs: Long? = null, withAccount: Boolean = true): JsonObject {
        var lastFailure: Throwable? = null
        repeat(2) { attempt ->
            try {
                return post(url) {
                    if (timeoutMs != null) timeout {
                        requestTimeoutMillis = timeoutMs
                        socketTimeoutMillis = timeoutMs
                    }
                    contentType(ContentType.Application.Json)
                    header("Accept", "application/json")
                    header("Accept-Language", "en-US,en;q=0.9")
                    header("Cache-Control", "no-cache")
                    header("Origin", "https://music.youtube.com")
                    header("X-Origin", "https://music.youtube.com")
                    header("Referer", "https://music.youtube.com/")
                    header("User-Agent", DEFAULT_STREAM_USER_AGENT)
                    header("X-Goog-Api-Format-Version", "1")
                    header("X-YouTube-Client-Name", "67")
                    header("X-YouTube-Client-Version", CLIENT_VERSION)
                    WindowsPreferences.googleSession()?.takeIf { withAccount }?.let { session ->
                        header("Cookie", session.cookie)
                        header("Authorization", session.sapisidHash())
                        header("X-Goog-AuthUser", "0")
                        if (session.visitorData.isNotBlank()) header("X-Goog-Visitor-Id", session.visitorData)
                        // The account's page id: only while YouTube Music accepts it (see validateGoogleSession).
                        if (session.dataSyncId.isNotBlank() && WindowsPreferences.googleUsePageId()) header("X-Goog-PageId", session.dataSyncId)
                    }
                    setBody(body)
                }.body()
            } catch (error: Throwable) {
                lastFailure = error
                if (attempt == 0) delay(220L)
            }
        }
        throw lastFailure ?: IllegalStateException("YouTube Music request failed")
    }

    private fun clientContext(): JsonObject = buildJsonObject {
        put("client", buildJsonObject {
            put("clientName", "WEB_REMIX")
            put("clientVersion", CLIENT_VERSION)
            put("hl", "en")
            // Settings > Content > Country or region (the language stays English: pages are read in English).
            put("gl", AppSettings.contentRegion.value.ifBlank { "US" })
            WindowsPreferences.googleSession()?.visitorData?.takeIf(String::isNotBlank)?.let { put("visitorData", it) }
        })
    }

    private fun WindowsPreferences.GoogleSession.sapisidHash(origin: String = "https://music.youtube.com"): String {
        fun cookieValue(name: String) = cookie.split(';').map(String::trim)
            .firstOrNull { it.startsWith("$name=") }
            ?.substringAfter('=')
        val timestamp = currentTimeMillis() / 1000
        fun hash(secret: String) = digestHex("sha-1", "$timestamp $secret $origin".encodeToByteArray())
        // As the YouTube Music website sends it: the first- and third-party variants too
        // (some sessions are only accepted with them).
        return buildList {
            add("SAPISIDHASH ${timestamp}_${hash(cookieValue("SAPISID").orEmpty())}")
            cookieValue("__Secure-1PAPISID")?.takeIf(String::isNotBlank)?.let { add("SAPISID1PHASH ${timestamp}_${hash(it)}") }
            cookieValue("__Secure-3PAPISID")?.takeIf(String::isNotBlank)?.let { add("SAPISID3PHASH ${timestamp}_${hash(it)}") }
        }.joinToString(" ")
    }

    private fun browseBody(browseId: String? = null, continuation: String? = null, params: String? = null): String =
        buildJsonObject {
            put("context", clientContext())
            browseId?.let { put("browseId", it) }
            continuation?.let { put("continuation", it) }
            params?.let { put("params", it) }
        }.toString()

    private fun String?.isNullAsText() = this == null || this.equals("null", ignoreCase = true) || isBlank()

    init {
        // Declared after every property on purpose: the warm-up runs on another
        // thread and must only see a fully constructed repository.
        // Warms the visitor id so the first song does not pay for it.
        if (anonymousVisitorData == null) visitorWarmupScope.launch { playbackVisitorData() }
    }

    companion object {
        private const val API_KEY = "AIzaSyC9XL3ZjWddXya6X74dJoCTL-WEYFDNX3"
        private const val CLIENT_VERSION = "1.20260806.01.00"
        /** Continuation pages followed for an artist's "Show All" page. */
        private const val MAX_SHELF_PAGES = 30
        private val visitorWarmupScope = CoroutineScope(SupervisorJob() + IoDispatcher)
        @kotlin.concurrent.Volatile private var sharedVisitorData: String? = null
        @kotlin.concurrent.Volatile private var visitorDataRetryAfterMs = 0L
        private val visitorDataLock = kotlinx.coroutines.sync.Mutex()
        private const val PLAYBACK_CACHE_DOWNLOAD_DELAY_MS = 2_500L
        /** yt-dlp is a slow subprocess; it only starts if nothing else won within this time. */
        private const val YT_DLP_START_DELAY_MS = 1_500L
        private val VISITOR_DATA_PATTERN = Regex(""""VISITOR_DATA"\s*:\s*"([^"]+)"""")
        /** Home shelves: how many items each personal shelf and expanded shelf holds. */
        private const val QUICK_PICKS_SIZE = 32
        private val QUALITY_LABEL_HEIGHT = Regex("^(\\d{3,4})p")
        private const val KEEP_LISTENING_SIZE = 30
        private const val DAILY_DISCOVER_SEEDS = 6
        private const val DAILY_DISCOVER_PER_SEED = 4
        private const val DAILY_DISCOVER_SIZE = 24
        private const val EXPANDED_SHELF_SIZE = 60
        /** Songs from the Songs search added to the search suggestions. */
        private const val SUGGESTED_SONGS = 5
        private const val DEFAULT_STREAM_USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/131 Safari/537.36"
        private val PLAYBACK_CLIENTS = listOf(
            // Android's first stream fallback (see YTPlayerUtils.STREAM_FALLBACK_CLIENTS):
            // direct, unthrottled audio URLs without poToken or player.js. It only
            // returns formats when a visitor id is supplied.
            PlaybackClient(
                name = "VISIONOS",
                version = "0.1",
                id = "101",
                userAgent = "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/18.0 Safari/605.1.15",
                contextExtras = "\"osName\":\"visionOS\",\"osVersion\":\"1.3.21O771\",\"deviceMake\":\"Apple\",\"deviceModel\":\"RealityDevice14,1\"",
                requiresJsPlayer = false,
                sendVisitorData = true,
            ),
            PlaybackClient(
                name = "WEB_REMIX",
                version = "1.20231214.00.00",
                id = "67",
                userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Safari/537.36",
                contextExtras = "\"osName\":\"Windows\",\"osVersion\":\"10.0\",\"deviceMake\":\"\",\"deviceModel\":\"\""
            ),
            PlaybackClient(
                name = "TVHTML5_SIMPLY_EMBEDDED_PLAYER",
                version = "2.0",
                id = "85",
                userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Safari/537.36",
                contextExtras = "\"osName\":\"Windows\",\"osVersion\":\"10.0\",\"deviceMake\":\"\",\"deviceModel\":\"\"",
            ),
            PlaybackClient(
                name = "ANDROID_VR",
                version = "1.43.32",
                id = "28",
                userAgent = "com.google.android.apps.youtube.vr.oculus/1.43.32 (Linux; U; Android 12; en_US; Quest 3; Build/SQ3A.220605.009.A1; Cronet/107.0.5284.2)",
                contextExtras = "\"osName\":\"Android\",\"osVersion\":\"12\",\"deviceMake\":\"Oculus\",\"deviceModel\":\"Quest 3\",\"androidSdkVersion\":\"32\"",
                requiresJsPlayer = false,
            ),
            PlaybackClient(
                name = "ANDROID_VR",
                version = "1.61.48",
                id = "28",
                userAgent = "com.google.android.apps.youtube.vr.oculus/1.61.48 (Linux; U; Android 12; en_US; Quest 3; Build/SQ3A.220605.009.A1; Cronet/132.0.6808.3)",
                contextExtras = "\"osName\":\"Android\",\"osVersion\":\"12\",\"deviceMake\":\"Oculus\",\"deviceModel\":\"Quest 3\",\"androidSdkVersion\":\"32\"",
                requiresJsPlayer = false,
            ),
            PlaybackClient(
                name = "IOS",
                version = "21.03.1",
                id = "5",
                userAgent = "com.google.ios.youtube/21.03.1 (iPhone16,2; U; CPU iOS 18_2 like Mac OS X;)",
                contextExtras = "\"osName\":\"iPhone\",\"osVersion\":\"18.2.22C152\",\"deviceMake\":\"Apple\",\"deviceModel\":\"iPhone16,2\"",
                requiresJsPlayer = false,
            ),
        )
    }
}

// Force recompile
