package com.recordlabs.desktop.data

import kotlin.test.Test
import kotlin.test.assertEquals

class TrackCounterpartTest {
    private val video = Track("video", "Song", "Artist", musicVideoType = "MUSIC_VIDEO_TYPE_OMV")
    private val song = Track("song", "Song", "Artist", musicVideoType = "MUSIC_VIDEO_TYPE_ATV")

    @Test
    fun mapsIntoTheVideoTimeline() {
        // The video has a 9 s intro before the music starts.
        val toVideo = TrackCounterpart(video, listOf(CounterpartSegment(0, 9_000, 200_000)))
        assertEquals(9_000, toVideo.mapPosition(0))
        assertEquals(69_000, toVideo.mapPosition(60_000))
        // Past the matched part: its end.
        assertEquals(209_000, toVideo.mapPosition(250_000))
        val back = toVideo.reversed(song)
        assertEquals(song, back.track)
        assertEquals(60_000, back.mapPosition(69_000))
        // In the intro (before any matched part): the start of the music.
        assertEquals(0, back.mapPosition(3_000))
    }

    @Test
    fun withoutSegmentsKeepsThePosition() {
        assertEquals(42_000, TrackCounterpart(video).mapPosition(42_000))
    }
}
