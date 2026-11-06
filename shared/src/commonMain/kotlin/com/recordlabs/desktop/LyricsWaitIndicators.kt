package com.recordlabs.desktop

import com.recordlabs.desktop.platform.*

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material.Icon
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/*
 * What the lyrics show while waiting for the next line (an intro or a long
 * instrumental part). Settings > Appearance > Lyrics picks one:
 * "note" (default): a music note that fills with glowing white from the bottom
 * up, the lyrics come when it is full; "ring": the progress ring.
 */

/** Top and height of the note glyph inside the 24-unit Material icon. */
private const val NOTE_TOP = 3f / 24f
private const val NOTE_HEIGHT = 18f / 24f

/**
 * Shows only the part of the glyph under a moving water line ([progress] 0..1, from
 * the bottom up). The line is a gentle wave that drifts sideways ([phase], radians);
 * being a smooth, anti-aliased curve it glides between pixel rows instead of
 * stepping. [lift] raises the line (a fraction of the glyph) and [amplitude] scales
 * the wave: the back wave uses both.
 */
private fun Modifier.waterFill(progress: Float, phase: Float, amplitude: Float = 1f, lift: Float = 0f): Modifier =
    graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }.drawWithContent {
        drawContent()
        val p = progress.coerceIn(0f, 1f)
        // Calm when empty and when full, the most movement half way.
        val calm = (4f * p * (1f - p)).coerceIn(0f, 1f)
        val amp = WAVE_AMPLITUDE.toPx() * amplitude * (0.35f + 0.65f * calm)
        val glyphTop = size.height * NOTE_TOP
        val glyphBottom = size.height * (NOTE_TOP + NOTE_HEIGHT)
        // From fully under the glyph (0) to fully over it (1), wave crests included.
        val low = glyphBottom + amp
        val high = glyphTop - amp
        val level = low + (high - low) * p - size.height * NOTE_HEIGHT * lift * (1f - p)
        val wavelength = size.width * 0.62f
        val path = Path()
        path.moveTo(0f, 0f)
        val steps = 28
        for (i in 0..steps) {
            val x = size.width * i / steps
            val y = level + amp * sin(2f * PI.toFloat() * x / wavelength + phase)
            path.lineTo(x, y)
        }
        path.lineTo(size.width, 0f)
        path.close()
        // Everything above the water line is taken away.
        drawPath(path, Color.Black, blendMode = BlendMode.DstOut)
    }

private val WAVE_AMPLITUDE = 1.5.dp
private const val NOTE_MIN_SCALE = 0.8f
private const val NOTE_BREATH_SCALE = 0.9f
private const val WAVE_PERIOD_MS = 1_900f
private const val BREATH_PERIOD_MS = 2_200L
private const val PREVIEW_MS = 4_400L

private fun smoothStep(x: Float): Float {
    val t = x.coerceIn(0f, 1f)
    return t * t * (3f - 2f * t)
}

@Composable
internal fun LyricsWaitNote(rawProgress: Float, durationMs: Long, boxSize: Dp = 50.dp) {
    // The playback clock may correct itself by a few hundred ms; a soft spring on top
    // keeps the rising white gliding instead of nudging.
    val progress by animateFloatAsState(
        targetValue = rawProgress.coerceIn(0f, 1f),
        animationSpec = spring(dampingRatio = 1f, stiffness = Spring.StiffnessLow),
        label = "lyrics-note-progress",
    )
    val note = rememberVectorPainter(Icons.Default.MusicNote)
    // Everything moves with the song itself, not with a clock of its own: paused,
    // stopped or still loading, the note rests (no breathing, no drifting water).
    val elapsedMs = durationMs.coerceAtLeast(1L) * progress
    // The water drifts like a gentle swell (one wave every 1.9 s of music).
    val phase = (2f * PI.toFloat() * elapsedMs / WAVE_PERIOD_MS)
    // Breathes like Apple Music's waiting dots: a whole number of breaths fits this
    // pause exactly (about one every 2.2 s), so the last one flows into the final
    // swell right as the next line starts, whatever the length of the pause.
    val breaths = (durationMs / BREATH_PERIOD_MS.toDouble()).roundToInt().coerceAtLeast(1)
    val breath = 0.5f - 0.5f * cos(2f * PI.toFloat() * breaths * progress)
    val glow = .6f + .4f * breath
    val nearlyFull = smoothStep((progress - .8f) / .2f)
    // The glow fades in with the first bit of white rather than switching on.
    val glowIn = smoothStep(progress / .12f)
    Box(
        Modifier.size(boxSize).graphicsLayer {
            // 0.8 of the box is the note's earlier size (40 of 50 dp); full size at the end.
            val resting = NOTE_MIN_SCALE + (NOTE_BREATH_SCALE - NOTE_MIN_SCALE) * breath
            val scale = resting + (1f - resting) * nearlyFull
            scaleX = scale
            scaleY = scale
            // Grows from the note's head, so it stays at the start of the line.
            transformOrigin = TransformOrigin(0.3f, 0.65f)
        },
        contentAlignment = Alignment.Center,
    ) {
        // The empty note.
        Icon(note, null, tint = Color.White.copy(alpha = .22f), modifier = Modifier.fillMaxSize())
        // A paler wave just behind the front one, drifting the other way: water, not a bar.
        Icon(
            note,
            null,
            tint = Color.White.copy(alpha = .30f * glowIn),
            modifier = Modifier.fillMaxSize().waterFill(progress, -phase * 1f + 2.2f, amplitude = 1.25f, lift = .07f),
        )
        // The glow of the white rising inside it: soft, larger copies of the filled part
        // (cheap on every GPU, unlike a blur redrawn every frame).
        for ((spread, strength) in listOf(1.26f to .12f, 1.16f to .2f, 1.08f to .28f)) {
            Icon(
                note,
                null,
                tint = Color.White,
                modifier = Modifier.fillMaxSize()
                    .graphicsLayer {
                        alpha = strength * glow * glowIn * (.75f + .25f * nearlyFull)
                        scaleX = spread
                        scaleY = spread
                    }
                    .waterFill(progress, phase),
            )
        }
        // The filled part, crisp.
        Icon(note, null, tint = Color.White, modifier = Modifier.fillMaxSize().waterFill(progress, phase))
    }
}

/** The progress ring (the earlier look). */
@Composable
internal fun LyricsWaitRing(rawProgress: Float, boxSize: Dp = 36.dp) {
    val progress by animateFloatAsState(
        targetValue = rawProgress.coerceIn(0f, 1f),
        animationSpec = spring(dampingRatio = 1f, stiffness = Spring.StiffnessLow),
        label = "lyrics-ring-progress",
    )
    Canvas(Modifier.size(boxSize)) {
        val stroke = Stroke(width = 4.dp.toPx(), cap = StrokeCap.Round)
        drawArc(Color.White.copy(alpha = .20f), -90f, 360f, false, style = stroke)
        // Position is already extrapolated at display refresh cadence. Drawing
        // it directly avoids continuously restarting a tween and gives the
        // progressive indicator an even, frame-perfect movement.
        val sweepAngle = progress * 360f
        drawArc(Color.White.copy(alpha = .18f), -90f, sweepAngle, false, style = Stroke(width = 8.dp.toPx(), cap = StrokeCap.Round))
        drawArc(Color.White.copy(alpha = .90f), -90f, sweepAngle, false, style = stroke)
        if (progress in 0.001f..0.999f) {
            drawArc(Color.White.copy(alpha = .12f), -90f + sweepAngle - 18f, 18f, false, style = Stroke(width = 10.dp.toPx(), cap = StrokeCap.Round))
            drawArc(Color.White.copy(alpha = .24f), -90f + sweepAngle - 11f, 11f, false, style = Stroke(width = 8.dp.toPx(), cap = StrokeCap.Round))
            drawArc(Color.White.copy(alpha = .42f), -90f + sweepAngle - 5f, 5f, false, style = Stroke(width = 6.dp.toPx(), cap = StrokeCap.Round))
        }
    }
}

/** The indicator chosen in Settings. */
@Composable
internal fun LyricsWaitIndicator(progress: Float, durationMs: Long) {
    if (com.recordlabs.desktop.data.AppSettings.lyricsWaitStyle.value == "ring") LyricsWaitRing(progress) else LyricsWaitNote(progress, durationMs)
}

/** A looping preview of one indicator for its Settings choice. */
@Composable
internal fun LyricsWaitPreview(style: String) {
    val loop = rememberInfiniteTransition(label = "lyrics-wait-preview")
    val progress by loop.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(PREVIEW_MS.toInt(), easing = LinearEasing), RepeatMode.Restart),
        label = "lyrics-wait-preview-progress",
    )
    if (style == "ring") LyricsWaitRing(progress) else LyricsWaitNote(progress, PREVIEW_MS)
}

/**
 * Holds the lyrics' word highlight after a seek (tapping a lyric line) until the
 * audio really plays from there. Right after a seek the player is still loading,
 * but it may already report "playing", so the highlight ran ahead of the sound.
 * The hold ends once the reported position has reached the seek point and then
 * moves on while playing (or after a few seconds, whatever happens).
 */
internal object LyricsClockHold {
    var holding by androidx.compose.runtime.mutableStateOf(false)
        private set

    /** The song is still loading (set by the window): the waiting note rests meanwhile. */
    var loading by androidx.compose.runtime.mutableStateOf(false)
    private var target = 0L
    private var seenTarget = false
    private var since = 0L

    fun startAfterSeek(positionMs: Long) {
        target = positionMs
        seenTarget = false
        since = currentTimeMillis()
        holding = true
    }

    /** Every playback sample of the current song. */
    fun onPlaybackSample(positionMs: Long, isPlaying: Boolean) {
        if (!holding) return
        if (currentTimeMillis() - since > 6_000L) {
            holding = false
            return
        }
        // A sample from before the seek (e.g. further on, for a seek back) does not count.
        if (!seenTarget && positionMs in (target - 400L)..(target + 400L)) seenTarget = true
        if (seenTarget && isPlaying && positionMs >= target + 150L) holding = false
    }
}
