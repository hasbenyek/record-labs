package com.recordlabs.desktop.data

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.forms.submitForm
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.statement.bodyAsText
import io.ktor.http.parameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.security.MessageDigest
import java.util.Locale

/** A language lyrics can be translated into (Google Translate code and its name). */
data class LyricsTranslationLanguage(val code: String, val name: String)

/** Whether lyrics are translated and into which language; shared by the player and the lyrics page. */
object LyricsTranslationSettings {
    val languages = listOf(
        LyricsTranslationLanguage("id", "Bahasa Indonesia"),
        LyricsTranslationLanguage("en", "English"),
        LyricsTranslationLanguage("ja", "日本語 (Japanese)"),
        LyricsTranslationLanguage("ko", "한국어 (Korean)"),
        LyricsTranslationLanguage("zh-CN", "简体中文 (Chinese, Simplified)"),
        LyricsTranslationLanguage("zh-TW", "繁體中文 (Chinese, Traditional)"),
        LyricsTranslationLanguage("ms", "Bahasa Melayu"),
        LyricsTranslationLanguage("jw", "Basa Jawa"),
        LyricsTranslationLanguage("su", "Basa Sunda"),
        LyricsTranslationLanguage("tl", "Filipino"),
        LyricsTranslationLanguage("th", "ไทย (Thai)"),
        LyricsTranslationLanguage("vi", "Tiếng Việt"),
        LyricsTranslationLanguage("hi", "हिन्दी (Hindi)"),
        LyricsTranslationLanguage("ar", "العربية (Arabic)"),
        LyricsTranslationLanguage("es", "Español"),
        LyricsTranslationLanguage("pt", "Português"),
        LyricsTranslationLanguage("fr", "Français"),
        LyricsTranslationLanguage("de", "Deutsch"),
        LyricsTranslationLanguage("it", "Italiano"),
        LyricsTranslationLanguage("nl", "Nederlands"),
        LyricsTranslationLanguage("ru", "Русский (Russian)"),
        LyricsTranslationLanguage("uk", "Українська (Ukrainian)"),
        LyricsTranslationLanguage("pl", "Polski"),
        LyricsTranslationLanguage("tr", "Türkçe"),
        LyricsTranslationLanguage("sv", "Svenska"),
        LyricsTranslationLanguage("el", "Ελληνικά (Greek)"),
        LyricsTranslationLanguage("he", "עברית (Hebrew)"),
        LyricsTranslationLanguage("fa", "فارسی (Persian)"),
        LyricsTranslationLanguage("bn", "বাংলা (Bengali)"),
        LyricsTranslationLanguage("ta", "தமிழ் (Tamil)"),
    )

    var enabled by mutableStateOf(WindowsPreferences.lyricsTranslationEnabled())
        private set
    var targetLanguage by mutableStateOf(WindowsPreferences.lyricsTranslationLanguage(defaultLanguage()))
        private set

    fun toggle() {
        enabled = !enabled
        WindowsPreferences.setLyricsTranslationEnabled(enabled)
    }

    fun selectLanguage(code: String) {
        targetLanguage = code
        WindowsPreferences.setLyricsTranslationLanguage(code)
    }

    fun nameOf(code: String): String = languages.firstOrNull { it.code == code }?.name ?: code

    private fun defaultLanguage(): String {
        val system = Locale.getDefault()
        val code = when (system.language) {
            "in" -> "id" // Java's old code for Indonesian
            "zh" -> if (system.country in setOf("TW", "HK", "MO")) "zh-TW" else "zh-CN"
            else -> system.language
        }
        return languages.firstOrNull { it.code == code }?.code ?: "en"
    }
}

/**
 * Translates lyric lines with Google Translate's free endpoint (no API key;
 * it detects each line's language too) and falls back to MyMemory. Lines
 * already in the chosen language get no translation. Results are kept in
 * memory and on disk, so a song is translated once per language.
 */
object WindowsLyricsTranslator {
    /** Forgets translations kept in memory (Settings > Storage > Clear lyrics cache). */
    fun clearMemory() {
        synchronized(memory) { memory.clear() }
    }

    private const val MAX_BATCH_LINES = 40
    private const val MAX_BATCH_CHARS = 4_000
    private val jsonCodec = Json { ignoreUnknownKeys = true; isLenient = true }
    private val client = HttpClient {
        install(HttpTimeout) {
            requestTimeoutMillis = 20_000
            connectTimeoutMillis = 10_000
            socketTimeoutMillis = 20_000
        }
    }
    private val memory = object : LinkedHashMap<String, Map<String, String>>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Map<String, String>>?) = size > 24
    }

    /**
     * Translation of every line that is not already in [target], keyed by the
     * line's text; null when no translation service could be reached.
     */
    suspend fun translate(lines: List<String>, target: String): Map<String, String>? =
        translateRaw(lines, target)?.mapValues { (_, text) -> casual(text, target) }

    /**
     * Machine translation is formal ("Saya akan menunggu Anda"); song lyrics
     * speak casually. Formal words are made casual, as lyrics usually are
     * ("Aku akan menunggu kamu", "aku tak bisa").
     */
    private fun casual(text: String, target: String): String {
        val rules = casualRules[target.substringBefore('-')] ?: return text
        var result = text
        for ((pattern, replacement) in rules) {
            result = pattern.replace(result) { match ->
                val word = replacement(match)
                // Capital only where a sentence starts ("Anda" is capitalised everywhere).
                val before = result.substring(0, match.range.first).trimEnd { it.isWhitespace() || it in "\"'“‘(«" }
                val startsSentence = before.isEmpty() || before.last() in ".!?…"
                if (startsSentence && match.value.first().isUpperCase()) word.replaceFirstChar { it.uppercaseChar() } else word
            }
        }
        return result
    }

    private fun wordRule(formal: String, casual: String): Pair<Regex, (MatchResult) -> String> =
        Regex("(?i)(?<![\\p{L}\\p{N}])$formal(?![\\p{L}\\p{N}-])") to { _ -> casual }

    private val indonesianMalayCasual = listOf(
        wordRule("saya", "aku"),
        wordRule("anda", "kamu"),
        wordRule("tidak", "tak"),
    )

    private val casualRules: Map<String, List<Pair<Regex, (MatchResult) -> String>>> = mapOf(
        "id" to indonesianMalayCasual,
        "ms" to indonesianMalayCasual,
    )

    private suspend fun translateRaw(lines: List<String>, target: String): Map<String, String>? = withContext(Dispatchers.IO) {
        val distinct = lines.map(String::trim).filter { it.isNotBlank() && it.any(Char::isLetter) }.distinct()
        if (distinct.isEmpty()) return@withContext emptyMap()
        val key = cacheKey(distinct, target)
        synchronized(memory) { memory[key] }?.let { return@withContext it }
        readDisk(key)?.let { cached ->
            synchronized(memory) { memory[key] = cached }
            return@withContext cached
        }
        val translated = runCatching { translateWithGoogle(distinct, target) }.getOrNull()
            ?: runCatching { translateWithMyMemory(distinct, target) }.getOrNull()
            ?: return@withContext null
        val result = distinct.indices.mapNotNull { index ->
            val (text, detected) = translated.getOrNull(index) ?: return@mapNotNull null
            val original = distinct[index]
            if (text.isBlank() || sameLanguage(detected, target)) return@mapNotNull null
            if (text.trim().equals(original, ignoreCase = true)) return@mapNotNull null
            original to text.trim()
        }.toMap()
        synchronized(memory) { memory[key] = result }
        writeDisk(key, result)
        result
    }

    private fun sameLanguage(detected: String?, target: String): Boolean {
        if (detected.isNullOrBlank()) return false
        val normalized = detected.lowercase(Locale.ROOT)
        val wanted = target.lowercase(Locale.ROOT)
        // Chinese keeps its script variant; other languages compare by base code.
        if (wanted.startsWith("zh") || normalized.startsWith("zh")) return normalized == wanted
        fun base(code: String) = when (val b = code.substringBefore('-')) {
            "iw" -> "he"
            "in" -> "id"
            "jv" -> "jw"
            else -> b
        }
        return base(normalized) == base(wanted)
    }

    /** Google Translate (free, no key): each answer is the translation and the line's detected language. */
    private suspend fun translateWithGoogle(lines: List<String>, target: String): List<Pair<String, String?>>? {
        val results = ArrayList<Pair<String, String?>>(lines.size)
        for (batch in batches(lines)) {
            val body = withTimeoutOrNull(25_000L) {
                client.submitForm(
                    url = "https://translate.googleapis.com/translate_a/t?client=dict-chrome-ex&sl=auto&tl=$target",
                    formParameters = parameters { batch.forEach { append("q", it) } },
                ) {
                    header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                }.takeIf { it.status.value == 200 }?.bodyAsText()
            } ?: return null
            val array = runCatching { jsonCodec.parseToJsonElement(body) as? JsonArray }.getOrNull() ?: return null
            if (array.size != batch.size) return null
            array.forEach { item ->
                when (item) {
                    is JsonArray -> results += (decodeEntities(item.getOrNull(0)?.jsonPrimitive?.contentOrNull.orEmpty()) to
                        item.getOrNull(1)?.jsonPrimitive?.contentOrNull)
                    is JsonPrimitive -> results += (decodeEntities(item.contentOrNull.orEmpty()) to null)
                    else -> results += ("" to null)
                }
            }
        }
        return results
    }

    /** MyMemory (free, no key, a daily limit): one line at a time. */
    private suspend fun translateWithMyMemory(lines: List<String>, target: String): List<Pair<String, String?>>? {
        val results = ArrayList<Pair<String, String?>>(lines.size)
        for (line in lines) {
            val body = withTimeoutOrNull(15_000L) {
                client.get("https://api.mymemory.translated.net/get") {
                    parameter("q", line)
                    parameter("langpair", "Autodetect|${myMemoryCode(target)}")
                }.takeIf { it.status.value == 200 }?.bodyAsText()
            } ?: return null
            val root = runCatching { jsonCodec.parseToJsonElement(body).jsonObject }.getOrNull() ?: return null
            if (root["quotaFinished"]?.jsonPrimitive?.contentOrNull == "true") return null
            // Errors (limits, unsupported languages) come back as "translations" too.
            if (root["responseStatus"]?.jsonPrimitive?.contentOrNull?.trim() != "200") return null
            val data = root["responseData"] as? JsonObject ?: return null
            results += (data["translatedText"]?.jsonPrimitive?.contentOrNull.orEmpty() to
                data["detectedLanguage"]?.jsonPrimitive?.contentOrNull)
        }
        return results
    }

    private fun myMemoryCode(code: String): String = when (code) {
        "jw" -> "jv"
        else -> code
    }

    /** Quotes Google may return as HTML entities. */
    private fun decodeEntities(value: String): String =
        if ('&' !in value) value else value.replace("&#39;", "'").replace("&#34;", "\"").replace("&quot;", "\"")

    private fun batches(lines: List<String>): List<List<String>> {
        val batches = mutableListOf<List<String>>()
        var current = mutableListOf<String>()
        var chars = 0
        for (line in lines) {
            if (current.isNotEmpty() && (current.size >= MAX_BATCH_LINES || chars + line.length > MAX_BATCH_CHARS)) {
                batches += current
                current = mutableListOf()
                chars = 0
            }
            current += line
            chars += line.length
        }
        if (current.isNotEmpty()) batches += current
        return batches
    }

    private fun cacheKey(lines: List<String>, target: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(target.toByteArray())
        lines.forEach { digest.update(0); digest.update(it.toByteArray()) }
        return digest.digest().take(16).joinToString("") { "%02x".format(it) }
    }

    private fun cacheFile(key: String): File =
        File(WindowsStorage.directory, "lyrics-translations").apply { mkdirs() }.resolve("$key.txt")

    private fun readDisk(key: String): Map<String, String>? = runCatching {
        val file = cacheFile(key)
        if (!file.isFile) return@runCatching null
        file.readLines().mapNotNull { row ->
            val tab = row.indexOf('\t')
            if (tab <= 0) null else unescape(row.substring(0, tab)) to unescape(row.substring(tab + 1))
        }.toMap()
    }.getOrNull()

    private fun writeDisk(key: String, result: Map<String, String>) {
        runCatching {
            cacheFile(key).writeText(result.entries.joinToString("\n") { (original, text) -> escape(original) + "\t" + escape(text) })
        }
    }

    private fun escape(value: String) = value.replace("\\", "\\\\").replace("\t", "\\t").replace("\n", "\\n")

    private fun unescape(value: String): String {
        val out = StringBuilder(value.length)
        var index = 0
        while (index < value.length) {
            val c = value[index]
            if (c == '\\' && index + 1 < value.length) {
                when (value[index + 1]) {
                    't' -> out.append('\t')
                    'n' -> out.append('\n')
                    else -> out.append(value[index + 1])
                }
                index += 2
            } else {
                out.append(c)
                index++
            }
        }
        return out.toString()
    }
}
