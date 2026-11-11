package com.recordlabs.desktop.data

import com.recordlabs.desktop.platform.currentTimeMillis
import com.recordlabs.desktop.platform.hmac
import com.recordlabs.desktop.platform.toHex

/**
 * Proves to the Record Labs Listen Together server that this is an official build.
 *
 * The key comes from [ListenTogetherBuildKey], which Gradle generates at build time
 * from `LISTEN_TOGETHER_KEY` in local.properties (or the environment variable of the
 * same name). It is never committed, so builds made from the public source code have
 * no key and send no token.
 */
internal object ListenTogetherAuth {
    const val HEADER = "X-RecordLabs-Auth"

    /** "v1.<unix seconds>.<hex HMAC-SHA256>", or null when this build has no key. */
    fun token(nowMillis: Long = currentTimeMillis()): String? {
        val key = ListenTogetherBuildKey.value
        if (key.isBlank()) return null
        val seconds = (nowMillis / 1000).toString()
        val signature = hmac("HmacSHA256", key.encodeToByteArray(), "recordlabs-listen.v1.$seconds".encodeToByteArray()).toHex()
        return "v1.$seconds.$signature"
    }
}
