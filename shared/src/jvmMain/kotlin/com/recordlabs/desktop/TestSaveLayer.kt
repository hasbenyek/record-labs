
package com.recordlabs.desktop
import org.jetbrains.skia.*
fun testSaveLayer() {
    val blurFilter = ImageFilter.makeBlur(30f, 30f, FilterTileMode.CLAMP)
    val rec = Canvas.SaveLayerRec(backdrop = blurFilter)
}
