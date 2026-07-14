package com.recordlabs.innertube.pages

import com.recordlabs.innertube.models.YTItem

data class ArtistItemsContinuationPage(
    val items: List<YTItem>,
    val continuation: String?,
)
