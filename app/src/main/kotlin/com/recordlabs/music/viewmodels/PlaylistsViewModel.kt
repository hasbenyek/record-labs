/**
 * Record Labs Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

@file:OptIn(ExperimentalCoroutinesApi::class)

package com.recordlabs.music.viewmodels

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.recordlabs.music.constants.AddToPlaylistSortDescendingKey
import com.recordlabs.music.constants.AddToPlaylistSortTypeKey
import com.recordlabs.music.constants.PlaylistSortType
import com.recordlabs.music.db.MusicDatabase
import com.recordlabs.music.extensions.toEnum
import com.recordlabs.music.utils.SyncUtils
import com.recordlabs.music.utils.dataStore
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

@HiltViewModel
class PlaylistsViewModel
@Inject
constructor(
    @ApplicationContext context: Context,
    database: MusicDatabase,
    private val syncUtils: SyncUtils,
) : ViewModel() {
    val allPlaylists =
        context.dataStore.data
            .map {
                it[AddToPlaylistSortTypeKey].toEnum(PlaylistSortType.CREATE_DATE) to (it[AddToPlaylistSortDescendingKey]
                    ?: true)
            }.distinctUntilChanged()
            .flatMapLatest { (sortType, descending) ->
                database.playlists(sortType, descending)
            }.stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    // Suspend function that waits for sync to complete
    suspend fun sync() {
        syncUtils.syncSavedPlaylists()
    }
}
