package com.recordlabs.innertube.pages

import com.recordlabs.innertube.models.Album
import com.recordlabs.innertube.models.AlbumItem
import com.recordlabs.innertube.models.Artist
import com.recordlabs.innertube.models.ArtistItem
import com.recordlabs.innertube.models.MusicResponsiveListItemRenderer
import com.recordlabs.innertube.models.MusicTwoRowItemRenderer
import com.recordlabs.innertube.models.PlaylistItem
import com.recordlabs.innertube.models.SongItem
import com.recordlabs.innertube.models.YTItem
import com.recordlabs.innertube.models.oddElements
import com.recordlabs.innertube.utils.parseTime

data class LibraryAlbumsPage(
    val albums: List<AlbumItem>,
    val continuation: String?,
) {
    companion object {
        fun fromMusicTwoRowItemRenderer(renderer: MusicTwoRowItemRenderer): AlbumItem? {
            return AlbumItem(
                        browseId = renderer.navigationEndpoint.browseEndpoint?.browseId ?: return null,
                        playlistId = renderer.thumbnailOverlay?.musicItemThumbnailOverlayRenderer?.content
                            ?.musicPlayButtonRenderer?.playNavigationEndpoint
                            ?.watchPlaylistEndpoint?.playlistId ?: return null,
                        title = renderer.title.runs?.firstOrNull()?.text ?: return null,
                        artists = null,
                        year = renderer.subtitle?.runs?.lastOrNull()?.text?.toIntOrNull(),
                        thumbnail = renderer.thumbnailRenderer.musicThumbnailRenderer?.getThumbnailUrl() ?: return null,
                        explicit = renderer.subtitleBadges?.find {
                            it.musicInlineBadgeRenderer?.icon?.iconType == "MUSIC_EXPLICIT_BADGE"
                        } != null
                    )
        }
    }
}
