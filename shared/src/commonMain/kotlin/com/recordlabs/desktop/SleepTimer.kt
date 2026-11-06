package com.recordlabs.desktop

import com.recordlabs.desktop.platform.*

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material.Icon
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * Sleep timer (⋯ menu of the music player and the lyrics page): playback pauses after
 * the chosen time, or when the song playing ends. One timer for the whole app; the
 * player (App in Main.kt) waits for [deadlineMs] and checks [endOfTrack] when a song ends.
 */
internal object SleepTimer {
    /** When playback pauses (System.currentTimeMillis), or null. */
    var deadlineMs by mutableStateOf<Long?>(null)
        private set
    /** Pause when the song playing now (or the one playing when it ends) finishes. */
    var endOfTrack by mutableStateOf(false)
        private set
    /** The chosen length in minutes (0 for "end of song"), for the menu's check mark. */
    var chosenMinutes by mutableStateOf<Int?>(null)
        private set

    val active: Boolean get() = deadlineMs != null || endOfTrack

    /** Lengths offered in the menu, in minutes. */
    val choices = listOf(5, 10, 15, 30, 45, 60, 90, 120)

    fun start(minutes: Int) {
        endOfTrack = false
        chosenMinutes = minutes
        deadlineMs = currentTimeMillis() + minutes * 60_000L
    }

    fun startEndOfTrack() {
        deadlineMs = null
        chosenMinutes = 0
        endOfTrack = true
    }

    fun cancel() {
        deadlineMs = null
        endOfTrack = false
        chosenMinutes = null
    }

    /** The song ended: true (and the timer is done) when playback should stop here. */
    fun consumeTrackEnd(): Boolean {
        if (!endOfTrack) return false
        cancel()
        return true
    }

    /** The deadline passed: the timer is done. */
    fun fired() = cancel()

    fun label(minutes: Int): String = when {
        minutes < 60 -> "$minutes minutes"
        minutes % 60 == 0 -> if (minutes == 60) "1 hour" else "${minutes / 60} hours"
        else -> "${minutes / 60} h ${minutes % 60} min"
    }

    fun remainingText(nowMs: Long): String? {
        if (endOfTrack) return "End of song"
        val deadline = deadlineMs ?: return null
        val seconds = ((deadline - nowMs).coerceAtLeast(0L) + 999L) / 1000L
        val h = seconds / 3600
        val m = (seconds % 3600) / 60
        val s = seconds % 60
        return if (h > 0) "%d:%02d:%02d".fmt(h, m, s) else "%d:%02d".fmt(m, s)
    }
}

/**
 * ⋯ menu entry: "Sleep timer" with the time left; tapping it shows the choices
 * (Off, the lengths, End of song) right under it in the menu.
 */
@Composable
internal fun SleepTimerMenuItem() {
    var expanded by remember { mutableStateOf(false) }
    var now by remember { mutableLongStateOf(currentTimeMillis()) }
    val deadline = SleepTimer.deadlineMs
    LaunchedEffect(deadline) {
        while (deadline != null) {
            now = currentTimeMillis()
            kotlinx.coroutines.delay(1_000L - now % 1_000L)
        }
    }
    val remaining = SleepTimer.remainingText(now)
    IosMenuRow(
        "Sleep timer",
        null,
        onClick = { expanded = !expanded },
        subtitle = if (remaining != null) "Pauses playback" else "Pause playback after a while",
        value = remaining ?: "Off",
    ) {
        Icon(
            if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
            null,
            tint = Color.White,
            modifier = Modifier.size(20.dp),
        )
    }
    if (expanded) {
        val check: (Boolean) -> (@Composable () -> Unit) = { selected ->
            {
                if (selected) Icon(Icons.Default.Check, null, tint = Color.White, modifier = Modifier.size(18.dp))
                else Box(Modifier.size(18.dp))
            }
        }
        IosMenuRow("Off", null, onClick = { SleepTimer.cancel(); expanded = false }, trailing = check(!SleepTimer.active))
        SleepTimer.choices.forEach { minutes ->
            IosMenuRow(
                SleepTimer.label(minutes),
                null,
                onClick = { SleepTimer.start(minutes); expanded = false },
                trailing = check(SleepTimer.chosenMinutes == minutes && SleepTimer.deadlineMs != null),
            )
        }
        IosMenuRow("End of song", null, onClick = { SleepTimer.startEndOfTrack(); expanded = false }, trailing = check(SleepTimer.endOfTrack))
    }
}
