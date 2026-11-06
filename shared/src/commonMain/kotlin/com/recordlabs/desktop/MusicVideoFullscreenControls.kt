package com.recordlabs.desktop

import com.recordlabs.desktop.platform.*

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.DropdownMenu
import androidx.compose.material.DropdownMenuItem
import androidx.compose.material.Icon
import androidx.compose.material.IconButton
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Slider
import androidx.compose.material.SliderDefaults
import androidx.compose.material.Text
import androidx.compose.material.TextButton
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ClosedCaption
import androidx.compose.material.icons.filled.ClosedCaptionOff
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material.icons.filled.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.recordlabs.desktop.data.MusicVideoCaptionTrack
import com.recordlabs.desktop.ui.Heroicons

/**
 * Picture heights offered when the video's own list is not known (yet); null
 * is Auto.
 */
internal val MusicVideoQualityOptions: List<Int?> = listOf(null, 1080, 720, 480, 360)

internal fun musicVideoQualityLabel(height: Int?): String = when {
    height == null -> "Auto"
    height >= 2160 -> "${height}p 4K"
    height >= 1440 -> "${height}p 2K"
    else -> "${height}p"
}

/**
 * The height a choice actually plays at for a video offering [heights]: the
 * smallest one at least as high, else the highest (as the stream lookup picks).
 */
internal fun effectiveMusicVideoQuality(choice: Int?, heights: List<Int>): Int? {
    if (choice == null || heights.isEmpty()) return choice
    return heights.filter { it >= choice }.minOrNull() ?: heights.maxOrNull()
}

/**
 * Caption track for the chosen language: the same track (code and name), else
 * the same code, else the same language, else the first.
 */
internal fun List<MusicVideoCaptionTrack>.forCaptionLanguage(code: String?, name: String? = null): MusicVideoCaptionTrack? {
    if (code == null || isEmpty()) return null
    return firstOrNull { it.languageCode.equals(code, ignoreCase = true) && it.name == name }
        ?: firstOrNull { it.languageCode.equals(code, ignoreCase = true) }
        ?: firstOrNull { it.languageCode.substringBefore('-').equals(code.substringBefore('-'), ignoreCase = true) }
        ?: first()
}

/**
 * Bottom bar of the full-screen music video: a seek bar, previous / play-pause /
 * next, subtitles (when the video has uploader captions), the picture quality
 * and the exit button. Playback goes through the same callbacks as the rest of
 * the app (published in [FloatingLyricsState]).
 *
 * [onBusyChange] reports while the user is seeking or has a menu open, so the
 * bar does not hide in the middle of that.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
internal fun MusicVideoFullscreenControls(
    onExit: () -> Unit,
    onBusyChange: (Boolean) -> Unit,
    onPointerOnControls: (Boolean) -> Unit = {},
    /** Heights this video offers, highest first (empty = not known yet). */
    availableHeights: List<Int> = emptyList(),
    captionTracks: List<MusicVideoCaptionTrack> = emptyList(),
    /** The caption track showing now (null = subtitles off). */
    selectedCaption: MusicVideoCaptionTrack? = null,
    onSelectCaption: (MusicVideoCaptionTrack?) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val positionMs = FloatingLyricsState.positionMs
    val durationMs = FloatingLyricsState.durationMs
    val isPlaying = FloatingLyricsState.isPlaying
    val trackId = FloatingLyricsState.currentTrack?.id
    val safeDuration = durationMs.coerceAtLeast(1L)
    var isSeeking by remember(trackId) { mutableStateOf(false) }
    var pendingProgress by remember(trackId) { mutableStateOf(0f) }
    var qualityMenuOpen by remember { mutableStateOf(false) }
    var captionMenuOpen by remember { mutableStateOf(false) }
    val qualityChoices: List<Int?> = if (availableHeights.isEmpty()) MusicVideoQualityOptions else listOf<Int?>(null) + availableHeights
    val effectiveQuality = effectiveMusicVideoQuality(MusicVideoFullscreen.quality, availableHeights)
    LaunchedEffect(trackId, positionMs, safeDuration, isSeeking) {
        if (!isSeeking) pendingProgress = (positionMs.toFloat() / safeDuration).coerceIn(0f, 1f)
    }
    LaunchedEffect(isSeeking, qualityMenuOpen, captionMenuOpen) { onBusyChange(isSeeking || qualityMenuOpen || captionMenuOpen) }
    val shownPositionMs = if (isSeeking) (pendingProgress * safeDuration).toLong() else positionMs

    Column(
        modifier
            .fillMaxWidth()
            .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = .78f))))
            .padding(start = 28.dp, end = 28.dp, top = 56.dp, bottom = 18.dp)
            // Only the seek bar and buttons count as "on the controls", not the shade above.
            .onPointerEvent(PointerEventType.Enter) { onPointerOnControls(true) }
            .onPointerEvent(PointerEventType.Exit) { onPointerOnControls(false) },
    ) {
        Slider(
            value = pendingProgress,
            onValueChange = {
                isSeeking = true
                pendingProgress = it
            },
            onValueChangeFinished = {
                FloatingLyricsState.onSeek?.invoke((pendingProgress * safeDuration).toLong())
                isSeeking = false
            },
            enabled = durationMs > 0L,
            colors = SliderDefaults.colors(
                thumbColor = MaterialTheme.colors.primary,
                activeTrackColor = MaterialTheme.colors.primary,
                inactiveTrackColor = Color.White.copy(alpha = 0.3f),
            ),
            modifier = Modifier.fillMaxWidth(),
        )
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { FloatingLyricsState.onPrevious?.invoke() }) {
                Icon(Heroicons.Backward, "Previous", tint = Color.White, modifier = Modifier.size(28.dp))
            }
            IconButton(onClick = { FloatingLyricsState.onPlayPause?.invoke() }, modifier = Modifier.size(56.dp)) {
                Icon(if (isPlaying) Heroicons.PauseSolid else Heroicons.PlaySolid, if (isPlaying) "Pause" else "Play", tint = Color.White, modifier = Modifier.size(40.dp))
            }
            IconButton(onClick = { FloatingLyricsState.onNext?.invoke() }) {
                Icon(Heroicons.Forward, "Next", tint = Color.White, modifier = Modifier.size(28.dp))
            }
            Spacer(Modifier.width(14.dp))
            Text(
                "${shownPositionMs.toVideoClock()} / ${durationMs.toVideoClock()}",
                color = Color.White.copy(alpha = .85f),
                fontSize = 14.sp,
            )
            Spacer(Modifier.weight(1f))
            if (captionTracks.isNotEmpty()) {
                Box {
                    IconButton(onClick = { captionMenuOpen = true }) {
                        Icon(
                            if (selectedCaption != null) Icons.Default.ClosedCaption else Icons.Default.ClosedCaptionOff,
                            "Subtitles",
                            tint = if (selectedCaption != null) MaterialTheme.colors.primary else Color.White,
                            modifier = Modifier.size(26.dp),
                        )
                    }
                    DropdownMenu(
                        expanded = captionMenuOpen,
                        onDismissRequest = { captionMenuOpen = false },
                        modifier = Modifier.background(QualityMenuColor),
                    ) {
                        Text(
                            "Subtitles",
                            color = Color.White.copy(alpha = .65f),
                            fontSize = 12.sp,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                        )
                        val choices: List<MusicVideoCaptionTrack?> = listOf<MusicVideoCaptionTrack?>(null) + captionTracks
                        choices.forEach { track ->
                            val selected = track == selectedCaption
                            DropdownMenuItem(onClick = {
                                captionMenuOpen = false
                                onSelectCaption(track)
                            }) {
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                    Box(Modifier.size(18.dp)) {
                                        if (selected) Icon(Icons.Default.Check, null, tint = MaterialTheme.colors.primary, modifier = Modifier.size(18.dp))
                                    }
                                    Text(track?.name ?: "Off", color = Color.White)
                                }
                            }
                        }
                    }
                }
                Spacer(Modifier.width(4.dp))
            }
            Box {
                TextButton(onClick = { qualityMenuOpen = true }) {
                    Icon(Icons.Default.Settings, null, tint = Color.White, modifier = Modifier.size(22.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(
                        if (MusicVideoFullscreen.quality == null) "Auto" else musicVideoQualityLabel(effectiveQuality),
                        color = Color.White,
                        fontSize = 14.sp,
                    )
                }
                DropdownMenu(
                    expanded = qualityMenuOpen,
                    onDismissRequest = { qualityMenuOpen = false },
                    modifier = Modifier.background(QualityMenuColor),
                ) {
                    Text(
                        "Quality",
                        color = Color.White.copy(alpha = .65f),
                        fontSize = 12.sp,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                    )
                    qualityChoices.forEach { height ->
                        val selected = if (height == null) MusicVideoFullscreen.quality == null else MusicVideoFullscreen.quality != null && effectiveQuality == height
                        DropdownMenuItem(onClick = {
                            qualityMenuOpen = false
                            MusicVideoFullscreen.selectQuality(height)
                        }) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                Box(Modifier.size(18.dp)) {
                                    if (selected) Icon(Icons.Default.Check, null, tint = MaterialTheme.colors.primary, modifier = Modifier.size(18.dp))
                                }
                                Text(musicVideoQualityLabel(height), color = Color.White)
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.width(6.dp))
            IconButton(onClick = onExit) {
                Icon(Icons.Default.FullscreenExit, "Exit full screen video", tint = Color.White, modifier = Modifier.size(28.dp))
            }
        }
    }
}

private val QualityMenuColor = Color(0xFF252832)

private fun Long.toVideoClock(): String {
    if (this <= 0L) return "0:00"
    val totalSeconds = this / 1_000
    val hours = totalSeconds / 3_600
    val minutes = (totalSeconds / 60) % 60
    val seconds = totalSeconds % 60
    return if (hours > 0) "%d:%02d:%02d".fmt(hours, minutes, seconds) else "%d:%02d".fmt(minutes, seconds)
}
