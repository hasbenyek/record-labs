package com.recordlabs.music.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackRemoteConfigTest {
    private val validDocument = """
        {
          "schemaVersion": 1,
          "revision": "test-1",
          "enabled": true,
          "expiresAtEpochSeconds": 0,
          "minimumAppVersion": "1.9",
          "fallbackClientOrder": [
            "VISIONOS",
            "WEB_CREATOR",
            "TVHTML5",
            "ANDROID_VR_1_43_32",
            "ANDROID_VR_1_61_48",
            "TVHTML5_SIMPLY_EMBEDDED_PLAYER",
            "IOS",
            "IPADOS",
            "ANDROID_CREATOR",
            "ANDROID_VR_NO_AUTH",
            "MOBILE",
            "WEB"
          ],
          "disabledClientIds": [],
          "forceAnonymousPublicPlayback": true,
          "preferFallbackWhenLoggedIn": true,
          "skipWebRemixHeadValidation": true,
          "validateLastFallback": false,
          "clientOverrides": {}
        }
    """.trimIndent()

    @Test
    fun validPolicyPreservesBundledPlaybackBehaviour() {
        val policy = PlaybackRemoteConfig.parseAndValidate(
            validDocument,
            nowEpochSeconds = 1_800_000_000L,
            appVersion = "1.9.6",
        )

        assertEquals("test-1", policy.revision)
        assertEquals(PlaybackRemoteConfig.defaultFallbackClientOrder, policy.fallbackClientOrder)
        assertTrue(policy.forceAnonymousPublicPlayback)
        assertTrue(policy.preferFallbackWhenLoggedIn)
        assertTrue(policy.skipWebRemixHeadValidation)
        assertFalse(policy.validateLastFallback)
    }

    @Test
    fun unknownClientIsRejected() {
        val invalid = validDocument.replace("\"VISIONOS\"", "\"UNTRUSTED_CLIENT\"")

        assertThrows(IllegalArgumentException::class.java) {
            PlaybackRemoteConfig.parseAndValidate(invalid, 1_800_000_000L, "1.9.6")
        }
    }

    @Test
    fun expiredDocumentIsRejected() {
        val expired = validDocument.replace(
            "\"expiresAtEpochSeconds\": 0",
            "\"expiresAtEpochSeconds\": 100",
        )

        assertThrows(IllegalArgumentException::class.java) {
            PlaybackRemoteConfig.parseAndValidate(expired, 1_800_000_000L, "1.9.6")
        }
    }
}
