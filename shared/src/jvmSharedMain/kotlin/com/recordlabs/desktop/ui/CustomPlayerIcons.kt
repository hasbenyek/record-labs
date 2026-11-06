package com.recordlabs.desktop.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/**
 * Custom vector icons matching user specification (Gambar 1 to Gambar 5).
 */
object CustomPlayerIcons {

    /** Gambar 1: Quote speech bubble with "99" inside for Lyrics */
    val LyricsQuote: ImageVector = ImageVector.Builder(
        name = "CustomPlayerIcons.LyricsQuote",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    ).path(
        stroke = SolidColor(Color.White),
        strokeLineWidth = 2f,
        strokeLineCap = StrokeCap.Round,
        strokeLineJoin = StrokeJoin.Round,
    ) {
        // Speech bubble contour with bottom tail
        moveTo(4f, 4f)
        lineTo(20f, 4f)
        arcTo(2f, 2f, 0f, false, true, 22f, 6f)
        lineTo(22f, 15f)
        arcTo(2f, 2f, 0f, false, true, 20f, 17f)
        lineTo(9f, 17f)
        lineTo(5f, 21f)
        lineTo(5f, 17f)
        lineTo(4f, 17f)
        arcTo(2f, 2f, 0f, false, true, 2f, 15f)
        lineTo(2f, 6f)
        arcTo(2f, 2f, 0f, false, true, 4f, 4f)
        close()
    }.path(
        fill = SolidColor(Color.White),
    ) {
        // Left "9" quote mark
        moveTo(8.5f, 9f)
        arcTo(1.5f, 1.5f, 0f, true, true, 7f, 10.5f)
        arcTo(1.5f, 1.5f, 0f, false, true, 8.5f, 9f)
        moveTo(8.5f, 9f)
        curveTo(9.5f, 9f, 10f, 10f, 10f, 11f)
        curveTo(10f, 12.5f, 8.5f, 14f, 7.5f, 14.5f)

        // Right "9" quote mark
        moveTo(14.5f, 9f)
        arcTo(1.5f, 1.5f, 0f, true, true, 13f, 10.5f)
        arcTo(1.5f, 1.5f, 0f, false, true, 14.5f, 9f)
        moveTo(14.5f, 9f)
        curveTo(15.5f, 9f, 16f, 10f, 16f, 11f)
        curveTo(16f, 12.5f, 14.5f, 14f, 13.5f, 14.5f)
    }.build()

    /** Gambar 2: Crescent Moon for Sleep Timer */
    val SleepMoon: ImageVector = ImageVector.Builder(
        name = "CustomPlayerIcons.SleepMoon",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    ).path(
        stroke = SolidColor(Color.White),
        strokeLineWidth = 2f,
        strokeLineCap = StrokeCap.Round,
        strokeLineJoin = StrokeJoin.Round,
    ) {
        moveTo(21.75f, 12.84f)
        curveTo(20.76f, 13.34f, 19.64f, 13.62f, 18.45f, 13.62f)
        curveTo(13.84f, 13.62f, 10.11f, 9.89f, 10.11f, 5.28f)
        curveTo(10.11f, 4.09f, 10.39f, 2.97f, 10.89f, 1.98f)
        curveTo(5.82f, 2.87f, 1.98f, 7.28f, 1.98f, 12.57f)
        curveTo(1.98f, 18.39f, 6.72f, 23.13f, 12.54f, 23.13f)
        curveTo(17.83f, 23.13f, 22.24f, 19.29f, 23.13f, 14.22f)
        curveTo(22.69f, 13.73f, 22.23f, 13.27f, 21.75f, 12.84f)
        close()
    }.build()

    /** Gambar 3: Crossing Arrows for Shuffle */
    val ShuffleArrows: ImageVector = ImageVector.Builder(
        name = "CustomPlayerIcons.ShuffleArrows",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    ).path(
        stroke = SolidColor(Color.White),
        strokeLineWidth = 2.2f,
        strokeLineCap = StrokeCap.Round,
        strokeLineJoin = StrokeJoin.Round,
    ) {
        // Top arrow going down-right
        moveTo(16f, 3f)
        lineTo(21f, 3f)
        lineTo(21f, 8f)
        moveTo(3f, 21f)
        curveTo(9f, 21f, 15f, 3f, 21f, 3f)

        // Bottom arrow going up-right
        moveTo(16f, 21f)
        lineTo(21f, 21f)
        lineTo(21f, 16f)
        moveTo(3f, 3f)
        curveTo(9f, 3f, 15f, 21f, 21f, 21f)
    }.build()

    /** Gambar 4: Double Loop Arrows for Repeat All */
    val RepeatAll: ImageVector = ImageVector.Builder(
        name = "CustomPlayerIcons.RepeatAll",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    ).path(
        stroke = SolidColor(Color.White),
        strokeLineWidth = 2.2f,
        strokeLineCap = StrokeCap.Round,
        strokeLineJoin = StrokeJoin.Round,
    ) {
        // Top right arrow loop
        moveTo(17f, 2f)
        lineTo(22f, 6f)
        lineTo(17f, 10f)
        moveTo(22f, 6f)
        lineTo(7f, 6f)
        curveTo(4.5f, 6f, 3f, 7.5f, 3f, 10f)
        lineTo(3f, 11f)

        // Bottom left arrow loop
        moveTo(7f, 22f)
        lineTo(2f, 18f)
        lineTo(7f, 14f)
        moveTo(2f, 18f)
        lineTo(17f, 18f)
        curveTo(19.5f, 18f, 21f, 16.5f, 21f, 14f)
        lineTo(21f, 13f)
    }.build()

    /** Gambar 5: Double Loop Arrows with "1" inside for Repeat One */
    val RepeatOne: ImageVector = ImageVector.Builder(
        name = "CustomPlayerIcons.RepeatOne",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    ).path(
        stroke = SolidColor(Color.White),
        strokeLineWidth = 2.2f,
        strokeLineCap = StrokeCap.Round,
        strokeLineJoin = StrokeJoin.Round,
    ) {
        // Top right arrow loop
        moveTo(17f, 2f)
        lineTo(22f, 6f)
        lineTo(17f, 10f)
        moveTo(22f, 6f)
        lineTo(7f, 6f)
        curveTo(4.5f, 6f, 3f, 7.5f, 3f, 10f)
        lineTo(3f, 11f)

        // Bottom left arrow loop
        moveTo(7f, 22f)
        lineTo(2f, 18f)
        lineTo(7f, 14f)
        moveTo(2f, 18f)
        lineTo(17f, 18f)
        curveTo(19.5f, 18f, 21f, 16.5f, 21f, 14f)
        lineTo(21f, 13f)
    }.path(
        stroke = SolidColor(Color.White),
        strokeLineWidth = 2.2f,
        strokeLineCap = StrokeCap.Round,
        strokeLineJoin = StrokeJoin.Round,
    ) {
        // "1" digit in top-right area
        moveTo(17.5f, 11f)
        lineTo(19f, 10f)
        lineTo(19f, 14f)
    }.build()
}
