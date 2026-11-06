package com.recordlabs.innertube.models.body

import com.recordlabs.innertube.models.Context
import com.recordlabs.innertube.models.Continuation
import kotlinx.serialization.Serializable

@Serializable
data class BrowseBody(
    val context: Context,
    val browseId: String?,
    val params: String?,
    val continuation: String?
)
