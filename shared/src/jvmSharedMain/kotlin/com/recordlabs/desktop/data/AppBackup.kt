package com.recordlabs.desktop.data

import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/**
 * Settings > Backup & restore: one .zip with the settings, the library
 * (liked songs, playlists, history, listening stats), recent searches and
 * the local music folders. Downloads and caches are not included.
 */
object AppBackup {
    private const val MANIFEST = "recordlabs-backup.json"
    private const val PREFERENCES = "preferences.json"
    private const val LIBRARY = "library_snapshot.json"
    private const val SEARCHES = "search_history.json"
    private const val FOLDERS = "local-music-folders.txt"
    private const val EXCLUDED_FOLDERS = "local-music-excluded.txt"
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }
    private val stringMap = MapSerializer(String.serializer(), String.serializer())

    /** Google, Last.fm and Musixmatch sign-ins: only in a backup when asked for. */
    private val accountKeys = setOf(
        "googleCookie", "googleVisitorData", "googleDataSyncId", "googleAccountName", "googleAccountEmail",
        "googleAccountChannelHandle", "googleAccountPhoto", "lastFmSessionKey", "lastFmUsername",
        "lastFmApiKey", "lastFmSecret",
    )
    /** Belong to this PC / this installation, never copied. */
    private val localKeys = setOf(
        "connectDeviceId", "preferencesMigrated", "googleSignedOut", "windowSessionSaved", "windowPlacement",
        "windowWidth", "windowHeight", "floatingLyricsPosSaved", "floatingLyricsPosX", "floatingLyricsPosY", "lastPlayback",
    )

    fun defaultFileName(): String =
        "RecordLabs_" + java.time.LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss")) + ".zip"

    fun export(target: File, includeAccounts: Boolean) {
        val preferences = WindowsPreferences.allEntries()
            .filterKeys { it !in localKeys && (includeAccounts || it !in accountKeys) }
        val temp = File(target.absoluteFile.parentFile, target.name + ".tmp")
        ZipOutputStream(temp.outputStream().buffered()).use { zip ->
            fun entry(name: String, text: String) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(text.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
            entry(MANIFEST, buildJsonObject {
                put("app", "Record Labs Windows")
                put("version", AppInfo.version)
                put("format", 1)
                put("createdAt", System.currentTimeMillis())
                put("includesAccounts", includeAccounts)
            }.toString())
            entry(PREFERENCES, json.encodeToString(stringMap, preferences))
            listOf(LIBRARY, SEARCHES, FOLDERS, EXCLUDED_FOLDERS).forEach { name ->
                File(WindowsStorage.directory, name).takeIf(File::isFile)?.let { entry(name, it.readText()) }
            }
        }
        java.nio.file.Files.move(temp.toPath(), target.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
    }

    class Restored(val library: WindowsLibrarySnapshot?, val searchHistory: List<String>?, val settingsCount: Int)

    /**
     * Reads and applies a backup. Throws (changing nothing) when [source] is
     * not a Record Labs backup or is damaged.
     */
    fun restore(source: File): Restored {
        val opened = runCatching { ZipFile(source) }.getOrElse { error("This file is not a Record Labs backup") }
        opened.use { zip ->
            fun text(name: String): String? = zip.getEntry(name)?.let { entry -> zip.getInputStream(entry).use { it.readBytes().toString(Charsets.UTF_8) } }
            val manifest = text(MANIFEST)?.let { json.parseToJsonElement(it).jsonObject }
                ?: error("This file is not a Record Labs backup")
            check(manifest["app"]?.jsonPrimitive?.contentOrNull == "Record Labs Windows") { "This file is not a Record Labs backup" }
            // Everything is read and checked before anything is changed.
            val preferences = text(PREFERENCES)?.let { json.decodeFromString(stringMap, it) }.orEmpty()
                .filterKeys { it !in localKeys }
            val library = text(LIBRARY)?.let { json.decodeFromString(WindowsLibrarySnapshot.serializer(), it) }
            val searches = text(SEARCHES)?.let { json.decodeFromString(ListSerializer(String.serializer()), it) }
            val folders = text(FOLDERS)
            val excludedFolders = text(EXCLUDED_FOLDERS)
            // Files first: if one cannot be written, the settings are still untouched.
            folders?.let { File(WindowsStorage.directory, FOLDERS).writeText(it) }
            excludedFolders?.let { File(WindowsStorage.directory, EXCLUDED_FOLDERS).writeText(it) }
            WindowsPreferences.restoreEntries(preferences)
            return Restored(library, searches, preferences.size)
        }
    }
}
