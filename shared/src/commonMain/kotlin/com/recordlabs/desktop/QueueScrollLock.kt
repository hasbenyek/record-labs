package com.recordlabs.desktop

import com.recordlabs.desktop.platform.*

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.pointerInput
import kotlin.math.abs

private class QueueScrollLockState {
    /** The user pushed past the lock; scrolling up is free until they come back down. */
    var released = false
    /** The list is resting on the lock item. */
    var atLock = false
    var lastTarget = -1
    var lastWheelNanos = 0L
    /** Upward wheel amount of the current (fresh) wheel/touchpad stream at the lock. */
    var freshPressure = 0f
    var freshStream = false
    /** Upward wheel amount since the lock was reached, whatever the pauses. */
    var totalPressure = 0f
    var atLockSinceNanos = 0L
    /** A finger is on the list, or just let go of it (its fling): touch scrolling is never held. */
    var touching = false
    var touchEndNanos = 0L
}

/**
 * Scrolling up (mouse wheel, touchpad) stops once the item at [lockIndex]
 * reaches the top of the list (the song playing now), so the songs that were
 * already played above it do not fly by. Scrolling up again after a short
 * pause, or pushing on for a long while, scrolls on past it.
 *
 * Programmatic scrolling (scrollToItem, the drag-to-reorder auto-scroll) and
 * scrolling down are not affected.
 */
internal fun Modifier.queueScrollLock(state: LazyListState, lockIndex: () -> Int): Modifier = composed {
    val lock = remember(state) { QueueScrollLockState() }
    val currentLockIndex by rememberUpdatedState(lockIndex)
    val connection = remember(state, lock) {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                val target = currentLockIndex()
                if (target != lock.lastTarget) {
                    lock.lastTarget = target
                    lock.released = false
                    lock.atLock = false
                }
                if (target < 0 || available.y == 0f) return Offset.Zero
                // A finger has no "push again" to get past the lock, so touch
                // scrolling (and its fling) moves freely.
                if (lock.touching || nanoTime() - lock.touchEndNanos < TOUCH_FLING_NANOS) return Offset.Zero
                val distance = distanceToLock(state, target)
                if (available.y < 0f) {
                    // Scrolling down: once the lock item is above the top
                    // again, the next way up stops at it again.
                    if (distance != null && distance > 0.5f) {
                        lock.released = false
                        lock.atLock = false
                    }
                    return Offset.Zero
                }
                if (lock.released || distance == null) return Offset.Zero
                return when {
                    distance <= 0.5f -> {
                        lock.markAtLock()
                        Offset(0f, available.y)
                    }
                    available.y > distance -> {
                        // Stop exactly at the lock item; the rest is swallowed.
                        lock.markAtLock()
                        Offset(0f, available.y - distance)
                    }
                    else -> Offset.Zero
                }
            }
        }
    }
    this
        .pointerInput(state, lock) {
            awaitPointerEventScope {
                while (true) {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    if (event.changes.any { it.type == PointerType.Touch }) {
                        if (event.changes.any { it.pressed }) {
                            lock.touching = true
                        } else if (lock.touching) {
                            lock.touching = false
                            lock.touchEndNanos = nanoTime()
                        }
                    }
                    if (event.type != PointerEventType.Scroll) continue
                    val dy = event.changes.firstOrNull()?.scrollDelta?.y ?: continue
                    if (dy == 0f) continue
                    val now = nanoTime()
                    val gapMs = (now - lock.lastWheelNanos) / 1_000_000L
                    lock.lastWheelNanos = now
                    if (dy > 0f || !lock.atLock || lock.released) continue
                    // Upward wheel while resting on the lock item.
                    if (gapMs > STREAM_GAP_MS) {
                        lock.freshStream = true
                        lock.freshPressure = 0f
                    }
                    lock.totalPressure += abs(dy)
                    if (lock.freshStream) lock.freshPressure += abs(dy)
                    val pushedLong = lock.totalPressure >= HARD_PUSH_AMOUNT &&
                        (now - lock.atLockSinceNanos) / 1_000_000L >= HARD_PUSH_MS
                    if (lock.freshPressure >= RELEASE_AMOUNT || pushedLong) {
                        lock.released = true
                        lock.atLock = false
                    }
                }
            }
        }
        .nestedScroll(connection)
}

private fun QueueScrollLockState.markAtLock() {
    if (atLock) return
    atLock = true
    atLockSinceNanos = nanoTime()
    // The stream that brought the list here does not count as a push past it.
    freshStream = false
    freshPressure = 0f
    totalPressure = 0f
}

/**
 * Pixels the list can still scroll up before the item at [target] sits at
 * the top: 0 when it is there already, null when the list is above it (the
 * item is lower down or out of view below: earlier songs are in view).
 */
private fun distanceToLock(state: LazyListState, target: Int): Float? {
    val info = state.layoutInfo
    val item = info.visibleItemsInfo.firstOrNull { it.index == target }
    if (item != null) {
        // Lower down than the top: the list is above the lock (earlier songs in view).
        if (item.offset - info.viewportStartOffset > 1) return null
        return (info.viewportStartOffset - item.offset).toFloat().coerceAtLeast(0f)
    }
    val first = state.firstVisibleItemIndex
    if (first < target) return null
    val visible = info.visibleItemsInfo
    if (visible.isEmpty()) return null
    val averageItem = visible.sumOf { it.size }.toFloat() / visible.size + info.mainAxisItemSpacing
    return state.firstVisibleItemScrollOffset + (first - target) * averageItem
}

/** A wheel/touchpad pause this long starts a new push. */
private const val STREAM_GAP_MS = 250L
private const val TOUCH_FLING_NANOS = 2_500_000_000L
/** Upward amount of a new push that scrolls past the lock (one wheel notch is 1). */
private const val RELEASE_AMOUNT = 0.75f
/** Continuous pushing on the lock (this much, for this long) lets go as well. */
private const val HARD_PUSH_AMOUNT = 12f
private const val HARD_PUSH_MS = 1_500L
