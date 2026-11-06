package com.recordlabs.desktop.data

import org.w3c.dom.Element
import org.w3c.dom.Node
import java.util.Locale
import javax.xml.parsers.DocumentBuilderFactory

data class TimedLyricWord(
    val text: String,
    val startMs: Long,
    val endMs: Long,
    val trailingSpace: Boolean = true,
)

data class RichLyricLine(
    val startMs: Long,
    val text: String,
    val words: List<TimedLyricWord> = emptyList(),
    val agent: String? = null,
    val isBackground: Boolean = false,
    val endMs: Long? = null,
)

object WindowsRichLyrics {
    private val lineTime = Regex("^\\[(\\d{1,3}):(\\d{2})[.:](\\d{2,3})\\](.*)$")
    private val inlineWord = Regex("<(\\d{1,3}):(\\d{2})[.:](\\d{2,3})>([^<]*)")
    private val metadata = Regex("^\\[(ar|ti|al|by|offset|length):", RegexOption.IGNORE_CASE)
    private val agentMarker = Regex("\\{agent:([^}]+)\\}", RegexOption.IGNORE_CASE)
    private val backgroundMarker = Regex("\\{bg\\}", RegexOption.IGNORE_CASE)
    private val paxAgent = Regex("^(v\\d+):\\s*(.*)$", RegexOption.IGNORE_CASE)
    private val paxBackground = Regex("^\\[bg:\\s*(.*)\\]$", RegexOption.IGNORE_CASE)

    fun parse(raw: String): List<RichLyricLine> {
        if (raw.isBlank()) return emptyList()
        val source = raw.lines().map(String::trim).filter(String::isNotBlank)
        val result = mutableListOf<RichLyricLine>()
        var lastNonBackgroundAgent: String? = null
        // BetterLyrics emits {bg} only on the first row of a consecutive
        // background-vocal run. Following rows remain background lyrics until
        // an explicit agent row starts the next main-vocal run.
        var backgroundRunActive = false
        var index = 0
        while (index < source.size) {
            val line = source[index]
            if (metadata.containsMatchIn(line)) {
                index++
                continue
            }
            // Paxsenix multi-person eLRC represents backing/sub lyrics as a
            // standalone [bg: <time>text...] row, rather than an LRC line.
            // Preserve it as its own timed and centered RichLyricLine.
            val backgroundMatch = paxBackground.matchEntire(line)
            if (backgroundMatch != null) {
                val bgContent = backgroundMatch.groupValues[1]
                var bgWords = parseInlineWords(bgContent)
                var bgText = if (bgWords.isNotEmpty()) {
                    bgWords = applySourceSpacing(bgWords, inlineWord.findAll(bgContent).joinToString("") { it.groupValues[4] })
                    bgWords.joinToString("") { it.text + if (it.trailingSpace) " " else "" }.trim()
                } else bgContent.replace(inlineWord, "").trim()
                bgWords = stripBackgroundParentheses(bgWords)
                bgText = bgText.removeSurrounding("(", ")").trim()
                val bgStart = bgWords.minOfOrNull(TimedLyricWord::startMs) ?: 0L
                if (bgText.isNotBlank() && bgStart > 0L) {
                    result += RichLyricLine(bgStart, bgText, bgWords, lastNonBackgroundAgent ?: "v1", true, bgWords.maxOfOrNull { it.endMs })
                }
                backgroundRunActive = true
                index++
                continue
            }
            val match = lineTime.matchEntire(line)
            if (match == null) {
                index++
                continue
            }
            val start = timestampMs(match.groupValues[1], match.groupValues[2], match.groupValues[3])
            var content = match.groupValues[4].trim()
            val rawContent = content
            // Providers may emit both markers in a row. Parse their roles
            // independently so {bg} can never be mistaken for v1/v2 text.
            val inlineBackgroundMatch = backgroundMarker.find(content)
            val backgroundMarkerIndex = inlineBackgroundMatch?.range?.first ?: -1
            val mainBeforeBackground = if (backgroundMarkerIndex > 0) {
                content.substring(0, backgroundMarkerIndex).replace(agentMarker, "").trim()
            } else ""
            // A provider may pack: {agent:v2}main words {bg}(backing words)
            // into one LRC record. It must become two distinct render rows.
            val hasInlineBackground = mainBeforeBackground.isNotBlank()
            var agent = agentMarker.find(content)?.groupValues?.getOrNull(1)
            content = content.replace(agentMarker, "").replace(backgroundMarker, "").trim()
            // elrcMultiPerson has the equivalent metadata as v1:/v2:.
            val paxAgentMatch = paxAgent.matchEntire(content)
            paxAgentMatch?.let { pax ->
                agent = pax.groupValues[1].lowercase()
                content = pax.groupValues[2]
            }
            val hasExplicitAgent = !agent.isNullOrBlank()
            val isBackground = when {
                hasInlineBackground -> false
                inlineBackgroundMatch != null -> true
                hasExplicitAgent -> false
                else -> backgroundRunActive
            }
            var words = parseInlineWords(content)
            if (words.isNotEmpty()) {
                val inlineText = inlineWord.findAll(content).joinToString("") { it.groupValues[4] }
                words = applySourceSpacing(words, inlineText)
                content = words.joinToString("") { it.text + if (it.trailingSpace) " " else "" }.trim()
            } else if (index + 1 < source.size && source[index + 1].startsWith("<") && source[index + 1].endsWith(">")) {
                words = applySourceSpacing(parseSerializedWords(source[++index]), content)
            }
            if (isBackground) {
                words = stripBackgroundParentheses(words)
                content = content.removeSurrounding("(", ")").trim()
            }
            if (!isBackground && !agent.isNullOrBlank()) lastNonBackgroundAgent = agent
        val lineEndMs = words.maxOfOrNull { it.endMs }
        if (words.size <= 1) words = emptyList()
            if (content.isNotBlank()) {
                // {bg} is the only source of a background/sub-lyric. Parentheses
                // are ordinary v1/v2 text and must wrap inside their own row.
                val resolvedAgent = agent ?: lastNonBackgroundAgent
                if (hasInlineBackground) {
                    val rawBackground = rawContent.substring(backgroundMarkerIndex + inlineBackgroundMatch!!.value.length)
                        .replace(agentMarker, "").replace(backgroundMarker, "").trim()
                    val mainTokenCount = mainBeforeBackground.split(Regex("\\s+")).count(String::isNotBlank)
                    val mainWords = words.take(mainTokenCount)
                    val backgroundWords = stripBackgroundParentheses(words.drop(mainTokenCount))
                    val mainText = mainBeforeBackground
                    val backgroundText = rawBackground.removeSurrounding("(", ")").trim()
                    if (mainText.isNotBlank()) result += RichLyricLine(start, mainText, mainWords, resolvedAgent, false, lineEndMs)
                    if (backgroundText.isNotBlank()) result += RichLyricLine(
                        backgroundWords.minOfOrNull(TimedLyricWord::startMs) ?: start,
                        backgroundText,
                        backgroundWords,
                        resolvedAgent,
                        true,
                        backgroundWords.maxOfOrNull { it.endMs }
                    )
                } else {
                    result += RichLyricLine(start, content, words, resolvedAgent, isBackground, lineEndMs)
                }
            }
            backgroundRunActive = when {
                hasInlineBackground || isBackground -> true
                hasExplicitAgent -> false
                else -> backgroundRunActive
            }
            index++
        }
        if (result.isNotEmpty()) return result.sortedBy(RichLyricLine::startMs)
        return source.filterNot { metadata.containsMatchIn(it) || Regex("^\\[[^\\]]*\\]$").matches(it) }.mapIndexed { plainIndex, text ->
            RichLyricLine(plainIndex * 3_000L, text)
        }
    }

    private fun parseInlineWords(content: String): List<TimedLyricWord> {
        val matches = inlineWord.findAll(content).toList()
        if (matches.isEmpty()) return emptyList()
        return matches.mapIndexed { index, match ->
            val text = match.groupValues[4]
            val start = timestampMs(match.groupValues[1], match.groupValues[2], match.groupValues[3])
            val nextStart = matches.getOrNull(index + 1)?.let {
                timestampMs(it.groupValues[1], it.groupValues[2], it.groupValues[3])
            } ?: start + 800L
            TimedLyricWord(text.trim(), start, nextStart.coerceAtLeast(start + 40L), text.lastOrNull()?.isWhitespace() == true)
        }.filter { it.text.isNotBlank() }
    }

    private fun stripBackgroundParentheses(words: List<TimedLyricWord>): List<TimedLyricWord> =
        words.mapIndexed { index, word ->
            var text = word.text
            if (index == 0) text = text.removePrefix("(")
            if (index == words.lastIndex) text = text.removeSuffix(")")
            word.copy(text = text.trim())
        }.filter { it.text.isNotBlank() }

    private fun parseSerializedWords(value: String): List<TimedLyricWord> = value.removePrefix("<").removeSuffix(">")
        .split('|')
        .mapIndexedNotNull { index, encoded ->
            val lastColon = encoded.lastIndexOf(':')
            val previousColon = encoded.lastIndexOf(':', lastColon - 1)
            if (lastColon <= 0 || previousColon <= 0) return@mapIndexedNotNull null
            val rawText = encoded.substring(0, previousColon)
            val text = rawText.trim()
            val start = (encoded.substring(previousColon + 1, lastColon).toDoubleOrNull()?.times(1000))?.toLong() ?: return@mapIndexedNotNull null
            val end = (encoded.substring(lastColon + 1).toDoubleOrNull()?.times(1000))?.toLong() ?: start + 500L
            TimedLyricWord(text, start, end.coerceAtLeast(start + 40L), rawText.lastOrNull()?.isWhitespace() == true)
        }

    /**
     * Timing payloads commonly omit whitespace because the timestamps are
     * attached to each token. Recover the original word boundaries from the
     * line text: a boundary after "hel" is not a space in "hello", while a
     * boundary after "hello" is a real space in "hello world".
     */
    private fun applySourceSpacing(words: List<TimedLyricWord>, sourceText: String): List<TimedLyricWord> {
        if (words.isEmpty()) return words
        var cursor = 0
        return words.mapIndexed { index, word ->
            val wordStart = sourceText.indexOf(word.text, cursor).takeIf { it >= 0 } ?: cursor
            val wordEnd = (wordStart + word.text.length).coerceAtMost(sourceText.length)
            val nextWord = words.getOrNull(index + 1)
            val nextStart = nextWord?.let { sourceText.indexOf(it.text, wordEnd).takeIf { found -> found >= 0 } }
            val hasSourceSpace = nextStart?.let { next ->
                sourceText.substring(wordEnd, next).any(Char::isWhitespace)
            }
            cursor = wordEnd
            word.copy(
                trailingSpace = index < words.lastIndex &&
                    (hasSourceSpace ?: word.trailingSpace),
            )
        }
    }

    private fun timestampMs(minutes: String, seconds: String, fraction: String): Long {
        val fractionMs = fraction.toLongOrNull()?.let { if (fraction.length == 2) it * 10 else it } ?: 0L
        return (minutes.toLongOrNull() ?: 0L) * 60_000L + (seconds.toLongOrNull() ?: 0L) * 1_000L + fractionMs
    }

    fun ttmlToEnhancedLrc(ttml: String): String = runCatching {
        val factory = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
            runCatching { setFeature("http://xml.org/sax/features/external-general-entities", false) }
            runCatching { setFeature("http://xml.org/sax/features/external-parameter-entities", false) }
            runCatching { setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false) }
            isExpandEntityReferences = false
        }
        val document = factory.newDocumentBuilder().parse(ttml.byteInputStream())
        val paragraphs = document.getElementsByTagNameNS("*", "p")
        buildString {
            for (paragraphIndex in 0 until paragraphs.length) {
                val paragraph = paragraphs.item(paragraphIndex) as? Element ?: continue
                val paragraphRole = ttmlAttribute(paragraph, "role")
                val paragraphStart = parseTime(ttmlAttribute(paragraph, "begin"))
                val agentValue = ttmlAttribute(paragraph, "agent")

                if (paragraphRole == "x-bg") {
                    val backgroundWords = mutableListOf<TimedLyricWord>()
                    collectTimedSpans(paragraph, backgroundWords, includeBackground = true)
                    val backgroundText = timedText(paragraph.textContent.orEmpty(), backgroundWords)
                    val backgroundStart = backgroundWords.minOfOrNull(TimedLyricWord::startMs)
                        ?: paragraphStart.takeIf { it > 0L }
                    if (backgroundStart != null && backgroundText.isNotBlank()) {
                        appendEnhancedLine(backgroundStart, "{bg}", backgroundText, backgroundWords)
                    }
                    continue
                }

                // Main-vocal spans and nested x-bg spans are two different
                // LyricsEntry items on Android. Never descend into x-bg while
                // collecting the paragraph's main words.
                val mainWords = mutableListOf<TimedLyricWord>()
                collectTimedSpans(paragraph, mainWords, includeBackground = false)
                val mainSourceText = textExcludingBackground(paragraph).trim()
                val mainText = timedText(mainSourceText, mainWords)
                val mainStart = paragraphStart.takeIf { it > 0L }
                    ?: mainWords.minOfOrNull(TimedLyricWord::startMs)
                if (mainStart != null && mainText.isNotBlank()) {
                    val marker = if (agentValue.isNotBlank()) "{agent:${agentValue.substringAfterLast('#')}}" else ""
                    appendEnhancedLine(mainStart, marker, mainText, mainWords)
                }

                // Nested background vocals get an independent LRC record,
                // exactly like BetterLyrics' Android TTMLParser.backgroundLines.
                val backgroundElements = mutableListOf<Element>()
                collectBackgroundElements(paragraph, backgroundElements)
                backgroundElements.forEach { background ->
                    val backgroundWords = mutableListOf<TimedLyricWord>()
                    collectTimedSpans(background, backgroundWords, includeBackground = true)
                    val backgroundText = timedText(background.textContent.orEmpty(), backgroundWords)
                    val backgroundStart = backgroundWords.minOfOrNull(TimedLyricWord::startMs)
                        ?: parseTime(ttmlAttribute(background, "begin")).takeIf { it > 0L }
                        ?: mainStart
                    if (backgroundStart != null && backgroundText.isNotBlank()) {
                        appendEnhancedLine(backgroundStart, "{bg}", backgroundText, backgroundWords)
                    }
                }
            }
        }
    }.getOrDefault("")

    private fun StringBuilder.appendEnhancedLine(
        startMs: Long,
        marker: String,
        text: String,
        words: List<TimedLyricWord>,
    ) {
        append(formatLrcTime(startMs)).append(marker).append(text.trim()).append('\n')
        if (words.isNotEmpty()) {
            append('<')
            words.forEachIndexed { index, word ->
                append(word.text).append(':').append(word.startMs / 1000.0).append(':').append(word.endMs / 1000.0)
                if (index < words.lastIndex) append('|')
            }
            append(">\n")
        }
    }

    private fun timedText(source: String, words: List<TimedLyricWord>): String {
        // TTML indentation/newlines are formatting, not lyric line breaks.
        val cleanSource = source.replace(Regex("\\s+"), " ").trim()
        if (words.isEmpty()) return cleanSource
        val wordText = words.joinToString("") { it.text + if (it.trailingSpace) " " else "" }.trim()
        return if (cleanSource.replace(Regex("\\s+"), "") == wordText.replace(Regex("\\s+"), "")) cleanSource else wordText
    }

    private fun collectTimedSpans(
        element: Element,
        target: MutableList<TimedLyricWord>,
        includeBackground: Boolean,
    ) {
        var child: Node? = element.firstChild
        while (child != null) {
            if (child is Element) {
                val name = child.localName ?: child.nodeName.substringAfterLast(':')
                val role = ttmlAttribute(child, "role")
                val excluded = role in setOf("x-translation", "x-roman") || (role == "x-bg" && !includeBackground)
                if (excluded) {
                    child = child.nextSibling
                    continue
                }
                if (name == "span") {
                    val begin = parseTime(ttmlAttribute(child, "begin"))
                    val end = parseTime(ttmlAttribute(child, "end"))
                    val text = child.textContent.orEmpty()
                    if (begin > 0L && end > begin && text.isNotBlank()) {
                        val next = child.nextSibling
                        val spaced = text.lastOrNull()?.isWhitespace() == true || next?.textContent?.firstOrNull()?.isWhitespace() == true
                        target += TimedLyricWord(text.trim(), begin, end, spaced)
                    } else {
                        collectTimedSpans(child, target, includeBackground)
                    }
                } else {
                    collectTimedSpans(child, target, includeBackground)
                }
            }
            child = child.nextSibling
        }
    }

    private fun collectBackgroundElements(element: Element, target: MutableList<Element>) {
        var child: Node? = element.firstChild
        while (child != null) {
            if (child is Element) {
                if (ttmlAttribute(child, "role") == "x-bg") target += child
                else collectBackgroundElements(child, target)
            }
            child = child.nextSibling
        }
    }

    private fun textExcludingBackground(element: Element): String = buildString {
        var child: Node? = element.firstChild
        while (child != null) {
            when (child) {
                is Element -> if (ttmlAttribute(child, "role") !in setOf("x-bg", "x-translation", "x-roman")) {
                    append(textExcludingBackground(child))
                }
                else -> if (child.nodeType == Node.TEXT_NODE) append(child.textContent.orEmpty())
            }
            child = child.nextSibling
        }
    }

    private fun ttmlAttribute(element: Element, name: String): String {
        element.getAttribute(name).takeIf(String::isNotBlank)?.let { return it }
        element.getAttribute("ttm:$name").takeIf(String::isNotBlank)?.let { return it }
        val attributes = element.attributes
        for (index in 0 until attributes.length) {
            val attribute = attributes.item(index)
            if ((attribute.localName ?: attribute.nodeName.substringAfterLast(':')) == name && attribute.nodeValue.isNotBlank()) {
                return attribute.nodeValue
            }
        }
        return ""
    }

    private fun parseTime(value: String): Long {
        val clean = value.trim()
        if (clean.isBlank()) return 0L
        if (clean.endsWith("ms")) return clean.dropLast(2).toDoubleOrNull()?.toLong() ?: 0L
        val seconds = when {
            clean.endsWith("s") -> clean.dropLast(1).toDoubleOrNull()
            clean.count { it == ':' } == 2 -> {
                val parts = clean.split(':')
                (parts[0].toDoubleOrNull() ?: 0.0) * 3600 + (parts[1].toDoubleOrNull() ?: 0.0) * 60 + (parts[2].toDoubleOrNull() ?: 0.0)
            }
            clean.contains(':') -> {
                val parts = clean.split(':')
                (parts[0].toDoubleOrNull() ?: 0.0) * 60 + (parts[1].toDoubleOrNull() ?: 0.0)
            }
            else -> clean.toDoubleOrNull()
        } ?: 0.0
        return (seconds * 1000).toLong()
    }

    private fun formatLrcTime(timeMs: Long): String = String.format(
        Locale.US,
        "[%02d:%02d.%02d]",
        timeMs / 60_000,
        (timeMs / 1_000) % 60,
        (timeMs % 1_000) / 10,
    )
}
