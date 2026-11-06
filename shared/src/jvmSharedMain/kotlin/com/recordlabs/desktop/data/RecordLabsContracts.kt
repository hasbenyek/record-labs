package com.recordlabs.desktop.data

import kotlinx.serialization.Serializable

/** Platform-neutral models used by the Windows UI and future shared services. */
@Serializable
data class Track(
    val id: String,
    val title: String,
    val artist: String,
    val album: String = "",
    val streams: String = "",
    val artworkUrl: String? = null,
    val durationMs: Long = 0L,
    val explicit: Boolean = false,
    val musicVideoType: String? = null,
    val isOfficial: Boolean = false,
    val localPath: String? = null,
)

/**
 * True when the item carries a concrete YouTube Music type (song, official
 * music video, user video, podcast episode...). Such ids are played exactly as
 * chosen, like YouTube Music web does, instead of being swapped for a
 * search-matched "official audio" track.
 */
val Track.playsAsIs: Boolean
    get() = isOfficial || !musicVideoType.isNullOrBlank()

/**
 * The other version of a song on YouTube Music: the official music video of
 * a song, or the song (audio) of a music video, as its player offers with the
 * Song / Video switch. [segments] line up the two timelines (a video often has
 * a longer intro), so playback carries on at the same moment of the music.
 */
data class TrackCounterpart(
    val track: Track,
    val segments: List<CounterpartSegment> = emptyList(),
) {
    /** Where [positionMs] of the current version is in [track]. */
    fun mapPosition(positionMs: Long): Long {
        if (segments.isEmpty()) return positionMs
        val inside = segments.firstOrNull { positionMs >= it.fromStartMs && positionMs < it.fromStartMs + it.durationMs }
        if (inside != null) return inside.toStartMs + (positionMs - inside.fromStartMs)
        // Between or around the matched parts: the nearest one before (else the first).
        val before = segments.filter { it.fromStartMs <= positionMs }.maxByOrNull { it.fromStartMs }
        return if (before != null) before.toStartMs + before.durationMs else segments.minOf { it.toStartMs }
    }

    /** The same pair seen from [track]'s side (to switch back). */
    fun reversed(current: Track): TrackCounterpart =
        TrackCounterpart(current, segments.map { CounterpartSegment(it.toStartMs, it.fromStartMs, it.durationMs) })
}

/** A part of the music that plays at [fromStartMs] in one version and [toStartMs] in the other. */
data class CounterpartSegment(val fromStartMs: Long, val toStartMs: Long, val durationMs: Long)

/** Official or user music videos: the player shows the video instead of the cover. */
val Track.hasMusicVideo: Boolean
    get() = localPath == null &&
        (musicVideoType == "MUSIC_VIDEO_TYPE_OMV" || musicVideoType == "MUSIC_VIDEO_TYPE_UGC")

/**
 * Page kind for a YouTube Music browse id, using the server's page type when
 * known and the id's well-known prefix otherwise; [fallback] keeps the old
 * subtitle-based guess for anything unrecognised.
 */
fun catalogKindFor(browseId: String?, pageType: String? = null, fallback: () -> String): String = when {
    pageType == "MUSIC_PAGE_TYPE_ALBUM" || pageType == "MUSIC_PAGE_TYPE_AUDIOBOOK" -> "Album"
    pageType == "MUSIC_PAGE_TYPE_PLAYLIST" -> "Playlist"
    pageType == "MUSIC_PAGE_TYPE_ARTIST" || pageType == "MUSIC_PAGE_TYPE_USER_CHANNEL" -> "Artist"
    browseId == null -> fallback()
    browseId.startsWith("MPRE") || browseId.startsWith("OLAK5uy_") -> "Album"
    browseId.startsWith("VL") || browseId.startsWith("RDCLAK") || browseId.startsWith("PL") -> "Playlist"
    browseId.startsWith("UC") -> "Artist"
    else -> fallback()
}

@Serializable
data class LyricLine(
    val startMs: Long,
    val text: String,
)

data class SearchResults(
    val tracks: List<Track> = emptyList(),
    val albums: List<CatalogItem> = emptyList(),
    val artists: List<CatalogItem> = emptyList(),
    val playlists: List<CatalogItem> = emptyList(),
    val summaries: List<SearchSummaryShelf> = emptyList(),
    val filters: List<String> = emptyList(),
    /** YouTube Music's own "Top result" for the query (search suggestions only). */
    val topResult: SearchTopResult? = null,
)

/**
 * The best match for a search: a song/video ([track]) or an artist, album or
 * playlist ([item], opened as [kind]).
 */
data class SearchTopResult(
    val kind: String,
    val track: Track? = null,
    val item: CatalogItem? = null,
)

data class CatalogItem(
    val browseId: String,
    val title: String,
    val subtitle: String = "",
    val artworkUrl: String? = null,
)

data class CatalogPageData(
    val title: String,
    val subtitle: String = "",
    val description: String = "",
    val artworkUrl: String? = null,
    val tracks: List<Track> = emptyList(),
        val albums: List<CatalogItem> = emptyList(),
    val shelves: List<CatalogShelf> = emptyList(),
    /** Album pages: the album's artist and release year. */
    val artist: String = "",
    val year: String = "",
)

data class CatalogShelf(
    val title: String,
    val items: List<Any>, // Can be Track or CatalogItem
    val moreEndpoint: String? = null,
    val moreParams: String? = null
)

data class SearchSummaryShelf(
    val title: String,
    val items: List<Track>
)

enum class HomeShelfLayout { Cards, List }

/** An item returned by the actual YouTube Music home carousel.
 * [track] is non-null only when InnerTube exposes a playable video id. */
data class HomeItem(
    val title: String,
    val subtitle: String = "",
    val artworkUrl: String? = null,
    val track: Track? = null,
    val browseId: String? = null,
    val kind: String? = null,
    /** A card that opens a web page (a concert / live event and its tickets). */
    val url: String? = null,
)

data class HomeShelf(
    val title: String,
    val items: List<HomeItem>,
    val layout: HomeShelfLayout = HomeShelfLayout.Cards,
)

data class HomeChip(
    val title: String,
    val params: String,
)

data class HomeFeed(
    val shelves: List<HomeShelf> = emptyList(),
    val continuation: String? = null,
    val chips: List<HomeChip> = emptyList(),
)

interface CatalogRepository {
    suspend fun home(): HomeFeed
    suspend fun search(query: String, filter: String? = null): SearchResults
}

interface LyricsRepository {
    suspend fun lyrics(track: Track): List<LyricLine>
}

interface DownloadRepository {
    suspend fun download(track: Track): Result<Unit>
    fun isDownloaded(trackId: String): Boolean
}

interface PlaybackController {
    val currentTrack: Track?
    val isPlaying: Boolean
    fun play(track: Track)
    fun pause()
    fun resume()
    fun skipNext()
    fun skipPrevious()
}
