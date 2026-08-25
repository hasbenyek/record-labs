package com.recordlabs.music.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VersionNameComparatorTest {
    @Test
    fun revisionAIsNewerThanBaseVersion() {
        assertTrue(compareVersionNames("1.9.9.a", "1.9.9") > 0)
    }

    @Test
    fun laterRevisionIsNewer() {
        assertTrue(compareVersionNames("1.9.9.b", "1.9.9.a") > 0)
    }

    @Test
    fun nextNumericVersionRemainsNewerThanRevision() {
        assertTrue(compareVersionNames("1.9.10", "1.9.9.z") > 0)
    }

    @Test
    fun optionalVPrefixDoesNotChangeOrdering() {
        assertEquals(0, compareVersionNames("v1.9.9.a", "1.9.9.a"))
    }
}
