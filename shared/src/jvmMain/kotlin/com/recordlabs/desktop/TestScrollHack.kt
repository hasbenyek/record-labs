package com.recordlabs.desktop

import androidx.compose.ui.input.pointer.PointerType
import java.lang.reflect.Modifier

fun main() {
    try {
        val clazz = Class.forName("androidx.compose.foundation.gestures.ScrollableKt")
        val field = clazz.getDeclaredField("CanDragCalculation")
        field.isAccessible = true
        
        // Let's remove final modifier if necessary (Java 8-11 trick, might not work on 17)
        try {
            val modifiersField = java.lang.reflect.Field::class.java.getDeclaredField("modifiers")
            modifiersField.isAccessible = true
            modifiersField.setInt(field, field.modifiers and Modifier.FINAL.inv())
        } catch (e: Exception) {
            // Ignore if modifiers field is not accessible
        }
        
        val func: (PointerType) -> Boolean = { true }
        field.set(null, func)
        println("Successfully overwrote CanDragCalculation!")
    } catch (e: Exception) {
        println("Error: " + e)
        e.printStackTrace()
    }
}
