package com.recordlabs.desktop.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

/**
 * Thin line icons in the style of iOS (SF Symbols, regular weight): one even
 * 1.6 stroke on a 24 grid with round ends, used by the menus and Settings.
 * Tint them like any icon.
 */
object LineIcons {
    private fun icon(name: String, vararg parts: Pair<Boolean, String>): ImageVector {
        val builder = ImageVector.Builder(name = "LineIcons.$name", defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f)
        for ((filled, data) in parts) {
            if (filled) builder.addPath(pathData = addPathNodes(data), fill = SolidColor(Color.White))
            else builder.addPath(
                pathData = addPathNodes(data),
                stroke = SolidColor(Color.White),
                strokeLineWidth = 1.6f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
        }
        return builder.build()
    }

    val Info: ImageVector by lazy { icon("Info", false to "M12 21a9 9 0 1 0 0-18a9 9 0 1 0 0 18z", false to "M12 11v5.5", true to "M12 7.1a1.05 1.05 0 1 0 0 2.1a1.05 1.05 0 1 0 0-2.1z") }
    val Plus: ImageVector by lazy { icon("Plus", false to "M12 5v14M5 12h14") }
    val AddToList: ImageVector by lazy { icon("AddToList", false to "M4 7h10M4 12h10M4 17h6", false to "M17 14v6M14 17h6") }
    val Share: ImageVector by lazy { icon("Share", false to "M12 3.5v11", false to "M8.5 7L12 3.5L15.5 7", false to "M8.5 10H7a2 2 0 0 0-2 2v7a2 2 0 0 0 2 2h10a2 2 0 0 0 2-2v-7a2 2 0 0 0-2-2h-1.5") }
    val Album: ImageVector by lazy { icon("Album", false to "M6 4h12a2 2 0 0 1 2 2v12a2 2 0 0 1-2 2H6a2 2 0 0 1-2-2V6a2 2 0 0 1 2-2z", false to "M14 8v6.2", false to "M14 8l2.2 0.7", false to "M12.2 16.2a1.8 1.8 0 1 0 0-0.01") }
    val Station: ImageVector by lazy { icon("Station", false to "M12 10.6a1.4 1.4 0 1 0 0 2.8a1.4 1.4 0 1 0 0-2.8z", false to "M8.4 8.4a5.1 5.1 0 0 0 0 7.2M15.6 8.4a5.1 5.1 0 0 1 0 7.2", false to "M5.6 5.6a9 9 0 0 0 0 12.8M18.4 5.6a9 9 0 0 1 0 12.8") }
    val Star: ImageVector by lazy { icon("Star", false to "M12 3.8l2.45 5.1l5.6 0.75l-4.1 3.9l1.03 5.55L12 16.4l-4.98 2.7l1.03-5.55l-4.1-3.9l5.6-0.75z") }
    val Heart: ImageVector by lazy { icon("Heart", false to "M12 19.5s-7.5-4.4-7.5-10a4.2 4.2 0 0 1 7.5-2.6a4.2 4.2 0 0 1 7.5 2.6c0 5.6-7.5 10-7.5 10z") }
    val HeartFill: ImageVector by lazy { icon("HeartFill", true to "M12 19.5s-7.5-4.4-7.5-10a4.2 4.2 0 0 1 7.5-2.6a4.2 4.2 0 0 1 7.5 2.6c0 5.6-7.5 10-7.5 10z") }
    val Person: ImageVector by lazy { icon("Person", false to "M12 11.5a3.75 3.75 0 1 0 0-7.5a3.75 3.75 0 1 0 0 7.5z", false to "M4.75 20c0.6-3.6 3.6-5.6 7.25-5.6s6.65 2 7.25 5.6") }
    val People: ImageVector by lazy { icon("People", false to "M9 11a3.25 3.25 0 1 0 0-6.5a3.25 3.25 0 1 0 0 6.5z", false to "M3 19.5c0.5-3.1 3-4.9 6-4.9s5.5 1.8 6 4.9", false to "M15.5 4.7a3.25 3.25 0 0 1 0 6.1", false to "M17.6 14.9c1.9 0.6 3.1 2.2 3.4 4.6") }
    val Download: ImageVector by lazy { icon("Download", false to "M12 21a9 9 0 1 0 0-18a9 9 0 1 0 0 18z", false to "M12 7.5v8.5M8.5 12.6L12 16.1l3.5-3.5") }
    val Copy: ImageVector by lazy { icon("Copy", false to "M9 8h9a1.5 1.5 0 0 1 1.5 1.5v9A1.5 1.5 0 0 1 18 20H9a1.5 1.5 0 0 1-1.5-1.5v-9A1.5 1.5 0 0 1 9 8z", false to "M16.5 5.5v-0.5A1.5 1.5 0 0 0 15 3.5H6A1.5 1.5 0 0 0 4.5 5v9A1.5 1.5 0 0 0 6 15.5h0.5") }
    val Link: ImageVector by lazy { icon("Link", false to "M10.2 13.8a3.6 3.6 0 0 0 5.1 0l3-3a3.6 3.6 0 0 0-5.1-5.1l-1.2 1.2", false to "M13.8 10.2a3.6 3.6 0 0 0-5.1 0l-3 3a3.6 3.6 0 0 0 5.1 5.1l1.2-1.2") }
    val PlayNext: ImageVector by lazy { icon("PlayNext", false to "M4 6.5h11M4 11.5h11M4 16.5h6", false to "M14 14.2v5.6l4.6-2.8z") }
    val Pin: ImageVector by lazy { icon("Pin", false to "M9 4h6l-0.9 5.2l3.4 3.3v1.5h-11v-1.5l3.4-3.3z", false to "M12 14v6") }
    val Sliders: ImageVector by lazy { icon("Sliders", false to "M5 7h6M15 7h4M5 17h3M12 17h7", false to "M13 5v4M10 15v4") }
    val TextSize: ImageVector by lazy { icon("TextSize", false to "M3.5 18.5L8 6.5l4.5 12M5.2 14.2h5.6", false to "M14.5 18.5l3-7.5l3 7.5M15.6 15.9h3.8") }
    val Translate: ImageVector by lazy { icon("Translate", false to "M4 5.5h9M8.5 4v1.5M11 5.5c-0.8 3.6-3.4 6.5-6.5 8M6.6 8.4c1.1 2 2.8 3.6 4.9 4.6", false to "M12.5 20l3.75-9l3.75 9M13.9 17h4.7") }
    val Globe: ImageVector by lazy { icon("Globe", false to "M12 21a9 9 0 1 0 0-18a9 9 0 1 0 0 18z", false to "M3 12h18", false to "M12 3c2.5 2.6 3.7 5.6 3.7 9s-1.2 6.4-3.7 9c-2.5-2.6-3.7-5.6-3.7-9S9.5 5.6 12 3z") }
    val Pencil: ImageVector by lazy { icon("Pencil", false to "M15.6 4.9l3.5 3.5L8.5 19H5v-3.5z", false to "M13.4 7.1l3.5 3.5") }
    val Refresh: ImageVector by lazy { icon("Refresh", false to "M19.5 12a7.5 7.5 0 1 1-2.2-5.3", false to "M19.5 4.5v4h-4") }
    val Search: ImageVector by lazy { icon("Search", false to "M10.5 17.5a7 7 0 1 0 0-14a7 7 0 1 0 0 14z", false to "M15.6 15.6L20.5 20.5") }
    val Hourglass: ImageVector by lazy { icon("Hourglass", false to "M7 3.5h10M7 20.5h10", false to "M8 3.5c0 4 4 5.2 4 8.5s-4 4.5-4 8.5M16 3.5c0 4-4 5.2-4 8.5s4 4.5 4 8.5") }
    val Timer: ImageVector by lazy { icon("Timer", false to "M12 21a7.5 7.5 0 1 0 0-15a7.5 7.5 0 1 0 0 15z", false to "M12 9.5v4l2.5 1.6", false to "M9.5 3h5") }
    val PiP: ImageVector by lazy { icon("PiP", false to "M5 5h14a2 2 0 0 1 2 2v10a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V7a2 2 0 0 1 2-2z", false to "M13 12.5h4.5a0.5 0.5 0 0 1 0.5 0.5v2.5a0.5 0.5 0 0 1-0.5 0.5H13a0.5 0.5 0 0 1-0.5-0.5V13a0.5 0.5 0 0 1 0.5-0.5z") }
    val Paintbrush: ImageVector by lazy { icon("Paintbrush", false to "M20 4c-2.8 1.2-7.4 5.4-9.3 8.4l1.4 1.4C15.1 11.9 19 7 20 4z", false to "M10.7 12.4c-1.7-0.2-3.3 0.9-3.6 2.7c-0.3 1.7-1 2.9-2.6 3.4c2.7 1.5 6.5 1.1 7.4-1.5c0.4-1.1 0.2-2.3-0.4-3.3") }
    val Waveform: ImageVector by lazy { icon("Waveform", false to "M3.5 12v0M6.5 9v6M9.5 5.5v13M12.5 8v8M15.5 10v4M18.5 7v10M21 11.5v1") }
    val Cloud: ImageVector by lazy { icon("Cloud", false to "M7 18.5h10.5a4 4 0 0 0 0.4-8A6 6 0 0 0 6.4 9.2A4.7 4.7 0 0 0 7 18.5z") }
    val Puzzle: ImageVector by lazy { icon("Puzzle", false to "M9 6V4.75a1.75 1.75 0 0 1 3.5 0V6H17a1 1 0 0 1 1 1v3.5h-1.25a1.75 1.75 0 0 0 0 3.5H18V19a1 1 0 0 1-1 1H6a1 1 0 0 1-1-1V7a1 1 0 0 1 1-1z") }
    val Lock: ImageVector by lazy { icon("Lock", false to "M6.5 10.5h11a1.5 1.5 0 0 1 1.5 1.5v7a1.5 1.5 0 0 1-1.5 1.5h-11A1.5 1.5 0 0 1 5 19v-7a1.5 1.5 0 0 1 1.5-1.5z", false to "M8.5 10.5V8a3.5 3.5 0 0 1 7 0v2.5") }
    val Drive: ImageVector by lazy { icon("Drive", false to "M4 14.5h16v3a1.5 1.5 0 0 1-1.5 1.5h-13A1.5 1.5 0 0 1 4 17.5z", false to "M4 14.5l2.4-8.1a1.5 1.5 0 0 1 1.4-1.1h8.4a1.5 1.5 0 0 1 1.4 1.1l2.4 8.1", true to "M16.6 16.75a0.85 0.85 0 1 0 0.01 0z") }
    val Backup: ImageVector by lazy { icon("Backup", false to "M5 4.5h14a1 1 0 0 1 1 1v2.5a1 1 0 0 1-1 1H5a1 1 0 0 1-1-1V5.5a1 1 0 0 1 1-1z", false to "M5.5 9v9.5a1.5 1.5 0 0 0 1.5 1.5h10a1.5 1.5 0 0 0 1.5-1.5V9", false to "M10 13h4") }
    val Gear: ImageVector by lazy { icon("Gear", false to "M12 15a3 3 0 1 0 0-6a3 3 0 1 0 0 6z", false to "M10.3 3.5h3.4l0.5 2.3l1.6 0.9l2.2-0.8l1.7 2.9l-1.7 1.6v1.8l1.7 1.6l-1.7 2.9l-2.2-0.8l-1.6 0.9l-0.5 2.3h-3.4l-0.5-2.3l-1.6-0.9l-2.2 0.8l-1.7-2.9l1.7-1.6v-1.8L4.3 8.8l1.7-2.9l2.2 0.8l1.6-0.9z") }
    val Headphones: ImageVector by lazy { icon("Headphones", false to "M4.5 15.5v-3a7.5 7.5 0 0 1 15 0v3", false to "M4.5 14.5h2a1 1 0 0 1 1 1v3.5a1 1 0 0 1-1 1h-1a1 1 0 0 1-1-1zM19.5 14.5h-2a1 1 0 0 0-1 1v3.5a1 1 0 0 0 1 1h1a1 1 0 0 0 1-1z") }
    val History: ImageVector by lazy { icon("History", false to "M4.5 12a7.5 7.5 0 1 0 2.2-5.3", false to "M4.5 4.5v4h4", false to "M12 8.5v4l2.6 1.6") }
    val ChevronRight: ImageVector by lazy { icon("ChevronRight", false to "M9.5 5.5l6.5 6.5l-6.5 6.5") }
    val Gauge: ImageVector by lazy { icon("Gauge", false to "M4.2 17a8.5 8.5 0 1 1 15.6 0", false to "M12 13.5l3.6-4.1", true to "M12 12.1a1.4 1.4 0 1 0 0 2.8a1.4 1.4 0 1 0 0-2.8z") }
    val Quote: ImageVector by lazy { icon("Quote", false to "M6.5 4.5h11a2.5 2.5 0 0 1 2.5 2.5v7a2.5 2.5 0 0 1-2.5 2.5H11l-4.5 3.5v-3.5A2.5 2.5 0 0 1 4 14V7a2.5 2.5 0 0 1 2.5-2.5z", true to "M9.2 9h1.8v1.8c0 1-0.5 1.8-1.4 2.2l-0.3-0.6c0.4-0.3 0.6-0.7 0.6-1.1H9.2zM13 9h1.8v1.8c0 1-0.5 1.8-1.4 2.2l-0.3-0.6c0.4-0.3 0.6-0.7 0.6-1.1H13z") }
    val MusicNote: ImageVector by lazy { icon("MusicNote", false to "M9 17.5V6l10-2v11.5", false to "M6.75 19.75a2.25 2.25 0 1 0 0-4.5a2.25 2.25 0 1 0 0 4.5zM16.75 17.75a2.25 2.25 0 1 0 0-4.5a2.25 2.25 0 1 0 0 4.5z") }
    val Xmark: ImageVector by lazy { icon("Xmark", false to "M6 6l12 12M18 6L6 18") }
    val Check: ImageVector by lazy { icon("Check", false to "M5 12.5l4.5 4.5L19 7.5") }
    val Pronounce: ImageVector by lazy { icon("Pronounce", false to "M5.5 4.5h13a2 2 0 0 1 2 2v8a2 2 0 0 1-2 2H12l-4 3.5v-3.5H5.5a2 2 0 0 1-2-2v-8a2 2 0 0 1 2-2z", false to "M8 13l2.2-5.5L12.4 13M8.7 11.3h3", false to "M14.2 8.5h2.8M15.6 8.5v4.5") }
    val Bell: ImageVector by lazy { icon("Bell", false to "M6 16.5V11a6 6 0 0 1 12 0v5.5l1.5 1.5h-15z", false to "M10 20.5a2 2 0 0 0 4 0") }
    val Exclaim: ImageVector by lazy { icon("Exclaim", false to "M12 21a9 9 0 1 0 0-18a9 9 0 1 0 0 18z", false to "M12 7.5v6", true to "M12 15.4a1.05 1.05 0 1 0 0 2.1a1.05 1.05 0 1 0 0-2.1z") }
}
