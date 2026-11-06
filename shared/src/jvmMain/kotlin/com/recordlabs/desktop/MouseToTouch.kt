package com.recordlabs.desktop

import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.*

val spoofedPointers = mutableSetOf<Long>()

fun Modifier.mouseToTouchDrag(): Modifier = this.pointerInput(Unit) {
    awaitPointerEventScope {
        while (true) {
            val event = awaitPointerEvent(PointerEventPass.Initial)
            event.changes.forEach { change ->
                if (change.type == PointerType.Mouse && change.pressed) {
                    spoofedPointers.add(change.id.value)
                }
                
                if (spoofedPointers.contains(change.id.value)) {
                    val field = PointerInputChange::class.java.getDeclaredField("type")
                    field.isAccessible = true
                    field.setInt(change, 1 /* PointerType.Touch */)
                    
                    if (!change.pressed) {
                        spoofedPointers.remove(change.id.value)
                    }
                }

                if (event.type == PointerEventType.Scroll) {
                    val delta = change.scrollDelta
                    if (delta.y != 0f && delta.x == 0f) {
                        val field = PointerInputChange::class.java.getDeclaredField("scrollDelta")
                        field.isAccessible = true
                        
                        val v1 = java.lang.Float.floatToRawIntBits(delta.y).toLong()
                        val v2 = java.lang.Float.floatToRawIntBits(delta.y).toLong()
                        val packed = (v1 shl 32) or (v2 and 0xFFFFFFFFL)
                        
                        field.setLong(change, packed)
                    }
                }
            }
        }
    }
}
