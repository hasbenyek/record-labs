package com.recordlabs.desktop.data

import com.recordlabs.desktop.platform.*

import kotlinx.serialization.json.Json
object SearchHistoryStore {
    private val json = Json { ignoreUnknownKeys = true }
    private val file: File by lazy {
        File(AppStorage.directory, "search_history.json")
    }

    fun load(): List<String> = runCatching {
        if (file.exists()) json.decodeFromString<List<String>>(file.readText()) else emptyList()
    }.getOrDefault(emptyList())

    fun save(history: List<String>) {
        runCatching { file.writeText(json.encodeToString(history.takeLast(20))) }
    }
}
