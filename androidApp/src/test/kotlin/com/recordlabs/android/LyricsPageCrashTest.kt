package com.recordlabs.android

import androidx.compose.ui.test.junit4.createComposeRule
import com.recordlabs.desktop.RecordLabsLyricsTestHarness
import com.recordlabs.desktop.data.Track
import com.recordlabs.desktop.data.WindowsStorage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [33], application = RecordLabsApp::class, qualifiers = "w411dp-h891dp-xxhdpi")
class LyricsPageCrashTest {
    @get:Rule val compose = createComposeRule()

    private fun render(id: String, lyrics: String) {
        val track = Track(id, "Test Song", "Test Artist", durationMs = 213_000L)
        val dir = File(WindowsStorage.directory, "lyrics-cache").apply { mkdirs() }
        File(dir, "${track.id.hashCode().toUInt().toString(16)}.lyrics")
            .writeText("provider:LRCLIB\nselected:true\nmatch-version:2\n$lyrics")
        compose.mainClock.autoAdvance = false
        compose.setContent { RecordLabsLyricsTestHarness(track, positionMs = 10_500L) }
        repeat(40) { compose.mainClock.advanceTimeByFrame() }
        repeat(20) { compose.mainClock.advanceTimeBy(100) }
    }

    private val lineLrc = (0 until 30).joinToString("\n") { i -> "[00:%02d.00]Line number %d of the lyrics".format(i * 3, i) }

    // Word-timed (enhanced LRC) lyrics: the active line is drawn letter by letter.
    private val wordLrc = (0 until 20).joinToString("\n") { i ->
        val s = i * 3
        "[00:%02d.00]<00:%02d.00>Never <00:%02d.50>gonna <00:%02d.00>give <00:%02d.50>you <00:%02d.90>up".format(s, s, s, s + 1, s + 1, s + 1)
    }

    private val japaneseLrc = (0 until 20).joinToString("\n") { i -> "[00:%02d.00]夜に駆ける あの日の空 カタカナ %d".format(i * 3, i) }

    @Test @Config(qualifiers = "w760dp-h1030dp-mdpi") fun tabletPortraitWords() = render("aaaaaaaaaa6", wordLrc)

    @Test fun portraitLines() = render("aaaaaaaaaa1", lineLrc)
    @Test fun portraitWords() = render("aaaaaaaaaa2", wordLrc)
    @Test fun portraitJapanese() = render("aaaaaaaaaa3", japaneseLrc)
    @Test @Config(qualifiers = "w891dp-h411dp-xxhdpi") fun landscapeWords() = render("aaaaaaaaaa4", wordLrc)
    @Test @Config(qualifiers = "w891dp-h411dp-xxhdpi") fun landscapeJapanese() = render("aaaaaaaaaa5", japaneseLrc)
}
