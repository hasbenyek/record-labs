package com.recordlabs.desktop.data

/**
 * How a platform plays YouTube songs itself (Android: its own engine; iOS: its
 * player): the URI the player opens for a song, a resolver for a stream URL
 * (downloads), the signed-in account, a session check and a prefetch of the next
 * song. Unset on the desktop. (Platform.androidX on Android points here.)
 */
object StreamHooks {
    @kotlin.concurrent.Volatile var streamUri: ((String) -> String)? = null
    @kotlin.concurrent.Volatile var streamResolver: (suspend (String) -> String?)? = null
    @kotlin.concurrent.Volatile var accountProfile: (suspend () -> GoogleAccountProfile?)? = null
    @kotlin.concurrent.Volatile var validateSession: (suspend () -> Boolean?)? = null
    @kotlin.concurrent.Volatile var prefetch: (suspend (String) -> Unit)? = null
}
