package com.recordlabs.desktop.data

import java.awt.RenderingHints
import java.awt.image.BufferedImage
import javax.imageio.ImageIO

internal actual fun decodeRgbThumbnail(bytes: ByteArray, size: Int): IntArray? {
    val source = ImageIO.read(bytes.inputStream()) ?: return null
    val resized = BufferedImage(size, size, BufferedImage.TYPE_INT_RGB)
    val g = resized.createGraphics()
    g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
    g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
    g.drawImage(source, 0, 0, size, size, null)
    g.dispose()
    return IntArray(size * size) { i -> resized.getRGB(i % size, i / size) }
}
