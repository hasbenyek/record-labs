package com.recordlabs.innertube.models.body

import com.recordlabs.innertube.models.Context
import kotlinx.serialization.Serializable

@Serializable
data class GetTranscriptBody(
    val context: Context,
    val params: String,
)
