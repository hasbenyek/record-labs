package com.recordlabs.desktop

import androidx.compose.ui.Modifier

/** Desktop: a mouse wheel scrolls a horizontal row (nothing to do on touch screens). */
expect fun Modifier.verticalToHorizontalScroll(): Modifier
