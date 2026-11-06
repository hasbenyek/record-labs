package com.recordlabs.desktop

import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.geometry.Offset

fun Modifier.verticalToHorizontalScroll(): Modifier = this.pointerInput(Unit) {
    awaitPointerEventScope {
        while (true) {
            val event = awaitPointerEvent(PointerEventPass.Initial)
            event.changes.forEach { change ->
                val delta = change.scrollDelta
                if (delta.y != 0f && delta.x == 0f) {
                    val field = PointerInputChange::class.java.getDeclaredField("scrollDelta")
                    field.isAccessible = true
                    // Map Y scroll to X scroll
                    field.set(change, Offset(delta.y, 0f))
                }
            }
        }
    }
}
