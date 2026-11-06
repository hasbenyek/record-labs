package com.recordlabs.desktop

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.floor
import kotlin.math.roundToInt

/**
 * Card size for a horizontal shelf on a wide (non-compact) window: the cards
 * grow with the window so a large landscape screen is not mostly empty space,
 * and never get smaller than [base] (the size they always had).
 */
internal fun adaptiveCardWidth(available: Dp, base: Dp): Dp =
    (available / CARDS_ACROSS).coerceIn(base, base * MAX_CARD_GROWTH)

/**
 * Width of one column of a list shelf (Quick picks, Top songs) on a wide
 * window. When all [panels] fit side by side they share the whole width; when
 * there are more, the visible columns fill the width with half of the next
 * one peeking in, so it still reads as scrollable. Stays close to [base]
 * (never below 85 % of it).
 */
internal fun adaptivePanelWidth(available: Dp, base: Dp, spacing: Dp, panels: Int): Dp {
    if (panels <= 0 || available < WIDE_WINDOW || base <= 0.dp) return base
    val fitting = floor((available + spacing) / (base + spacing)).toInt().coerceAtLeast(1)
    if (panels <= fitting) {
        val shared = (available - spacing * (panels - 1)) / panels
        return shared.coerceIn(base, base * MAX_PANEL_GROWTH)
    }
    // Every whole column that fits at the base width, plus the peek.
    val whole = ((available + spacing) / (base + spacing) - PEEK).roundToInt().coerceAtLeast(1)
    val width = (available - spacing * whole) / (whole + PEEK)
    return width.coerceIn(base * 0.85f, base * MAX_PANEL_GROWTH)
}

/** Narrower windows keep the list columns exactly as they were. */
private val WIDE_WINDOW = 1100.dp
/** About this many cards across the window before they stop growing. */
private const val CARDS_ACROSS = 7.2f
private const val MAX_CARD_GROWTH = 1.5f
private const val MAX_PANEL_GROWTH = 2.2f
/** Share of the next list column that shows at the right edge. */
private const val PEEK = 0.5f
