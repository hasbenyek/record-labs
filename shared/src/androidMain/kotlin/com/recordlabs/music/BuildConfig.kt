package com.recordlabs.music

/** The values the original Android app's playback code reads from its generated BuildConfig. */
object BuildConfig {
    const val DEBUG: Boolean = false
    // The original app's version: the remote playback policy is published for it.
    const val VERSION_NAME: String = "1.9.9.c"
    // Discord application of Record Labs (Rich Presence), the same one as the original
    // Android app and the desktop app (DiscordRichPresence.APPLICATION_ID).
    const val DISCORD_APP_ID: Long = 1525699798076887183L
}
