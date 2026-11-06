package com.recordlabs.desktop

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import kotlinx.coroutines.flow.first

/**
 * Controls that hide after a while without input (the landscape lyrics page's corner
 * buttons) and come back on any touch, click, mouse movement or scroll.
 *
 * Input only records a time (no state change while the controls are showing), so
 * moving the mouse over the page does not redraw it; [visible] changes only when the
 * controls hide or come back.
 */
internal class IdleVisibility {
    var visible by mutableStateOf(true)
        internal set
    internal val lastInput = longArrayOf(System.currentTimeMillis())

    fun bump() {
        lastInput[0] = System.currentTimeMillis()
        if (!visible) visible = true
    }
}

/** [keep]: menus or panels are open, so the controls stay. */
@Composable
internal fun rememberIdleVisibility(keep: Boolean, timeoutMs: Long = 3_000L): IdleVisibility {
    val idle = remember { IdleVisibility() }
    LaunchedEffect(idle, keep, timeoutMs) {
        idle.bump()
        if (keep) return@LaunchedEffect
        while (true) {
            val left = timeoutMs - (System.currentTimeMillis() - idle.lastInput[0])
            if (left > 0L) {
                kotlinx.coroutines.delay(left)
            } else {
                idle.visible = false
                snapshotFlow { idle.visible }.first { it }
            }
        }
    }
    return idle
}

/** Any touch, click, mouse movement or scroll inside brings the controls back. */
internal fun Modifier.reportsActivity(idle: IdleVisibility): Modifier = pointerInput(idle) {
    awaitPointerEventScope {
        while (true) {
            val event = awaitPointerEvent(PointerEventPass.Initial)
            if (event.type != PointerEventType.Exit) idle.bump()
        }
    }
}

/**
 * Android: the hardware keyboard's \ key (the desktop shortcut) asks for the header on
 * the player pages; MainActivity reports it here, Main.kt shows the header.
 */
internal object PlayerHeaderRequest {
    var count by mutableIntStateOf(0)
        private set

    fun request() { count++ }
}
