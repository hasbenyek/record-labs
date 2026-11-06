package com.recordlabs.desktop

import androidx.compose.ui.input.pointer.*

fun test(change: PointerInputChange) {
    val field = PointerInputChange::class.java.getDeclaredField("type")
    field.isAccessible = true
    field.set(change, PointerType.Touch)
}
