package com.recordlabs.desktop

import com.recordlabs.desktop.platform.*

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.recordlabs.desktop.data.HomeFeed
import com.recordlabs.desktop.data.HomeItem
import com.recordlabs.desktop.data.HomeShelf
import com.recordlabs.desktop.data.Track
import com.recordlabs.desktop.data.WindowsLibrarySnapshot
import com.recordlabs.desktop.data.mostPlayed
import com.recordlabs.desktop.ui.LineIcons

/*
 * Speed dial (as in YouTube Music and the earlier Android app): a grid of the
 * songs the user goes back to most, at the top of Home. The songs pinned with
 * "Pin to speed dial" come first, then YouTube Music's own Speed dial shelf
 * (when the account's home has one), the songs most listened to, the recent
 * ones and the account's Quick picks, up to [SPEED_DIAL_SIZE].
 */

internal const val SPEED_DIAL_SIZE = 27
private const val SPEED_DIAL_PAGE = 9

/** YouTube Music's own Speed dial shelf (shown inside our section, not twice). */
internal fun HomeShelf.isSpeedDialShelf(): Boolean = title.trim().equals("Speed dial", ignoreCase = true)

internal fun speedDialItems(library: WindowsLibrarySnapshot?, feed: HomeFeed?): List<HomeItem> {
    fun Track.asItem() = HomeItem(title = title, subtitle = artist, artworkUrl = artworkUrl, track = this)
    val candidates = buildList {
        library?.pinned?.forEach { add(it.asItem()) }
        feed?.shelves?.filter { it.isSpeedDialShelf() }?.forEach { shelf -> addAll(shelf.items.filter { it.track != null }) }
        library?.mostPlayed(limit = SPEED_DIAL_SIZE)?.forEach { add(it.asItem()) }
        library?.history?.take(SPEED_DIAL_SIZE)?.forEach { add(it.asItem()) }
        feed?.shelves?.filter { it.title.trim().equals("Quick picks", ignoreCase = true) }
            ?.forEach { shelf -> addAll(shelf.items.filter { it.track != null }) }
    }
    return candidates
        .filter { it.track != null && !it.track.id.startsWith("pending:") }
        .distinctBy { it.track!!.id }
        // The same song in two versions (album and single) counts once.
        .distinctBy { "${it.track!!.title.trim().lowercase()}|${it.track.artist.trim().lowercase()}" }
        .take(SPEED_DIAL_SIZE)
}

/**
 * The dice (as in YouTube Music): a song picked at random, but weighted by your own
 * listening, so it lands on what you like. The more (and the more recently) you
 * played a song, or songs by its artist, the likelier it is; pinned songs, your
 * account's own Speed dial and Quick picks (YouTube Music's personalised picks) and
 * your liked songs count too. The song just played and the last few are left out, so
 * it never repeats them. Its radio follows (also personalised for the account).
 */
internal fun speedDialDicePick(library: WindowsLibrarySnapshot?, feed: HomeFeed?, random: kotlin.random.Random = kotlin.random.Random.Default): Track? {
    val now = currentTimeMillis()
    val weights = LinkedHashMap<String, Pair<Track, Double>>()
    fun add(track: Track?, weight: Double) {
        if (track == null || track.id.startsWith("pending:") || weight <= 0.0) return
        val before = weights[track.id]
        weights[track.id] = track to ((before?.second ?: 0.0) + weight)
    }
    // How much each artist is listened to (minutes, the recent weeks counting double).
    val artistMinutes = HashMap<String, Double>()
    library?.listenedTracks?.forEach { listened ->
        val minutes = listened.totalPlayTimeMs / 60_000.0
        val recent = if (now - listened.lastListenedMs < 14L * 24 * 3_600_000L) 2.0 else 1.0
        add(listened.track, kotlin.math.sqrt(minutes) * recent)
        val artist = listened.track.artist.trim().lowercase()
        if (artist.isNotEmpty()) artistMinutes[artist] = (artistMinutes[artist] ?: 0.0) + minutes * recent
    }
    val topArtist = artistMinutes.values.maxOrNull()?.takeIf { it > 0.0 } ?: 1.0
    fun artistBonus(track: Track): Double =
        2.0 * ((artistMinutes[track.artist.trim().lowercase()] ?: 0.0) / topArtist)
    library?.pinned?.forEach { add(it, 3.0 + artistBonus(it)) }
    feed?.shelves?.forEach { shelf ->
        val base = when {
            shelf.isSpeedDialShelf() -> 2.0
            shelf.title.trim().equals("Quick picks", ignoreCase = true) -> 1.5
            else -> 0.0
        }
        if (base > 0.0) shelf.items.forEach { item -> item.track?.let { add(it, base + artistBonus(it)) } }
    }
    library?.liked?.take(200)?.forEach { add(it, 1.0 + artistBonus(it)) }
    // Not the song playing now or the ones just before it.
    val recent = library?.history?.take(5)?.map { it.id }?.toSet().orEmpty()
    val pool = weights.values.filter { it.first.id !in recent }.ifEmpty { weights.values.toList() }
    val total = pool.sumOf { it.second }
    if (pool.isEmpty() || total <= 0.0) return null
    var target = random.nextDouble() * total
    for ((track, weight) in pool) {
        target -= weight
        if (target <= 0.0) return track
    }
    return pool.last().first
}

/** The dice's card: what [speedDialDicePick] chose, played with its radio after it. */
internal const val DICE_ITEM_KIND = "Dice"

@OptIn(ExperimentalComposeUiApi::class)
@Composable
internal fun SpeedDialSection(
    items: List<HomeItem>,
    pinnedIds: Set<String>,
    compact: Boolean,
    onItemClick: (HomeItem, List<HomeItem>) -> Unit,
    onTrackMore: (Track) -> Unit,
    onDice: () -> Unit,
) {
    if (items.isEmpty()) return
    // As in YouTube Music: the account's picture and name above the title.
    val accountName = remember { com.recordlabs.desktop.data.WindowsPreferences.googleAccountName().trim() }
    val accountPhoto = remember { com.recordlabs.desktop.data.WindowsPreferences.googleAccountPhoto().trim() }
    Column {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            if (accountPhoto.isNotEmpty()) {
                Artwork(accountPhoto, Modifier.size(42.dp), CircleShape)
                Spacer(Modifier.width(12.dp))
            }
            Column(Modifier.weight(1f)) {
                if (accountName.isNotEmpty()) Text(
                    accountName.uppercase(),
                    color = Color.White.copy(alpha = 0.6f),
                    fontSize = 12.sp,
                    letterSpacing = 0.6.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text("Speed dial", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
            }
        }
        Spacer(Modifier.height(12.dp))
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val android = AppPlatform.isPhone
            val gap = if (!compact || !android) 12.dp else 8.dp
            // Each tile is as large as a Daily discover card (desktop, and Android in
            // landscape). Android portrait: a page of 3 x 3 fills the width (the next one
            // peeks in), as in YouTube Music.
            val tile = when {
                !compact -> adaptiveCardWidth(maxWidth, 160.dp)
                android -> (maxWidth - 28.dp - gap * 2) / 3
                else -> 128.dp
            }
            val pageWidth = tile * 3 + gap * 2
            val rowState = rememberLazyListState()
            // The dice sits in the first page's top right corner, as in YouTube Music.
            val tiles: List<HomeItem?> = remember(items) {
                items.take(SPEED_DIAL_SIZE - 1).toMutableList<HomeItem?>().apply { add(minOf(2, size), null) }
            }
            LazyRow(
                state = rowState,
                // Desktop: the mouse wheel scrolls the pages sideways, as in the other shelves.
                modifier = Modifier.smoothHorizontalScroll(rowState),
                horizontalArrangement = Arrangement.spacedBy(if (!compact || !android) gap else 14.dp),
            ) {
                items(tiles.chunked(SPEED_DIAL_PAGE)) { page ->
                    Column(Modifier.width(pageWidth), verticalArrangement = Arrangement.spacedBy(gap)) {
                        page.chunked(3).forEach { row ->
                            Row(horizontalArrangement = Arrangement.spacedBy(gap)) {
                                row.forEach { item ->
                                    if (item == null) DiceTile(Modifier.size(tile), onDice)
                                    else SpeedDialTile(item, items, tile, item.track?.id?.let { it in pinnedIds } == true, onItemClick, onTrackMore)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun SpeedDialTile(
    item: HomeItem,
    items: List<HomeItem>,
    tile: androidx.compose.ui.unit.Dp,
    pinned: Boolean,
    onItemClick: (HomeItem, List<HomeItem>) -> Unit,
    onTrackMore: (Track) -> Unit,
) {
    val track = item.track ?: return
    Box(
        Modifier
            .size(tile)
            .clip(RoundedCornerShape(10.dp))
            .onPointerEvent(PointerEventType.Press, PointerEventPass.Initial) { event ->
                if (event.buttons.isSecondaryPressed) onTrackMore(track)
            }
            .longPressMenu { onTrackMore(track) }
            .clickable { onItemClick(item, items) },
    ) {
        Artwork(item.artworkUrl, Modifier.fillMaxSize(), RoundedCornerShape(10.dp))
        // The title over a dark fade at the bottom, like YouTube Music's tiles.
        Box(
            Modifier.fillMaxSize().background(
                Brush.verticalGradient(
                    0.45f to Color.Transparent,
                    1f to Color.Black.copy(alpha = 0.78f),
                ),
            ),
        )
        Text(
            item.title,
            color = Color.White,
            fontSize = 12.sp,
            lineHeight = 14.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.align(Alignment.BottomStart).padding(start = 8.dp, end = 8.dp, bottom = 7.dp),
        )
        if (pinned) {
            Box(
                Modifier.align(Alignment.TopEnd).padding(6.dp).size(20.dp).clip(CircleShape)
                    .background(Color.Black.copy(alpha = 0.55f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(LineIcons.Pin, "Pinned", tint = Color.White, modifier = Modifier.size(13.dp))
            }
        }
    }
}

/** The dice card: dark, with the three-dot face of a die (as in YouTube Music). */
@Composable
private fun DiceTile(modifier: Modifier, onClick: () -> Unit) {
    val dot = Color(0xFFB9B8E6)
    Box(
        modifier
            .clip(RoundedCornerShape(10.dp))
            .background(Color(0xFF0B0A10))
            .drawBehind {
                // A dark card, a little red at the top right and violet at the bottom left.
                drawRect(
                    Brush.radialGradient(
                        listOf(Color(0xFF6D2A44).copy(alpha = 0.7f), Color.Transparent),
                        center = androidx.compose.ui.geometry.Offset(size.width, 0f),
                        radius = size.width * 0.9f,
                    ),
                )
                drawRect(
                    Brush.radialGradient(
                        listOf(Color(0xFF6B67A6).copy(alpha = 0.85f), Color.Transparent),
                        center = androidx.compose.ui.geometry.Offset(0f, size.height),
                        radius = size.width * 1.1f,
                    ),
                )
            }
            .clickable(onClick = onClick)
            .semantics { contentDescription = "Play something at random" },
    ) {
        androidx.compose.foundation.Canvas(Modifier.fillMaxSize()) {
            val r = size.minDimension * 0.1f
            listOf(0.23f to 0.23f, 0.5f to 0.5f, 0.77f to 0.77f).forEach { (x, y) ->
                drawCircle(dot, radius = r, center = androidx.compose.ui.geometry.Offset(size.width * x, size.height * y))
            }
        }
    }
}
