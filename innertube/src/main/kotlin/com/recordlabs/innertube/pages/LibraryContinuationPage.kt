package com.recordlabs.innertube.pages

import com.recordlabs.innertube.models.YTItem

data class LibraryContinuationPage(
    val items: List<YTItem>,
    val continuation: String?,
)
