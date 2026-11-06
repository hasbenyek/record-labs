package com.recordlabs.music.discord

object DiscordDefaults {
    const val YOUTUBE_WATCH_URL = "https://music.youtube.com/watch?v="

    const val BUTTON1_LABEL = "Listen on YouTube Music"
    const val BUTTON1_URL_TEMPLATE = "https://music.youtube.com/watch?v={song.id}"

    const val STATE_TEMPLATE = "{artist.name}"
    const val DETAILS_TEMPLATE = "{song.name}"

    const val ACTIVITY_TYPE = "2"
    const val ACTIVITY_NAME = ""

    const val USER_STATUS = "online"
    const val STATUS_IDLE = "idle"
    const val STATUS_DND = "dnd"

    const val ACTIVITY_TYPE_LISTENING = "2"
    const val ACTIVITY_TYPE_PLAYING = "0"
    const val ACTIVITY_TYPE_WATCHING = "3"
    const val ACTIVITY_TYPE_COMPETING = "5"

    const val UNKNOWN_ARTIST = "Unknown Artist"
    const val UNKNOWN_ALBUM = "Unknown Album"

    const val DISCORD_OAUTH_AUTHORIZE = "https://discord.com/oauth2/authorize"
    const val DISCORD_OAUTH_TOKEN = "https://discord.com/api/v10/oauth2/token"

    // Per Discord's official Social SDK docs (docs.discord.com/developers/discord-social-sdk/
    // core-concepts/oauth2-scopes): "openid sdk.social_layer_presence" is the correct/complete
    // scope set for rich presence + friends list ("Default Presence Scopes"). The broader
    // "sdk.social_layer" scope (which sdk.social_layer_presence is already a subset of) is only
    // needed for messaging/lobbies/linked-channels "Communication" features, which are currently
    // limited-access on Discord's side and require separate approval - requesting it alongside
    // sdk.social_layer_presence (as briefly tried here) does NOT fix invalid_scope, it just also
    // asks for a scope this app likely isn't approved for. Record Labs only needs presence, so
    // this should stay as just the two presence scopes.
    const val DISCORD_SCOPES = "openid sdk.social_layer_presence"
}
