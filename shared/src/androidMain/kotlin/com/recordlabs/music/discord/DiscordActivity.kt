package com.recordlabs.music.discord

data class DiscordActivity(
    val activityType: Int = 2,
    val name: String?,
    val state: String,
    val details: String?,
    val startTimestamp: Long,
    val endTimestamp: Long?,
    val largeImage: String?,
    val largeText: String?,
    val smallImage: String?,
    val smallText: String?,
    val button1Label: String?,
    val button1Url: String?,
    /** What Discord shows next to the name: 0 the app's name, 1 the state, 2 the details. */
    val statusDisplayType: Int? = null,
) {
    companion object {
        const val TYPE_PLAYING = 0
        const val TYPE_LISTENING = 2
        const val TYPE_WATCHING = 3
        const val TYPE_COMPETING = 5
    }
}
