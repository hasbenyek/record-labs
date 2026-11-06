package com.recordlabs.desktop.data

/** What a music video offers beyond its default picture: heights and uploader captions. */
data class MusicVideoOptions(
    /** Picture heights it can be shown at, highest first (360p up to 4K). */
    val heights: List<Int> = emptyList(),
    /** Caption tracks made by the uploader (no auto-generated ones). */
    val captions: List<MusicVideoCaptionTrack> = emptyList(),
)

data class MusicVideoCaptionTrack(
    val languageCode: String,
    /** Display name as YouTube shows it ("English", "Japanese"...). */
    val name: String,
    internal val baseUrl: String,
)

/** One caption line: shown from [startMs] until [endMs] (audio position). */
data class MusicVideoCaptionCue(val startMs: Long, val endMs: Long, val text: String)

internal const val MIN_MUSIC_VIDEO_HEIGHT = 360
internal const val STANDARD_MUSIC_VIDEO_HEIGHT = 1080
internal const val MAX_MUSIC_VIDEO_HEIGHT = 2160

private val SRV3_PARAGRAPH = Regex("""<p\b([^>]*)>(.*?)</p>""", RegexOption.DOT_MATCHES_ALL)
private val LEGACY_TEXT = Regex("""<text\b([^>]*)>(.*?)</text>""", RegexOption.DOT_MATCHES_ALL)
// Real markup only ("<font ...>", "</i>"), not text such as "<3".
private val TAG = Regex("""</?[A-Za-z][^>]*>""")
private val BREAK = Regex("""<br\s*/?>""", RegexOption.IGNORE_CASE)
private val NUMERIC_ENTITY = Regex("""&#(x?)([0-9a-fA-F]+);""")

private fun attribute(attributes: String, name: String): String? =
    Regex("""\b$name\s*=\s*"([^"]*)"""").find(attributes)?.groupValues?.get(1)

private fun decodeEntities(text: String): String = NUMERIC_ENTITY.replace(text) { match ->
    val code = match.groupValues[2].toIntOrNull(if (match.groupValues[1].isNotEmpty()) 16 else 10)
    if (code != null && Character.isValidCodePoint(code)) String(Character.toChars(code)) else match.value
}
    .replace("&quot;", "\"")
    .replace("&apos;", "'")
    .replace("&lt;", "<")
    .replace("&gt;", ">")
    .replace("&nbsp;", " ")
    .replace("&amp;", "&")

private fun cleanCaptionText(raw: String): String = decodeEntities(TAG.replace(BREAK.replace(raw, "\n"), ""))
    .lines()
    .map(String::trim)
    .filter(String::isNotEmpty)
    .joinToString("\n")

/**
 * Parses YouTube's timed text: the srv3 format (`<p t="ms" d="ms">`), the
 * older one (`<text start="s" dur="s">`) and json3 (`events` with `segs`).
 */
internal fun parseCaptionCues(body: String): List<MusicVideoCaptionCue> {
    val trimmed = body.trimStart()
    val cues = when {
        trimmed.startsWith("{") -> runCatching {
            val root = kotlinx.serialization.json.Json.parseToJsonElement(trimmed) as kotlinx.serialization.json.JsonObject
            val events = root["events"] as? kotlinx.serialization.json.JsonArray ?: return@runCatching emptyList()
            events.mapNotNull { event ->
                val obj = event as? kotlinx.serialization.json.JsonObject ?: return@mapNotNull null
                val start = (obj["tStartMs"] as? kotlinx.serialization.json.JsonPrimitive)?.content?.toLongOrNull() ?: return@mapNotNull null
                val duration = (obj["dDurationMs"] as? kotlinx.serialization.json.JsonPrimitive)?.content?.toLongOrNull() ?: 0L
                val segs = obj["segs"] as? kotlinx.serialization.json.JsonArray ?: return@mapNotNull null
                val text = segs.joinToString("") { seg ->
                    ((seg as? kotlinx.serialization.json.JsonObject)?.get("utf8") as? kotlinx.serialization.json.JsonPrimitive)?.content.orEmpty()
                }
                MusicVideoCaptionCue(start, start + duration, cleanCaptionText(text))
            }
        }.getOrDefault(emptyList())
        SRV3_PARAGRAPH.containsMatchIn(body) -> SRV3_PARAGRAPH.findAll(body).mapNotNull { match ->
            val attributes = match.groupValues[1]
            val start = attribute(attributes, "t")?.toLongOrNull() ?: return@mapNotNull null
            val duration = attribute(attributes, "d")?.toLongOrNull() ?: 0L
            MusicVideoCaptionCue(start, start + duration, cleanCaptionText(match.groupValues[2]))
        }.toList()
        else -> LEGACY_TEXT.findAll(body).mapNotNull { match ->
            val attributes = match.groupValues[1]
            val start = attribute(attributes, "start")?.toDoubleOrNull() ?: return@mapNotNull null
            val duration = attribute(attributes, "dur")?.toDoubleOrNull() ?: 0.0
            // This format escapes its text twice ("&amp;#39;", "&lt;font&gt;").
            MusicVideoCaptionCue((start * 1000).toLong(), ((start + duration) * 1000).toLong(), cleanCaptionText(decodeEntities(match.groupValues[2])))
        }.toList()
    }
    return cues.filter { it.text.isNotBlank() && it.endMs > it.startMs }.sortedBy { it.startMs }
}

/** Text of the caption lines showing at [positionMs] (several may overlap), or null. */
fun List<MusicVideoCaptionCue>.textAt(positionMs: Long): String? {
    if (isEmpty()) return null
    // Last cue starting at or before the position.
    var low = 0
    var high = lastIndex
    var found = -1
    while (low <= high) {
        val mid = (low + high) ushr 1
        if (this[mid].startMs <= positionMs) {
            found = mid
            low = mid + 1
        } else {
            high = mid - 1
        }
    }
    if (found < 0) return null
    val showing = ArrayList<String>(2)
    var index = found
    // Overlapping lines: walk back a little for any still showing.
    while (index >= 0 && found - index < 4) {
        val cue = this[index]
        if (positionMs < cue.endMs) showing.add(0, cue.text)
        index--
    }
    return showing.takeIf { it.isNotEmpty() }?.joinToString("\n")
}
