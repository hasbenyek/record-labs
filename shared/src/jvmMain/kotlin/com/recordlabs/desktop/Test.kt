package com.recordlabs.desktop

import org.jetbrains.skia.Bitmap
import com.sun.jna.Native
import java.nio.ByteBuffer

fun checkSkia() {
    val bitmap = Bitmap()
    val methods = bitmap::class.java.declaredMethods
    val hasPtrInstall = methods.any { it.name == "installPixels" && it.parameterTypes.contains(Long::class.java) }
    println("hasPtrInstall: " + hasPtrInstall)
}
