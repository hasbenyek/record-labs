package com.recordlabs.music.discord

import com.recordlabs.music.db.entities.Song
import timber.log.Timber

object DiscordActivityBuilder {
    private const val TAG = "DiscordSvc"

    fun build(
        song: Song,
        artistName: String,
        albumName: String?,
        artistThumbnail: String?,
        songTitle: String,
        startTimestamp: Long,
        endTimestamp: Long?,
        advancedMode: Boolean,
        activityType: Int = DiscordActivity.TYPE_LISTENING,
        activityName: String? = null,
        stateTemplate: String = DiscordDefaults.STATE_TEMPLATE,
        detailsTemplate: String = DiscordDefaults.DETAILS_TEMPLATE,
        btn1Enabled: Boolean = true,
        btn1Label: String = DiscordDefaults.BUTTON1_LABEL,
        btn1Url: String = DiscordDefaults.BUTTON1_URL_TEMPLATE,
    ): DiscordActivity {
        val state: String
        val details: String?
        val renderedBtn1Label: String?
        val renderedBtn1Url: String?

        if (advancedMode) {
            state = DiscordTemplateRenderer.render(
                stateTemplate.ifEmpty { DiscordDefaults.STATE_TEMPLATE },
                songTitle, artistName, albumName, song.song.id
            )
            details = DiscordTemplateRenderer.render(
                detailsTemplate.ifEmpty { DiscordDefaults.DETAILS_TEMPLATE },
                songTitle, artistName, albumName, song.song.id
            )
            renderedBtn1Label = if (btn1Enabled) {
                DiscordTemplateRenderer.render(
                    btn1Label.ifEmpty { DiscordDefaults.BUTTON1_LABEL },
                    songTitle, artistName, albumName, song.song.id
                )
            } else null
            renderedBtn1Url = if (btn1Enabled) {
                DiscordTemplateRenderer.render(
                    btn1Url.ifEmpty { DiscordDefaults.BUTTON1_URL_TEMPLATE },
                    songTitle, artistName, albumName, song.song.id
                )
            } else null
        } else {
            state = artistName
            details = songTitle
            renderedBtn1Label = DiscordDefaults.BUTTON1_LABEL
            renderedBtn1Url = "${DiscordDefaults.YOUTUBE_WATCH_URL}${song.song.id}"
        }

        val renderedName = if (advancedMode && !activityName.isNullOrEmpty()) {
            DiscordTemplateRenderer.render(
                activityName,
                songTitle, artistName, albumName, song.song.id,
            )
        } else {
            activityName?.takeIf { it.isNotEmpty() } ?: artistName
        }

        val result = DiscordActivity(
            activityType = activityType,
            name = renderedName,
            state = state,
            details = details,
            startTimestamp = startTimestamp,
            endTimestamp = endTimestamp,
            largeImage = song.song.thumbnailUrl,
            largeText = albumName,
            smallImage = artistThumbnail,
            smallText = artistName,
            button1Label = renderedBtn1Label,
            button1Url = renderedBtn1Url,
        )

        Timber.tag(TAG).d(
            "build: result — name=%s, type=%d, state=%s, details=%s, btn1=%s",
            result.name, result.activityType, result.state, result.details,
            result.button1Label?.take(30),
        )

        return result
    }
}
