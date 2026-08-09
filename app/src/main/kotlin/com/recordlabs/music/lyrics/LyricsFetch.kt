package com.recordlabs.music.lyrics

import android.content.Context
import com.recordlabs.music.db.MusicDatabase
import com.recordlabs.music.db.entities.LyricsEntity
import com.recordlabs.music.di.LyricsHelperEntryPoint
import com.recordlabs.music.models.MediaMetadata
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.flow.first

/** Fetches and persists lyrics once so they are available offline as soon as a track is used. */
suspend fun fetchLyricsIfMissing(
    context: Context,
    database: MusicDatabase,
    mediaMetadata: MediaMetadata,
) {
    if (database.lyrics(mediaMetadata.id).first() != null) return

    val entryPoint =
        EntryPointAccessors.fromApplication(
            context.applicationContext,
            LyricsHelperEntryPoint::class.java,
        )
    val fetched = entryPoint.lyricsHelper().getLyrics(mediaMetadata)
    database.query {
        upsert(LyricsEntity(mediaMetadata.id, fetched.lyrics, fetched.provider))
    }
}
