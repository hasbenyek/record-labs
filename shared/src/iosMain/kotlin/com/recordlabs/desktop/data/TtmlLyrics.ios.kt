package com.recordlabs.desktop.data

import com.recordlabs.desktop.data.WindowsRichLyrics.appendEnhancedLine
import com.recordlabs.desktop.data.WindowsRichLyrics.parseTime
import com.recordlabs.desktop.data.WindowsRichLyrics.timedText

// iOS reads TTML with a small XML reader (MiniXml below) that offers the few DOM
// calls the desktop code uses, so the lyrics come out the same.
internal actual fun ttmlToEnhancedLrcImpl(ttml: String): String = runCatching {
    val document = MiniXml.parse(ttml)
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



internal open class Node {
    var nextSibling: Node? = null
    open val textContent: String? get() = null
    open val nodeType: Short get() = 0
    companion object {
        const val ELEMENT_NODE: Short = 1
        const val TEXT_NODE: Short = 3
    }
}

internal class TextNode(val data: String) : Node() {
    override val textContent: String get() = data
    override val nodeType: Short get() = TEXT_NODE
}

internal class Attr(val nodeName: String, val nodeValue: String) {
    val localName: String? get() = nodeName.substringAfterLast(':')
}

internal class AttrList(private val items: List<Attr>) {
    val length: Int get() = items.size
    fun item(index: Int): Attr = items[index]
}

internal class NodeList(private val items: List<Element>) {
    val length: Int get() = items.size
    fun item(index: Int): Element? = items.getOrNull(index)
}

internal class Element(val nodeName: String) : Node() {
    val localName: String? get() = nodeName.substringAfterLast(':')
    private val attrs = LinkedHashMap<String, String>()
    val children = ArrayList<Node>()
    val firstChild: Node? get() = children.firstOrNull()
    val attributes: AttrList get() = AttrList(attrs.map { Attr(it.key, it.value) })
    override val nodeType: Short get() = ELEMENT_NODE
    override val textContent: String get() = buildString { children.forEach { append(it.textContent.orEmpty()) } }

    fun getAttribute(name: String): String = attrs[name].orEmpty()
    fun setAttribute(name: String, value: String) { attrs[name] = value }

    fun add(child: Node) {
        children.lastOrNull()?.nextSibling = child
        children += child
    }

    fun getElementsByTagNameNS(namespace: String, localName: String): NodeList {
        val found = ArrayList<Element>()
        fun walk(element: Element) {
            element.children.forEach { child ->
                if (child is Element) {
                    if (child.localName == localName) found += child
                    walk(child)
                }
            }
        }
        walk(this)
        return NodeList(found)
    }
}

/** Enough XML for TTML: elements, attributes, text, entities, comments, CDATA. */
internal object MiniXml {
    fun parse(xml: String): Element {
        val root = Element("#document")
        val stack = ArrayList<Element>().apply { add(root) }
        var i = 0
        val text = StringBuilder()
        fun flushText() {
            if (text.isNotEmpty()) { stack.last().add(TextNode(decode(text.toString()))); text.clear() }
        }
        while (i < xml.length) {
            val c = xml[i]
            if (c != '<') { text.append(c); i++; continue }
            when {
                xml.startsWith("<!--", i) -> { i = xml.indexOf("-->", i).let { if (it < 0) xml.length else it + 3 } }
                xml.startsWith("<![CDATA[", i) -> {
                    val end = xml.indexOf("]]>", i).let { if (it < 0) xml.length else it }
                    text.append(xml, i + 9, end)
                    i = (end + 3).coerceAtMost(xml.length)
                }
                xml.startsWith("<?", i) || xml.startsWith("<!", i) -> { i = xml.indexOf('>', i).let { if (it < 0) xml.length else it + 1 } }
                xml.startsWith("</", i) -> {
                    flushText()
                    i = xml.indexOf('>', i).let { if (it < 0) xml.length else it + 1 }
                    if (stack.size > 1) stack.removeAt(stack.lastIndex)
                }
                else -> {
                    flushText()
                    var j = i + 1
                    while (j < xml.length && !xml[j].isWhitespace() && xml[j] != '>' && xml[j] != '/') j++
                    val element = Element(xml.substring(i + 1, j))
                    // attributes
                    while (j < xml.length && xml[j] != '>' && !(xml[j] == '/' && xml.getOrNull(j + 1) == '>')) {
                        if (xml[j].isWhitespace()) { j++; continue }
                        var k = j
                        while (k < xml.length && xml[k] != '=' && !xml[k].isWhitespace() && xml[k] != '>' && xml[k] != '/') k++
                        val name = xml.substring(j, k)
                        while (k < xml.length && xml[k].isWhitespace()) k++
                        if (k < xml.length && xml[k] == '=') {
                            k++
                            while (k < xml.length && xml[k].isWhitespace()) k++
                            val quote = xml.getOrNull(k)
                            if (quote == '"' || quote == '\'') {
                                val end = xml.indexOf(quote, k + 1).let { if (it < 0) xml.length else it }
                                element.setAttribute(name, decode(xml.substring(k + 1, end)))
                                k = end + 1
                            }
                        } else if (name.isNotEmpty()) element.setAttribute(name, "")
                        j = if (k > j) k else j + 1
                    }
                    val selfClosing = j < xml.length && xml[j] == '/'
                    i = xml.indexOf('>', j).let { if (it < 0) xml.length else it + 1 }
                    stack.last().add(element)
                    if (!selfClosing) stack += element
                }
            }
        }
        flushText()
        return root
    }

    private val entity = Regex("&(#x[0-9a-fA-F]+|#[0-9]+|amp|lt|gt|quot|apos);")

    private fun decode(value: String): String {
        if (!value.contains('&')) return value
        return entity.replace(value) { m ->
            val v = m.groupValues[1]
            when {
                v == "amp" -> "&"; v == "lt" -> "<"; v == "gt" -> ">"; v == "quot" -> "\""; v == "apos" -> "'"
                v.startsWith("#x") -> v.drop(2).toIntOrNull(16)?.let { codeText(it) } ?: m.value
                else -> v.drop(1).toIntOrNull()?.let { codeText(it) } ?: m.value
            }
        }
    }

    private fun codeText(code: Int): String = when {
        code < 0x10000 -> code.toChar().toString()
        code <= 0x10FFFF -> { val r = code - 0x10000; charArrayOf(((r shr 10) + 0xD800).toChar(), ((r and 0x3FF) + 0xDC00).toChar()).concatToString() }
        else -> ""
    }
}
