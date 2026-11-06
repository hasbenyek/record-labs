package com.recordlabs.desktop.data

import kotlinx.coroutines.runBlocking
import org.junit.Test
import kotlin.test.assertTrue

class SearchSuggestionsTest {
    @Test
    fun youtubeMusicReturnsContextualQuerySuggestions() = runBlocking {
        WindowsMusicRepository().use { repository ->
            val suggestions = repository.searchSuggestions("beaches bea")
            assertTrue(suggestions.isNotEmpty(), "YouTube Music suggestions must not be empty")
            assertTrue(suggestions.any { it.contains("beaches", ignoreCase = true) })
        }
    }
}
