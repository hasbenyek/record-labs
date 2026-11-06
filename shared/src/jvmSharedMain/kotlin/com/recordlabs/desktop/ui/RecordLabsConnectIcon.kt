package com.recordlabs.desktop.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.dp

/** record_labs_connect.svg (screen and speaker) as a vector, for platforms without SVG resources. */
val RecordLabsConnectIcon: ImageVector by lazy {
    ImageVector.Builder("RecordLabsConnect", 24.dp, 24.dp, 24f, 24f)
        .addPath(
            pathData = PathParser().parsePathString("M3.5 5.5h11v8h-11zM7 16h4M9 13.5V16M17 8.5h3.5v9H17zM18.2 15.7h1.1").toNodes(),
            fill = null,
            stroke = SolidColor(Color.White),
            strokeLineWidth = 1.7f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round,
        )
        .build()
}
