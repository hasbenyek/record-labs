package com.recordlabs.desktop

import com.recordlabs.desktop.platform.*

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

/**
 * The mini player (and the portrait dock's song bar) can be dragged up, with a finger
 * or by holding the mouse button, to open the music player: it follows the pointer a
 * little (with resistance), and opens once it is pulled far or flicked up; otherwise it
 * springs back. A plain click still opens it, and its buttons keep working.
 */
internal fun Modifier.swipeUpToOpen(onOpen: () -> Unit): Modifier = composed {
    val latestOpen by rememberUpdatedState(onOpen)
    val offset = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    // Pointer input outside the moving layer: the drag is measured where the bar rests.
    this
        .pointerInput(Unit) {
            val openDistance = 44.dp.toPx()
            val flickSpeed = 700.dp.toPx()
            val maxLift = 36.dp.toPx()
            var pulled = 0f
            val velocity = VelocityTracker()
            fun settle(open: Boolean) {
                scope.launch {
                    if (open) latestOpen()
                    offset.animateTo(0f, spring(dampingRatio = 0.7f, stiffness = 500f))
                }
            }
            detectVerticalDragGestures(
                onDragStart = { pulled = 0f; velocity.resetTracking() },
                onDragEnd = {
                    val speed = velocity.calculateVelocity().y
                    settle(-pulled > openDistance || (speed < -flickSpeed && pulled < 0f))
                },
                onDragCancel = { settle(false) },
                onVerticalDrag = { change, amount ->
                    change.consume()
                    velocity.addPosition(change.uptimeMillis, change.position)
                    pulled += amount
                    // Up follows with resistance (at most maxLift); down barely moves.
                    val lift = if (pulled < 0f) -maxLift * (1f - 1f / (1f + -pulled / (maxLift * 2.5f))) else pulled * 0.12f
                    scope.launch { offset.snapTo(lift) }
                },
            )
        }
        .graphicsLayer { translationY = offset.value }
}
