package com.recordlabs.desktop.data

import com.recordlabs.desktop.data.WindowsRichLyrics.appendEnhancedLine
import com.recordlabs.desktop.data.WindowsRichLyrics.parseTime
import com.recordlabs.desktop.data.WindowsRichLyrics.timedText
import org.w3c.dom.Element
import org.w3c.dom.Node
import javax.xml.parsers.DocumentBuilderFactory

// The desktop and Android read TTML with the Java XML parser (unchanged).
internal actual fun ttmlToEnhancedLrcImpl(ttml: String): String = runCatching {
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

