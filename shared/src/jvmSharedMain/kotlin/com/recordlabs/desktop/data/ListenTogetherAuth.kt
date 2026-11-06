package com.recordlabs.desktop.data

import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

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
    fun token(nowMillis: Long = System.currentTimeMillis()): String? {
        val key = ListenTogetherBuildKey.value
        if (key.isBlank()) return null
        val seconds = (nowMillis / 1000).toString()
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        val signature = mac.doFinal("recordlabs-listen.v1.$seconds".toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
        return "v1.$seconds.$signature"
    }
}
