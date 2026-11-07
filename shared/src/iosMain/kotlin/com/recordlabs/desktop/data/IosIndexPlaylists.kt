@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlinx.cinterop.BetaInteropApi::class)

package com.recordlabs.desktop.data

import com.recordlabs.desktop.platform.concurrentMapOf
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.usePinned
import platform.AVFoundation.AVAssetResourceLoader
import platform.AVFoundation.AVAssetResourceLoaderDelegateProtocol
import platform.AVFoundation.AVAssetResourceLoadingRequest
import platform.AVFoundation.AVURLAsset
import platform.AVFoundation.resourceLoader
import platform.Foundation.NSData
import platform.Foundation.NSURL
import platform.Foundation.dataWithBytes
import platform.darwin.NSObject
import platform.darwin.dispatch_queue_create

/**
 * iPhone: hands AVPlayer the HLS playlists the app writes for YouTube's audio streams
 * (see [StreamIndex]). A playlist has no address of its own, so the asset is given a
 * made-up one ("recordlabs-hls://"); AVPlayer asks this loader for it, and fetches the
 * pieces it lists (plain https byte ranges of the stream) itself.
 */
internal class IosIndexPlaylists : NSObject(), AVAssetResourceLoaderDelegateProtocol {
    private val playlists = concurrentMapOf<String, String>()
    private val queue = dispatch_queue_create("recordlabs.hls-playlists", null)
    private var counter = 0

    /** An asset that plays [playlist] (the pieces of [streamUrl]); [options] as for the stream itself. */
    fun asset(streamUrl: String, playlist: String, options: Map<Any?, Any?>?): AVURLAsset? {
        counter += 1
        if (playlists.size > 16) playlists.clear()
        val address = "recordlabs-hls://song/${counter}.m3u8"
        val link = NSURL.URLWithString(address) ?: return null
        playlists[address] = playlist
        val asset = AVURLAsset(uRL = link, options = options)
        asset.resourceLoader.setDelegate(this, queue)
        AppDiagnostics.log("playback", "playing as HLS pieces (${playlist.lineSequence().count { it.startsWith("#EXTINF") }})")
        return asset
    }

    override fun resourceLoader(
        resourceLoader: AVAssetResourceLoader,
        shouldWaitForLoadingOfRequestedResource: AVAssetResourceLoadingRequest,
    ): Boolean {
        val request = shouldWaitForLoadingOfRequestedResource
        val address = request.request.URL?.absoluteString ?: return false
        val playlist = playlists[address] ?: return false
        val bytes = playlist.encodeToByteArray()
        val data = bytes.usePinned { NSData.dataWithBytes(it.addressOf(0), bytes.size.convert()) }
        request.dataRequest?.respondWithData(data)
        request.finishLoading()
        return true
    }
}

/** The one loader (an asset keeps only a weak reference to its loader's delegate). */
internal val sharedIndexPlaylists: IosIndexPlaylists by lazy { IosIndexPlaylists() }
