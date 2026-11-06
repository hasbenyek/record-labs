package com.recordlabs.desktop

import androidx.compose.ui.input.pointer.PointerIcon
import java.awt.Point
import java.awt.Toolkit
import java.awt.image.BufferedImage

/** A fully transparent mouse pointer, used while the full-screen video's controls are hidden. */
internal val HiddenPointerIcon: PointerIcon by lazy {
    val cursor = runCatching {
        Toolkit.getDefaultToolkit().createCustomCursor(BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB), Point(0, 0), "record-labs-hidden")
    }.getOrNull()
    if (cursor != null) PointerIcon(cursor) else PointerIcon.Default
}

