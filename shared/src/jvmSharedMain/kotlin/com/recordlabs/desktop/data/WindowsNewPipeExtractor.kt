package com.recordlabs.desktop.data

import okhttp3.OkHttpClient
import okhttp3.Callback
import okhttp3.Call
import okhttp3.RequestBody.Companion.toRequestBody
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.downloader.CancellableCall
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Request
import org.schabi.newpipe.extractor.downloader.Response
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException
import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import java.io.IOException

/** Desktop equivalent of the NewPipe fallback used by the Android InnerTube module. */
object WindowsNewPipeExtractor {
    private val client = OkHttpClient.Builder().build()

    init {
        NewPipe.init(DesktopDownloader)
    }

    fun audioStream(videoId: String): String? = runCatching {
        val info = StreamInfo.getInfo(NewPipe.getService(0), "https://www.youtube.com/watch?v=$videoId")
        // Media Foundation handles YouTube's Opus/WebM timeline reliably on modern Windows.
        val streams = info.audioStreams
        // Settings > Player > Audio quality "Low": the smallest Opus stream.
        if (AppSettings.audioQuality.value == "low") {
            streams.filter { it.format?.mimeType?.contains("audio/webm") == true }.minByOrNull { it.averageBitrate }?.content
                ?.let { return@runCatching it }
        }
        streams.firstOrNull { it.itagItem?.id == 251 }?.content
            ?: streams.firstOrNull { it.format?.mimeType?.contains("audio/webm") == true }?.content
            ?: streams.maxByOrNull { it.averageBitrate }?.content
    }.getOrNull()

    /**
     * YouTube's related-stream section for a concrete video. Unlike search results this keeps
     * the recommendation/radio context selected by YouTube for the seed track.
     */
    fun relatedTracks(videoId: String): List<Track> = runCatching {
        StreamInfo.getInfo(NewPipe.getService(0), "https://www.youtube.com/watch?v=$videoId")
            .relatedStreams
            .filterIsInstance<StreamInfoItem>()
            .mapNotNull { item ->
                val id = item.url.videoIdOrNull() ?: return@mapNotNull null
                Track(
                    id = id,
                    title = item.name.orEmpty(),
                    artist = item.uploaderName.orEmpty().ifBlank { "YouTube Music" },
                    artworkUrl = item.thumbnailUrl,
                    durationMs = item.duration.coerceAtLeast(0L) * 1_000L,
                ).takeIf { it.title.isNotBlank() && it.id != videoId }
            }
            .distinctBy { it.id }
    }.getOrDefault(emptyList())

    private fun String.videoIdOrNull(): String? = when {
        contains("watch?v=") -> substringAfter("watch?v=").substringBefore('&').takeIf { it.isNotBlank() }
        contains("youtu.be/") -> substringAfter("youtu.be/").substringBefore('?').substringBefore('/').takeIf { it.isNotBlank() }
        else -> null
    }

    private object DesktopDownloader : Downloader() {
        @Throws(IOException::class, ReCaptchaException::class)
        override fun execute(request: Request): Response {
            val builder = okhttp3.Request.Builder()
                .url(request.url())
                .method(request.httpMethod(), request.dataToSend()?.toRequestBody())
                .header("User-Agent", USER_AGENT)
            request.headers().forEach { (name, values) ->
                values.forEach { value -> builder.addHeader(name, value) }
            }
            return client.newCall(builder.build()).execute().use(::toExtractorResponse)
        }

        override fun executeAsync(request: Request, callback: AsyncCallback?): CancellableCall {
            val builder = okhttp3.Request.Builder()
                .url(request.url())
                .method(request.httpMethod(), request.dataToSend()?.toRequestBody())
                .header("User-Agent", USER_AGENT)
            request.headers().forEach { (name, values) -> values.forEach { builder.addHeader(name, it) } }
            val call = client.newCall(builder.build())
            val cancellable = CancellableCall(call)
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, exception: IOException) {
                    callback?.onError(exception)
                    cancellable.setFinished()
                }

                override fun onResponse(call: Call, response: okhttp3.Response) {
                    try {
                        response.use { callback?.onSuccess(toExtractorResponse(it)) }
                    } catch (exception: Exception) {
                        callback?.onError(exception)
                    } finally {
                        cancellable.setFinished()
                    }
                }
            })
            return cancellable
        }

        private fun toExtractorResponse(response: okhttp3.Response): Response {
            if (response.code == 429) throw ReCaptchaException("reCaptcha challenge", response.request.url.toString())
            val body = response.body?.string().orEmpty()
            return Response(
                response.code,
                response.message,
                response.headers.toMultimap(),
                body,
                body.toByteArray(),
                response.request.url.toString(),
            )
        }
    }

    private const val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/131 Safari/537.36"
}
