package com.recordlabs.desktop

import com.recordlabs.desktop.platform.*

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import com.recordlabs.desktop.data.RichLyricLine
import com.recordlabs.desktop.data.Track
import com.recordlabs.desktop.data.WindowsLyricsProviderRegistry
import com.recordlabs.desktop.data.WindowsRichLyrics
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** What the Android home-screen widget shows: the song, playing or not, and up to three lyric lines. */
data class NowPlayingInfo(
    val trackId: String,
    val title: String,
    val artist: String,
    val artworkUrl: String?,
    val isPlaying: Boolean,
    /** The line being sung first, then the next ones (at most three); empty without lyrics. */
    val lyrics: List<String>,
    /** False when the first line is not being sung yet (before the first line, unsynced lyrics). */
    val firstLineActive: Boolean,
)

/**
 * The playing song for the Android home-screen widget. The Android app sets [sink]
 * (and [lyricsWanted], true while a widget is on the home screen); the player
 * ([NowPlayingFeedEffect] in the app window) reports every change. The desktop leaves
 * them unset, so nothing is done there.
 */
object NowPlayingFeed {
    @kotlin.concurrent.Volatile var sink: ((NowPlayingInfo) -> Unit)? = null
    @kotlin.concurrent.Volatile var lyricsWanted: () -> Boolean = { false }

    /**
     * A widget button pressed while the app was closed ("play", "next", "previous"):
     * the app opens and runs it once the last song is back.
     */
    var pendingCommand by mutableStateOf<String?>(null)

    /** Bumped to send the current state again (a widget was just added). */
    internal var refreshRequests by mutableIntStateOf(0)

    fun requestRefresh() { refreshRequests++ }
}

@Composable
internal fun NowPlayingFeedEffect(track: Track?, isPlaying: Boolean, positionMs: () -> Long) {
    val sink = NowPlayingFeed.sink ?: return
    val latestPosition by rememberUpdatedState(positionMs)
    val usable = track?.takeUnless { it.id.startsWith("pending:") }
    var lines by remember(usable?.id) { mutableStateOf<List<RichLyricLine>>(emptyList()) }
    var lyricsGeneration by remember { mutableIntStateOf(0) }
    // Lyrics only while a widget is on the home screen (no extra lookups otherwise).
    LaunchedEffect(usable?.id, lyricsGeneration, NowPlayingFeed.refreshRequests) {
        val song = usable ?: return@LaunchedEffect
        if (!NowPlayingFeed.lyricsWanted()) return@LaunchedEffect
        val raw = withContext(IoDispatcher) {
            runCatching { WindowsLyricsProviderRegistry.getLyricsResult(song).lyrics }.getOrDefault("")
        }
        lines = withContext(Dispatchers.Default) {
            runCatching { WindowsRichLyrics.parse(raw) }.getOrDefault(emptyList())
                .filter { !it.isBackground && it.text.isNotBlank() }
        }
    }
    // Another version of the lyrics chosen on the lyrics page.
    DisposableEffect(usable?.id) {
        val id = usable?.id
        val unsubscribe = WindowsLyricsProviderRegistry.addListener { updatedId, _ -> if (updatedId == id) lyricsGeneration++ }
        onDispose { unsubscribe() }
    }
    LaunchedEffect(usable?.id, isPlaying, lines, NowPlayingFeed.refreshRequests) {
        val song = usable ?: return@LaunchedEffect
        var last: NowPlayingInfo? = null
        val synced = lines.size > 1 && lines.any { it.startMs != lines.first().startMs }
        while (true) {
            val position = latestPosition()
            val current = if (synced) lines.indexOfLast { it.startMs <= position } else -1
            val start = current.coerceAtLeast(0)
            val info = NowPlayingInfo(
                trackId = song.id,
                title = song.title,
                artist = song.artist,
                artworkUrl = song.artworkUrl,
                isPlaying = isPlaying,
                lyrics = lines.drop(start).take(3).map { it.text.trim() },
                firstLineActive = current >= 0,
            )
            if (info != last) {
                last = info
                runCatching { sink(info) }
            }
            if (!isPlaying || !synced) break
            kotlinx.coroutines.delay(400)
        }
    }
}
