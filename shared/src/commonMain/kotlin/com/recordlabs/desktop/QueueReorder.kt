package com.recordlabs.desktop

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Moving songs in a queue list by their handle, as in Apple Music and Spotify.
 *
 * The gesture is the list's own (see [queueReorder]), not the row's: a row dragged far
 * up or down scrolled out of the list, was taken out of it, and its gesture with it (the
 * song stopped moving, or jumped back). The song being moved is drawn over the list
 * ([QueueReorderGhost]) where the finger or mouse holds it, and the list scrolls along
 * while it is held near the top or bottom (faster the closer), not only while it moves.
 */
internal class QueueReorder {
    /** A handle is held: the list does not scroll by itself under it. */
    var pressed by mutableStateOf(false)
    /** The song being moved (its list key), once the drag has started. */
    var draggedKey by mutableStateOf<Any?>(null)
    /** 0: not decided yet, 1: up or down (moving), 2: sideways (removing). */
    var axis by mutableStateOf(0)
    /** The moved row's top in the list (px) and its size. */
    var ghostTop by mutableStateOf(0f)
    var ghostSize by mutableStateOf(IntSize.Zero)
    /** Sideways distance (px), for removing. */
    var dragX by mutableStateOf(0f)

    val moving: Boolean get() = draggedKey != null
}

/**
 * The drag on [state]'s list: a press on a row's handle (the last [handleWidth] of the row,
 * which ends [handleEndInset] before the list's right edge) of a row [isMovable] moves it.
 * [onReorder] moves the song in the shown list while dragging, [onDrop] keeps the new
 * place, [onRemove] removes it (swiped sideways far enough), [onCancel] puts it back.
 */
internal fun Modifier.queueReorder(
    state: LazyListState,
    reorder: QueueReorder,
    handleWidth: Dp,
    handleEndInset: Dp = 0.dp,
    removeDistance: Dp = 96.dp,
    isMovable: (Any) -> Boolean,
    onReorder: (moved: Any, target: Any) -> Unit,
    onDrop: (Any) -> Unit,
    onRemove: (Any) -> Unit,
    onCancel: () -> Unit,
): Modifier = composed {
    val scope = rememberCoroutineScope()
    pointerInput(state, reorder) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            val rowRight = size.width - handleEndInset.toPx()
            if (down.position.x < rowRight - handleWidth.toPx() || down.position.x > rowRight) return@awaitEachGesture
            val row = state.layoutInfo.visibleItemsInfo.firstOrNull {
                down.position.y >= it.offset && down.position.y < it.offset + it.size
            } ?: return@awaitEachGesture
            val key = row.key
            if (!isMovable(key)) return@awaitEachGesture
            reorder.pressed = true
            // Where in the row it was taken: the row stays at that spot under the pointer.
            val grab = down.position.y - row.offset
            val viewportHeight = size.height.toFloat()
            val slop = viewConfiguration.touchSlop
            var pendingX = 0f
            var pendingY = 0f
            var released = false
            var autoScroll: Job? = null

            // The row under the moved row's middle takes its place.
            fun retarget() {
                val middle = reorder.ghostTop + reorder.ghostSize.height / 2f
                val target = state.layoutInfo.visibleItemsInfo.firstOrNull {
                    it.key != key && isMovable(it.key) && middle >= it.offset && middle < it.offset + it.size
                } ?: return
                onReorder(key, target.key)
            }

            try {
                while (true) {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    val change = event.changes.firstOrNull { it.id == down.id } ?: break
                    if (!change.pressed) {
                        released = true
                        break
                    }
                    val delta = change.positionChange()
                    if (reorder.axis == 0) {
                        pendingX += delta.x
                        pendingY += delta.y
                        if (abs(pendingX) + abs(pendingY) < slop) continue
                        change.consume()
                        reorder.axis = if (abs(pendingX) > abs(pendingY)) 2 else 1
                        reorder.ghostSize = IntSize(rowRight.roundToInt(), row.size)
                        reorder.ghostTop = change.position.y - grab
                        reorder.dragX = if (reorder.axis == 2) pendingX else 0f
                        reorder.draggedKey = key
                        if (reorder.axis == 1) {
                            // Held near the top or bottom: the list keeps scrolling (faster the
                            // closer), and the songs passing under it make room.
                            autoScroll = scope.launch {
                                val zone = minOf(viewportHeight * 0.2f, 96.dp.toPx())
                                val fastest = 22.dp.toPx()
                                while (isActive) {
                                    withFrameNanos { }
                                    val middle = reorder.ghostTop + reorder.ghostSize.height / 2f
                                    val speed = when {
                                        middle < zone -> -fastest * ((zone - middle) / zone).coerceIn(0f, 1f)
                                        middle > viewportHeight - zone -> fastest * ((middle - (viewportHeight - zone)) / zone).coerceIn(0f, 1f)
                                        else -> 0f
                                    }
                                    if (speed != 0f) {
                                        val scrolled = state.scrollBy(speed)
                                        if (scrolled != 0f) retarget()
                                    }
                                }
                            }
                        }
                        continue
                    }
                    change.consume()
                    if (reorder.axis == 2) {
                        reorder.dragX += delta.x
                        continue
                    }
                    // Kept within the list, so it can always be seen.
                    reorder.ghostTop = (change.position.y - grab)
                        .coerceIn(-reorder.ghostSize.height / 2f, viewportHeight - reorder.ghostSize.height / 2f)
                    retarget()
                }
            } finally {
                autoScroll?.cancel()
                val axis = reorder.axis
                when {
                    released && axis == 1 -> onDrop(key)
                    released && axis == 2 && abs(reorder.dragX) >= removeDistance.toPx() -> onRemove(key)
                    axis != 0 -> onCancel()
                }
                reorder.draggedKey = null
                reorder.axis = 0
                reorder.dragX = 0f
                reorder.pressed = false
            }
        }
    }
}

/** The moved row, drawn over the list where it is held (put it in the list's own Box). */
@Composable
internal fun QueueReorderGhost(reorder: QueueReorder, content: @Composable (key: Any) -> Unit) {
    val key = reorder.draggedKey ?: return
    val size = reorder.ghostSize
    if (size.width <= 0 || size.height <= 0) return
    val density = LocalDensity.current
    Box(
        Modifier
            .zIndex(10f)
            .offset { IntOffset(reorder.dragX.roundToInt(), reorder.ghostTop.roundToInt()) }
            .size(with(density) { size.width.toDp() }, with(density) { size.height.toDp() }),
    ) { content(key) }
}
