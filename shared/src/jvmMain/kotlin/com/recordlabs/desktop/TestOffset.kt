package com.recordlabs.desktop

import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.geometry.Offset
import java.lang.reflect.Field

fun main() {
    val change = PointerInputChange(
        id = PointerId(0),
        uptimeMillis = 0,
        position = Offset.Zero,
        pressed = true,
        previousUptimeMillis = 0,
        previousPosition = Offset.Zero,
        previousPressed = true,
        isInitiallyConsumed = false,
        type = PointerType.Mouse,
        scrollDelta = Offset(0f, 1f)
    )
    
    val field = PointerInputChange::class.java.getDeclaredField("scrollDelta")
    field.isAccessible = true
    
    val x = 1f
    val y = 1f
    val v1 = java.lang.Float.floatToRawIntBits(x).toLong()
    val v2 = java.lang.Float.floatToRawIntBits(y).toLong()
    val packed = (v1 shl 32) or (v2 and 0xFFFFFFFFL)
    
    field.setLong(change, packed)
    
    println("New scrollDelta: " + change.scrollDelta)
}
