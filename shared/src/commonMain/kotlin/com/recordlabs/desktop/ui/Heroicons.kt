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
 * Heroicons Vector Library for Record Labs Music Player UI.
 */
object Heroicons {

    val ArrowLeft: ImageVector = ImageVector.Builder(
        name = "Heroicons.ArrowLeft",
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
        moveTo(10.5f, 19.5f)
        lineTo(3f, 12f)
        lineTo(10.5f, 4.5f)
        moveTo(3f, 12f)
        lineTo(21f, 12f)
    }.build()

    val Share: ImageVector = ImageVector.Builder(
        name = "Heroicons.Share",
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
        moveTo(7.217f, 10.907f)
        lineTo(14.783f, 7.093f)
        moveTo(14.777f, 16.907f)
        lineTo(7.223f, 13.093f)
        moveTo(21f, 5.25f)
        arcTo(2.25f, 2.25f, 0f, true, true, 16.5f, 5.25f)
        arcTo(2.25f, 2.25f, 0f, true, true, 21f, 5.25f)
        moveTo(7.5f, 12f)
        arcTo(2.25f, 2.25f, 0f, true, true, 3f, 12f)
        arcTo(2.25f, 2.25f, 0f, true, true, 7.5f, 12f)
        moveTo(21f, 18.75f)
        arcTo(2.25f, 2.25f, 0f, true, true, 16.5f, 18.75f)
        arcTo(2.25f, 2.25f, 0f, true, true, 21f, 18.75f)
    }.build()

    val Heart: ImageVector = ImageVector.Builder(
        name = "Heroicons.Heart",
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
        moveTo(21f, 8.25f)
        curveTo(21f, 5.765f, 18.901f, 3.75f, 16.312f, 3.75f)
        curveTo(14.377f, 3.75f, 12.715f, 4.876f, 12f, 6.483f)
        curveTo(11.285f, 4.876f, 9.623f, 3.75f, 7.688f, 3.75f)
        curveTo(5.099f, 3.75f, 3f, 5.765f, 3f, 8.25f)
        curveTo(3f, 15.429f, 12f, 20.25f, 12f, 20.25f)
        curveTo(12f, 20.25f, 21f, 15.429f, 21f, 8.25f)
        close()
    }.build()

    val HeartSolid: ImageVector = ImageVector.Builder(
        name = "Heroicons.HeartSolid",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    ).path(
        fill = SolidColor(Color.White),
        pathFillType = PathFillType.NonZero,
    ) {
        moveTo(11.645f, 20.91f)
        lineTo(11.64f, 20.907f)
        curveTo(6.059f, 17.518f, 2.25f, 13.568f, 2.25f, 8.625f)
        curveTo(2.25f, 5.485f, 4.735f, 3f, 7.875f, 3f)
        curveTo(9.664f, 3f, 11.298f, 3.844f, 12f, 5.259f)
        curveTo(12.702f, 3.844f, 14.336f, 3f, 16.125f, 3f)
        curveTo(19.265f, 3f, 21.75f, 5.485f, 21.75f, 8.625f)
        curveTo(21.75f, 13.568f, 17.941f, 17.518f, 12.36f, 20.907f)
        lineTo(12.355f, 20.91f)
        lineTo(12f, 21.119f)
        lineTo(11.645f, 20.91f)
        close()
    }.build()

    val EllipsisVertical: ImageVector = ImageVector.Builder(
        name = "Heroicons.EllipsisVertical",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    ).path(
        fill = SolidColor(Color.White),
    ) {
        moveTo(12f, 6.75f)
        arcTo(1.5f, 1.5f, 0f, true, true, 12f, 3.75f)
        arcTo(1.5f, 1.5f, 0f, true, true, 12f, 6.75f)
        moveTo(12f, 13.5f)
        arcTo(1.5f, 1.5f, 0f, true, true, 12f, 10.5f)
        arcTo(1.5f, 1.5f, 0f, true, true, 12f, 13.5f)
        moveTo(12f, 20.25f)
        arcTo(1.5f, 1.5f, 0f, true, true, 12f, 17.25f)
        arcTo(1.5f, 1.5f, 0f, true, true, 12f, 20.25f)
    }.build()

    val Backward: ImageVector = ImageVector.Builder(
        name = "Heroicons.Backward",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    ).path(
        fill = SolidColor(Color.White),
    ) {
        moveTo(9.195f, 18.44f)
        curveTo(10.445f, 19.154f, 12f, 18.251f, 12f, 16.812f)
        lineTo(12f, 13.5f)
        lineTo(18.195f, 17.04f)
        curveTo(19.445f, 17.754f, 21f, 16.851f, 21f, 15.412f)
        lineTo(21f, 8.588f)
        curveTo(21f, 7.149f, 19.445f, 6.246f, 18.195f, 6.96f)
        lineTo(12f, 10.5f)
        lineTo(12f, 7.188f)
        curveTo(12f, 5.749f, 10.445f, 4.846f, 9.195f, 5.56f)
        lineTo(2.087f, 9.622f)
        curveTo(0.827f, 10.342f, 0.827f, 12.158f, 2.087f, 12.878f)
        lineTo(9.195f, 18.44f)
        close()
    }.build()

    val Forward: ImageVector = ImageVector.Builder(
        name = "Heroicons.Forward",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    ).path(
        fill = SolidColor(Color.White),
    ) {
        moveTo(14.805f, 5.56f)
        curveTo(13.555f, 4.846f, 12f, 5.749f, 12f, 7.188f)
        lineTo(12f, 10.5f)
        lineTo(5.805f, 6.96f)
        curveTo(4.555f, 6.246f, 3f, 7.149f, 3f, 8.588f)
        lineTo(3f, 15.412f)
        curveTo(3f, 16.851f, 4.555f, 17.754f, 5.805f, 17.04f)
        lineTo(12f, 13.5f)
        lineTo(12f, 16.812f)
        curveTo(12f, 18.251f, 13.555f, 19.154f, 14.805f, 18.44f)
        lineTo(21.913f, 14.378f)
        curveTo(23.173f, 13.658f, 23.173f, 11.842f, 21.913f, 11.122f)
        lineTo(14.805f, 5.56f)
        close()
    }.build()


    val PlaySolid: ImageVector = ImageVector.Builder(
        name = "Heroicons.PlaySolid",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    ).path(
        fill = SolidColor(Color.White),
    ) {
        moveTo(4.5f, 5.653f)
        curveTo(4.5f, 4.226f, 6.029f, 3.323f, 7.279f, 4.01f)
        lineTo(18.819f, 10.357f)
        curveTo(20.114f, 11.069f, 20.114f, 12.93f, 18.819f, 13.642f)
        lineTo(7.28f, 19.99f)
        curveTo(6.03f, 20.677f, 4.5f, 19.773f, 4.5f, 18.347f)
        lineTo(4.5f, 5.653f)
        close()
    }.build()

    val PauseSolid: ImageVector = ImageVector.Builder(
        name = "Heroicons.PauseSolid",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    ).path(
        fill = SolidColor(Color.White),
    ) {
        moveTo(6.75f, 5.25f)
        arcTo(0.75f, 0.75f, 0f, false, true, 7.5f, 4.5f)
        lineTo(9f, 4.5f)
        arcTo(0.75f, 0.75f, 0f, false, true, 9.75f, 5.25f)
        lineTo(9.75f, 18.75f)
        arcTo(0.75f, 0.75f, 0f, false, true, 9f, 19.5f)
        lineTo(7.5f, 19.5f)
        arcTo(0.75f, 0.75f, 0f, false, true, 6.75f, 18.75f)
        lineTo(6.75f, 5.25f)
        close()
        moveTo(14.25f, 5.25f)
        arcTo(0.75f, 0.75f, 0f, false, true, 15f, 4.5f)
        lineTo(16.5f, 4.5f)
        arcTo(0.75f, 0.75f, 0f, false, true, 17.25f, 5.25f)
        lineTo(17.25f, 18.75f)
        arcTo(0.75f, 0.75f, 0f, false, true, 16.5f, 19.5f)
        lineTo(15f, 19.5f)
        arcTo(0.75f, 0.75f, 0f, false, true, 14.25f, 18.75f)
        lineTo(14.25f, 5.25f)
        close()
    }.build()


    val QueueList: ImageVector = ImageVector.Builder(
        name = "Heroicons.QueueList",
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
        moveTo(3.75f, 6.75f)
        lineTo(20.25f, 6.75f)
        moveTo(3.75f, 12f)
        lineTo(20.25f, 12f)
        moveTo(3.75f, 17.25f)
        lineTo(20.25f, 17.25f)
    }.build()

    val Clock: ImageVector = ImageVector.Builder(
        name = "Heroicons.Clock",
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
        moveTo(12f, 6f)
        lineTo(12f, 12f)
        lineTo(16.5f, 14.25f)
        moveTo(21f, 12f)
        arcTo(9f, 9f, 0f, true, true, 3f, 12f)
        arcTo(9f, 9f, 0f, true, true, 21f, 12f)
    }.build()

    val ArrowsRightLeft: ImageVector = ImageVector.Builder(
        name = "Heroicons.ArrowsRightLeft",
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
        moveTo(7.5f, 21f)
        lineTo(3f, 16.5f)
        moveTo(3f, 16.5f)
        lineTo(7.5f, 12f)
        moveTo(3f, 16.5f)
        lineTo(21f, 16.5f)
        moveTo(16.5f, 3f)
        lineTo(21f, 7.5f)
        moveTo(21f, 7.5f)
        lineTo(16.5f, 12f)
        moveTo(21f, 7.5f)
        lineTo(3f, 7.5f)
    }.build()

    val MusicalNote: ImageVector = ImageVector.Builder(
        name = "Heroicons.MusicalNote",
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
        moveTo(9f, 9f)
        lineTo(20.25f, 6.75f)
        moveTo(9f, 9f)
        lineTo(9f, 18f)
        moveTo(9f, 9f)
        lineTo(20.25f, 6.75f)
        moveTo(20.25f, 6.75f)
        lineTo(20.25f, 15.75f)
        moveTo(9f, 18f)
        arcTo(2.25f, 2.25f, 0f, true, true, 4.5f, 18f)
        arcTo(2.25f, 2.25f, 0f, true, true, 9f, 18f)
        moveTo(20.25f, 15.75f)
        arcTo(2.25f, 2.25f, 0f, true, true, 15.75f, 15.75f)
        arcTo(2.25f, 2.25f, 0f, true, true, 20.25f, 15.75f)
    }.build()

    val ArrowPath: ImageVector = ImageVector.Builder(
        name = "Heroicons.ArrowPath",
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
        moveTo(16.023f, 9.348f)
        arcTo(7.518f, 7.518f, 0f, false, true, 19.5f, 16.5f)
        arcTo(7.5f, 7.5f, 0f, false, true, 4.5f, 16.5f)
        moveTo(16.023f, 9.348f)
        lineTo(19.5f, 9.348f)
        moveTo(16.023f, 9.348f)
        lineTo(16.023f, 5.848f)
        moveTo(7.977f, 14.652f)
        arcTo(7.518f, 7.518f, 0f, false, true, 4.5f, 7.5f)
        arcTo(7.5f, 7.5f, 0f, false, true, 19.5f, 7.5f)
        moveTo(7.977f, 14.652f)
        lineTo(4.5f, 14.652f)
        moveTo(7.977f, 14.652f)
        lineTo(7.977f, 18.152f)
    }.build()
}
