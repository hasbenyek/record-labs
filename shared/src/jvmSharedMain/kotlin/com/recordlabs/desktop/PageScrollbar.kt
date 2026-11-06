package com.recordlabs.desktop

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.gestures.ScrollableState
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isPrimaryPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp

/**
 * Where a scrollable page is: [fraction] of the way down (0..1), the share of
 * the content that is visible, and how many pixels the content can scroll.
 */
internal data class ScrollbarMetrics(val fraction: Float, val visibleFraction: Float, val scrollRangePx: Float)

/** Scroll bar on the right edge of a scrollable page (lists, grids, scrolling columns). */
internal fun Modifier.pageScrollbar(state: LazyListState): Modifier = pageScrollbar(
    state,
    // Long lists move by item position, so a big jump never composes every row on the way.
    jumpTo = { fraction ->
        val info = state.layoutInfo
        val visible = info.visibleItemsInfo
        if (visible.isNotEmpty() && info.totalItemsCount > 0) {
            val viewport = (info.viewportEndOffset - info.viewportStartOffset).toFloat()
            val spacing = info.mainAxisItemSpacing.toFloat()
            val averageItem = visible.sumOf { it.size }.toFloat() / visible.size + spacing
            val content = averageItem * info.totalItemsCount - spacing + info.beforeContentPadding + info.afterContentPadding
            val position = (fraction.coerceIn(0f, 1f) * (content - viewport)).coerceAtLeast(0f)
            val index = (position / averageItem).toInt().coerceIn(0, info.totalItemsCount - 1)
            state.scrollToItem(index, (position - index * averageItem).toInt().coerceAtLeast(0))
        }
    },
) {
    val info = state.layoutInfo
    val visible = info.visibleItemsInfo
    val total = info.totalItemsCount
    if (visible.isEmpty() || total == 0) return@pageScrollbar null
    val viewport = (info.viewportEndOffset - info.viewportStartOffset).toFloat()
    val spacing = info.mainAxisItemSpacing.toFloat()
    val averageItem = visible.sumOf { it.size }.toFloat() / visible.size + spacing
    val content = averageItem * total - spacing + info.beforeContentPadding + info.afterContentPadding
    if (viewport <= 0f || content <= viewport + 1f || (!state.canScrollBackward && !state.canScrollForward)) return@pageScrollbar null
    val scrolled = state.firstVisibleItemIndex * averageItem + state.firstVisibleItemScrollOffset
    val fraction = when {
        !state.canScrollForward -> 1f
        !state.canScrollBackward -> 0f
        else -> (scrolled / (content - viewport)).coerceIn(0f, 1f)
    }
    ScrollbarMetrics(fraction, (viewport / content).coerceIn(0f, 1f), content - viewport)
}

internal fun Modifier.pageScrollbar(state: LazyGridState): Modifier = pageScrollbar(
    state,
    jumpTo = { fraction ->
        val info = state.layoutInfo
        val total = info.totalItemsCount
        if (total > 0) {
            val movable = (total - info.visibleItemsInfo.size).coerceAtLeast(0)
            state.scrollToItem((fraction.coerceIn(0f, 1f) * movable).toInt().coerceIn(0, total - 1))
        }
    },
) {
    val info = state.layoutInfo
    val visible = info.visibleItemsInfo
    val total = info.totalItemsCount
    if (visible.isEmpty() || total == 0) return@pageScrollbar null
    if (!state.canScrollBackward && !state.canScrollForward) return@pageScrollbar null
    val viewport = (info.viewportEndOffset - info.viewportStartOffset).toFloat()
    val visibleFraction = (visible.size.toFloat() / total).coerceIn(0.05f, 1f)
    val fraction = when {
        !state.canScrollForward -> 1f
        !state.canScrollBackward -> 0f
        else -> (state.firstVisibleItemIndex.toFloat() / (total - visible.size).coerceAtLeast(1)).coerceIn(0f, 1f)
    }
    ScrollbarMetrics(fraction, visibleFraction, viewport / visibleFraction - viewport)
}

internal fun Modifier.pageScrollbar(state: ScrollState): Modifier = pageScrollbar(state) {
    val range = state.maxValue.toFloat()
    val viewport = state.viewportSize.toFloat()
    if (range <= 0f || viewport <= 0f) return@pageScrollbar null
    ScrollbarMetrics(state.value / range, viewport / (viewport + range), range)
}

private fun Modifier.pageScrollbar(
    state: ScrollableState,
    jumpTo: (suspend (Float) -> Unit)? = null,
    metrics: () -> ScrollbarMetrics?,
): Modifier = composed {
    val scope = rememberCoroutineScope()
    var hovered by remember { mutableStateOf(false) }
    var dragging by remember { mutableStateOf(false) }
    this
        .drawWithContent {
            drawContent()
            val current = metrics() ?: return@drawWithContent
            val active = hovered || dragging
            val thickness = (if (active) SCROLLBAR_ACTIVE_THICKNESS else SCROLLBAR_THICKNESS).toPx()
            val margin = SCROLLBAR_MARGIN.toPx()
            val trackTop = margin
            val trackHeight = size.height - margin * 2
            if (trackHeight <= 0f) return@drawWithContent
            val thumbHeight = (trackHeight * current.visibleFraction).coerceIn(SCROLLBAR_MIN_THUMB.toPx().coerceAtMost(trackHeight), trackHeight)
            val thumbTop = trackTop + (trackHeight - thumbHeight) * current.fraction
            drawRoundRect(
                color = Color.White.copy(alpha = if (dragging) .55f else if (hovered) .42f else .20f),
                topLeft = Offset(size.width - margin - thickness, thumbTop),
                size = Size(thickness, thumbHeight),
                cornerRadius = CornerRadius(thickness / 2f),
            )
        }
        .pointerInput(state) {
            awaitPointerEventScope {
                while (true) {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    val change = event.changes.firstOrNull() ?: continue
                    val inStrip = change.position.x >= size.width - SCROLLBAR_HIT_WIDTH.toPx() && change.position.x <= size.width
                    when (event.type) {
                        PointerEventType.Exit -> hovered = false
                        PointerEventType.Enter, PointerEventType.Move -> hovered = inStrip && metrics() != null
                    }
                    if (event.type != PointerEventType.Press || !inStrip || !event.buttons.isPrimaryPressed) continue
                    val start = metrics() ?: continue
                    val margin = SCROLLBAR_MARGIN.toPx()
                    val trackHeight = size.height - margin * 2
                    val thumbHeight = (trackHeight * start.visibleFraction).coerceIn(SCROLLBAR_MIN_THUMB.toPx().coerceAtMost(trackHeight), trackHeight)
                    val freeTrack = (trackHeight - thumbHeight).coerceAtLeast(1f)
                    val thumbTop = margin + freeTrack * start.fraction
                    val slack = SCROLLBAR_GRAB_SLACK.toPx()
                    // Only the thumb is grabbed: presses elsewhere on the edge
                    // still reach the row or tile underneath.
                    if (change.position.y < thumbTop - slack || change.position.y > thumbTop + thumbHeight + slack) continue
                    change.consume()
                    dragging = true
                    val pressY = change.position.y
                    var lastY = pressY
                    while (true) {
                        val next = awaitPointerEvent(PointerEventPass.Initial)
                        val moved = next.changes.firstOrNull { it.id == change.id } ?: break
                        if (!moved.pressed) {
                            moved.consume()
                            break
                        }
                        val dy = moved.position.y - lastY
                        lastY = moved.position.y
                        moved.consume()
                        if (dy == 0f) continue
                        if (jumpTo != null) {
                            // Thumb follows the pointer: position by fraction.
                            val target = (start.fraction + (moved.position.y - pressY) / freeTrack).coerceIn(0f, 1f)
                            scope.launch { jumpTo(target) }
                        } else {
                            val now = metrics() ?: break
                            state.dispatchRawDelta(dy * now.scrollRangePx / freeTrack)
                        }
                    }
                    dragging = false
                    hovered = false
                }
            }
        }
}

private val SCROLLBAR_THICKNESS = 5.dp
private val SCROLLBAR_ACTIVE_THICKNESS = 8.dp
private val SCROLLBAR_MARGIN = 3.dp
private val SCROLLBAR_MIN_THUMB = 32.dp
/** Width of the strip at the right edge that grabs the scroll bar. */
private val SCROLLBAR_HIT_WIDTH = 12.dp
/** Extra height around the thumb that still grabs it. */
private val SCROLLBAR_GRAB_SLACK = 6.dp
