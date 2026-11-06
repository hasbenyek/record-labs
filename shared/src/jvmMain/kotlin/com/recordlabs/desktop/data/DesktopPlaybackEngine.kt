package com.recordlabs.desktop.data

/** Desktop player: the Windows native helper, or VLC on Linux. */
actual fun createPlaybackEngine(): PlaybackEngine = WindowsMediaPlayer()
