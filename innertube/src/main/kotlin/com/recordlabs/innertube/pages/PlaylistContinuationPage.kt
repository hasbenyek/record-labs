package com.recordlabs.innertube.pages

import com.recordlabs.innertube.models.SongItem

data class PlaylistContinuationPage(
    val songs: List<SongItem>,
    val continuation: String?,
)
