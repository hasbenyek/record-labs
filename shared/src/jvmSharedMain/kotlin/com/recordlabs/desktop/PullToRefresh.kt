package com.recordlabs.desktop

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.CircularProgressIndicator
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/** How far the page has to be pulled (past the top) to refresh. */
private val PullThreshold = 84.dp
private val PullMax = 140.dp
private val BubbleSize = 40.dp

/**
 * Pull to refresh like the YouTube Music app: at the top of the page, pulling
 * down (a finger, a touchpad, or a click-and-drag with the mouse) brings a
 * round bubble down from the top; let go past the line and the page reloads
 * while the bubble spins, then it slides back up.
 *
 * [atTop] says whether the page's list is scrolled all the way up.
 */
@Composable
internal fun PullToRefreshBox(
    refreshing: Boolean,
    onRefresh: () -> Unit,
    atTop: () -> Boolean,
    modifier: Modifier = Modifier,
    topInset: androidx.compose.ui.unit.Dp = 12.dp,
    content: @Composable BoxScope.() -> Unit,
) {
    val density = LocalDensity.current
    val thresholdPx = with(density) { PullThreshold.toPx() }
    val maxPx = with(density) { PullMax.toPx() }
    val pull = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    val latestRefresh by rememberUpdatedState(onRefresh)
    val latestAtTop by rememberUpdatedState(atTop)
    val latestRefreshing by rememberUpdatedState(refreshing)

    fun release() {
        val pulledFar = pull.value >= thresholdPx
        if (pulledFar && !latestRefreshing) latestRefresh()
        scope.launch { pull.animateTo(0f, tween(260)) }
    }

    fun dragBy(dy: Float) {
        // Harder to pull the further it goes (rubber band).
        val resistance = 0.5f * (1f - (pull.value / maxPx).coerceIn(0f, 0.9f))
        val next = (pull.value + dy * if (dy > 0) resistance else 1f).coerceIn(0f, maxPx)
        scope.launch { pull.snapTo(next) }
    }

    // Touch and touchpad: what the list could not scroll past its top.
    val connection = remember {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                // Scrolling back up first takes the pull back.
                if (pull.value > 0f && available.y < 0f) {
                    val used = maxOf(available.y, -pull.value)
                    dragBy(used)
                    return Offset(0f, used)
                }
                return Offset.Zero
            }

            override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
                if (source == NestedScrollSource.UserInput && available.y > 0f && latestAtTop()) {
                    dragBy(available.y)
                    return Offset(0f, available.y)
                }
                return Offset.Zero
            }

            override suspend fun onPreFling(available: Velocity): Velocity {
                if (pull.value > 0f) {
                    release()
                    return available
                }
                return Velocity.Zero
            }
        }
    }

    Box(
        modifier
            .nestedScroll(connection)
            // Mouse: click and drag down at the top of the page (the list itself
            // does not follow a mouse drag).
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                    if (down.type != PointerType.Mouse || !latestAtTop()) return@awaitEachGesture
                    var total = 0f
                    var pulling = false
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        if (!change.pressed) break
                        val dy = change.position.y - change.previousPosition.y
                        total += dy
                        if (!pulling && total > viewConfiguration.touchSlop) pulling = true
                        if (!pulling && total < -viewConfiguration.touchSlop) return@awaitEachGesture
                        if (pulling) {
                            dragBy(dy)
                            change.consume()
                        }
                    }
                    if (pulling) release()
                }
            },
    ) {
        content()
        // The bubble: follows the pull, then waits at the line while refreshing.
        val restingPx = if (refreshing) thresholdPx else 0f
        val shownPx = maxOf(pull.value, restingPx)
        val bubblePx = with(density) { BubbleSize.toPx() }
        val progress = (pull.value / thresholdPx).coerceIn(0f, 1f)
        if (shownPx > 0.5f) {
            Surface(
                shape = CircleShape,
                color = Color(0xFF2A2C31),
                elevation = 6.dp,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = topInset)
                    .offset { IntOffset(0, (shownPx - bubblePx).roundToInt()) }
                    .size(BubbleSize)
                    .graphicsLayer { alpha = if (refreshing) 1f else (shownPx / (thresholdPx * 0.6f)).coerceIn(0f, 1f) },
            ) {
                Box(contentAlignment = Alignment.Center) {
                    if (refreshing) {
                        CircularProgressIndicator(
                            color = MaterialTheme.colors.primary,
                            strokeWidth = 2.5.dp,
                            modifier = Modifier.size(22.dp),
                        )
                    } else {
                        CircularProgressIndicator(
                            progress = progress,
                            color = MaterialTheme.colors.primary.copy(alpha = 0.4f + 0.6f * progress),
                            strokeWidth = 2.5.dp,
                            modifier = Modifier.size(22.dp).graphicsLayer { rotationZ = progress * 270f },
                        )
                    }
                }
            }
        }
    }
}
