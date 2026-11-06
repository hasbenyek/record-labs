package com.recordlabs.desktop.data

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class WindowsRomanizerTest {
    @Test
    fun japaneseKanjiUsesJapaneseReadingInsteadOfMandarin() {
        val result = WindowsRomanizer.romanize("咳き込める重圧と")

        assertNotNull(result)
        assertTrue(result.contains("seki", ignoreCase = true), result)
        assertTrue(result.contains("juuatsu", ignoreCase = true) || result.contains("juatsu", ignoreCase = true), result)
        assertFalse(result.contains("zhong", ignoreCase = true), result)
    }
}
