package com.recordlabs.desktop.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

class WindowsRichLyricsTest {
    @Test
    fun parsesEnhancedWordTimings() {
        val source = """
            [00:10.00]{agent:v1}hello world
            <hello:10.0:10.5|world:10.5:11.2>
        """.trimIndent()
        val line = WindowsRichLyrics.parse(source).single()
        assertEquals(10_000L, line.startMs)
        assertEquals("hello world", line.text)
        assertEquals(2, line.words.size)
        assertEquals(10_500L, line.words[1].startMs)
    }

    @Test
    fun convertsWordTimedTtml() {
        val ttml = """
            <tt xmlns="http://www.w3.org/ns/ttml" xmlns:ttm="http://www.w3.org/ns/ttml#metadata">
              <body><div><p begin="1.0" end="2.0" ttm:agent="v1">
                <span begin="1.0" end="1.4">hello </span><span begin="1.4" end="2.0">world</span>
              </p></div></body>
            </tt>
        """.trimIndent()
        val encoded = WindowsRichLyrics.ttmlToEnhancedLrc(ttml)
        val line = WindowsRichLyrics.parse(encoded).single()
        assertEquals("hello world", line.text)
        assertTrue(line.words.isNotEmpty())
        assertEquals(1_000L, line.words.first().startMs)
    }

    @Test
    fun separatesNestedTtmlBackgroundFromMainVocal() {
        val ttml = """
            <tt xmlns="http://www.w3.org/ns/ttml" xmlns:ttm="http://www.w3.org/ns/ttml#metadata">
              <body><div><p begin="1.0" end="3.0" ttm:agent="v2">
                <span begin="1.0" end="1.5">Serve </span>
                <span begin="1.5" end="2.0">sass</span>
                <span begin="2.0" end="2.8" ttm:role="x-bg">
                  <span begin="2.0" end="2.8">(Yes)</span>
                </span>
              </p></div></body>
            </tt>
        """.trimIndent()

        val lines = WindowsRichLyrics.parse(WindowsRichLyrics.ttmlToEnhancedLrc(ttml))

        assertEquals(2, lines.size)
        assertEquals("Serve sass", lines[0].text)
        assertEquals(false, lines[0].isBackground)
        assertEquals("Yes", lines[1].text)
        assertEquals("Yes", lines[1].words.single().text)
        assertEquals(true, lines[1].isBackground)
    }

    @Test
    fun keepsBetterLyricsBackgroundRunAsIndependentBackgroundRows() {
        val source = """
            [00:25.59]{agent:v2}Serve a lil' sass
            <Serve:25.59:25.80|a:25.80:25.95|lil':25.95:26.10|sass:26.10:26.30>
            [00:26.49]{bg}(Yes)
            <(Yes):26.49:26.82>
            [00:28.40](Okay! Okay!)
            <(Okay!:28.40:28.90|Okay!):28.90:29.40>
            [00:30.30]{agent:v1}Got the front row
            <Got:30.30:30.55|the:30.55:30.75|front:30.75:31.00|row:31.00:31.30>
        """.trimIndent()

        val lines = WindowsRichLyrics.parse(source)

        assertEquals(listOf(false, true, true, false), lines.map { it.isBackground })
        assertEquals("Yes", lines[1].text)
        assertEquals("Okay! Okay!", lines[2].text)
        assertEquals("Okay! Okay!", lines[2].words.joinToString(" ") { it.text })
        assertEquals("v2", lines[1].agent)
        assertEquals("v1", lines[3].agent)
    }

    @Test
    fun betterLyricsFallbackProducesWordTiming() = runBlocking {
        val raw = WindowsLyricsProviderRegistry.getLyrics(
            Track(
                id = "test-oh-well",
                title = "oh well",
                artist = "Ariana Grande",
                durationMs = 196_000L,
            ),
            forceRefresh = true,
        )
        val lines = WindowsRichLyrics.parse(raw)
        assertTrue(lines.isNotEmpty())
        assertTrue(lines.any { it.words.isNotEmpty() })
    }
}
