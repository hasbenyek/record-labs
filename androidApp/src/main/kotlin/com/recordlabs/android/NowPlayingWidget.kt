package com.recordlabs.android

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.os.Handler
import android.os.Looper
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.widget.RemoteViews
import com.recordlabs.desktop.NowPlayingFeed
import com.recordlabs.desktop.NowPlayingInfo
import com.recordlabs.desktop.data.AndroidPlaybackEngine
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/**
 * Home-screen widget: the song playing (or the last one played), its artwork, up to
 * three lyric lines (the one being sung first) and previous / play-pause / next.
 *
 * The app reports the song through [NowPlayingFeed] ([NowPlayingWidgetUpdater]); the
 * last state is kept on disk, so the widget still shows the last song after the app
 * is closed. Its buttons go to the app's player when it is open, and open the app
 * (which then plays) when it is not.
 */
class NowPlayingWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, appWidgetIds: IntArray) {
        NowPlayingWidgetUpdater.render(context, appWidgetIds, full = true)
        // A widget was just added: the app (if open) sends the lyrics too now.
        Handler(Looper.getMainLooper()).post { NowPlayingFeed.requestRefresh() }
    }

    override fun onReceive(context: Context, intent: Intent) {
        val command = when (intent.action) {
            ACTION_PLAY_PAUSE -> if (NowPlayingWidgetUpdater.saved(context).isPlaying) "pause" else "play"
            ACTION_NEXT -> "next"
            ACTION_PREVIOUS -> "previous"
            else -> null
        }
        if (command == null) {
            super.onReceive(context, intent)
            return
        }
        val handler = AndroidPlaybackEngine.commandHandler()
        if (handler != null) {
            handler(command)
        } else {
            // The app is closed: open it (it plays once its last song is back), and make
            // the buttons open it directly from now on.
            runCatching { context.startActivity(NowPlayingWidgetUpdater.openAppIntent(context, command)) }
            NowPlayingWidgetUpdater.render(context)
        }
    }

    companion object {
        const val ACTION_PLAY_PAUSE = "com.recordlabs.widget.PLAY_PAUSE"
        const val ACTION_NEXT = "com.recordlabs.widget.NEXT"
        const val ACTION_PREVIOUS = "com.recordlabs.widget.PREVIOUS"
        /** On MainActivity's intent: the widget command to run once the app is open. */
        const val EXTRA_COMMAND = "com.recordlabs.widget.COMMAND"
    }
}

/** Draws the widget from the app's reports and keeps the last one on disk. */
object NowPlayingWidgetUpdater {
    private const val PREFS = "now_playing_widget"
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor { Thread(it, "now-playing-widget").apply { isDaemon = true } }

    /** The artwork of [artworkFor] (rounded) and its colour, kept while that song lasts. */
    @Volatile private var artworkFor: String? = null
    @Volatile private var artwork: Bitmap? = null
    @Volatile private var artworkColor: Int = DEFAULT_COLOR
    @Volatile private var loading: String? = null
    /** What the last full drawing had (artwork, buttons' targets): the same again -> text only. */
    @Volatile private var lastFull: String? = null

    private const val DEFAULT_COLOR = 0xFF2A2A2E.toInt()

    data class Saved(
        val title: String?,
        val artist: String,
        val artworkUrl: String?,
        val isPlaying: Boolean,
        val lyrics: List<String>,
        val firstLineActive: Boolean,
    )

    /** Connects the app's player to the widget (called once, when the app starts). */
    fun install(app: Context) {
        val context = app.applicationContext
        NowPlayingFeed.lyricsWanted = { widgetIds(context).isNotEmpty() }
        NowPlayingFeed.sink = { info -> publish(context, info) }
    }

    fun saved(context: Context): Saved {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return Saved(
            title = prefs.getString("title", null),
            artist = prefs.getString("artist", "").orEmpty(),
            artworkUrl = prefs.getString("artwork", null),
            isPlaying = prefs.getBoolean("playing", false),
            lyrics = prefs.getString("lyrics", "").orEmpty().split('\u0001').filter(String::isNotBlank),
            firstLineActive = prefs.getBoolean("firstActive", false),
        )
    }

    private fun widgetIds(context: Context): IntArray =
        runCatching {
            AppWidgetManager.getInstance(context).getAppWidgetIds(ComponentName(context, NowPlayingWidget::class.java))
        }.getOrDefault(IntArray(0))

    fun publish(context: Context, info: NowPlayingInfo) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("trackId", info.trackId)
            .putString("title", info.title)
            .putString("artist", info.artist)
            .putString("artwork", info.artworkUrl)
            .putBoolean("playing", info.isPlaying)
            .putString("lyrics", info.lyrics.joinToString("\u0001"))
            .putBoolean("firstActive", info.firstLineActive)
            .apply()
        if (widgetIds(context).isEmpty()) return
        render(context)
    }

    /** The app is closing (swiped away): the widget shows the song as paused. */
    fun markStopped(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean("playing", false).commit()
        render(context, closed = true)
    }

    fun openAppIntent(context: Context, command: String?): Intent =
        Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .apply { if (command != null) putExtra(NowPlayingWidget.EXTRA_COMMAND, command) }

    /** [closed]: the app is closing (its player still answers for a moment). */
    fun render(context: Context, ids: IntArray = widgetIds(context), full: Boolean = false, closed: Boolean = false) {
        if (ids.isEmpty()) return
        if (full) lastFull = null
        val state = saved(context)
        val appOpen = !closed && AndroidPlaybackEngine.commandHandler() != null
        val playing = state.isPlaying && appOpen
        val views = RemoteViews(context.packageName, R.layout.widget_now_playing)

        views.setTextViewText(R.id.widget_title, state.title ?: "Record Labs")
        views.setTextViewText(R.id.widget_artist, if (state.title == null) "Nothing played yet" else state.artist)
        views.setTextViewText(R.id.widget_lyrics, lyricsText(state))
        views.setImageViewResource(R.id.widget_play, if (playing) R.drawable.ic_widget_pause else R.drawable.ic_widget_play)

        val url = state.artworkUrl
        val bitmap = artwork?.takeIf { artworkFor == url && url != null }
        // Lyric lines change every few seconds: then only the text and the play button are
        // sent (the picture is not sent again each time).
        val fullKey = "${ids.joinToString()}|$url|${bitmap != null}|$appOpen"
        if (fullKey == lastFull) {
            runCatching { AppWidgetManager.getInstance(context).partiallyUpdateAppWidget(ids, views) }
            return
        }
        if (bitmap != null) {
            views.setImageViewBitmap(R.id.widget_artwork, bitmap)
            views.setInt(R.id.widget_bg, "setColorFilter", artworkColor)
        } else {
            views.setImageViewResource(R.id.widget_artwork, R.drawable.widget_art_placeholder)
            views.setInt(R.id.widget_bg, "setColorFilter", DEFAULT_COLOR)
            if (url != null) loadArtwork(context, url)
        }

        views.setOnClickPendingIntent(R.id.widget_root, activity(context, 10, openAppIntent(context, null)))
        if (appOpen) {
            views.setOnClickPendingIntent(R.id.widget_play, broadcast(context, NowPlayingWidget.ACTION_PLAY_PAUSE))
            views.setOnClickPendingIntent(R.id.widget_next, broadcast(context, NowPlayingWidget.ACTION_NEXT))
            views.setOnClickPendingIntent(R.id.widget_previous, broadcast(context, NowPlayingWidget.ACTION_PREVIOUS))
        } else {
            views.setOnClickPendingIntent(R.id.widget_play, activity(context, 11, openAppIntent(context, "play")))
            views.setOnClickPendingIntent(R.id.widget_next, activity(context, 12, openAppIntent(context, "next")))
            views.setOnClickPendingIntent(R.id.widget_previous, activity(context, 13, openAppIntent(context, "previous")))
        }
        runCatching { AppWidgetManager.getInstance(context).updateAppWidget(ids, views) }
        lastFull = fullKey
    }

    /** The line being sung in white, the next ones dimmed. */
    private fun lyricsText(state: Saved): CharSequence {
        if (state.lyrics.isEmpty()) return ""
        val text = SpannableStringBuilder()
        state.lyrics.take(3).forEachIndexed { index, line ->
            if (index > 0) text.append('\n')
            val start = text.length
            text.append(line)
            val color = if (index == 0 && state.firstLineActive) Color.WHITE else Color.argb(150, 255, 255, 255)
            text.setSpan(ForegroundColorSpan(color), start, text.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        return text
    }

    private fun activity(context: Context, code: Int, intent: Intent): PendingIntent =
        PendingIntent.getActivity(context, code, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

    private fun broadcast(context: Context, action: String): PendingIntent =
        PendingIntent.getBroadcast(
            context, action.hashCode(),
            Intent(context, NowPlayingWidget::class.java).setAction(action),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

    private fun loadArtwork(context: Context, url: String) {
        if (loading == url) return
        loading = url
        worker.execute {
            val loaded = runCatching { decode(context, url) }.getOrNull()
            main.post {
                if (loading == url) loading = null
                if (loaded != null && saved(context).artworkUrl == url) {
                    artwork = rounded(loaded)
                    artworkColor = cardColor(loaded)
                    artworkFor = url
                    render(context)
                }
            }
        }
    }

    /** The artwork, at most 256 px (widgets have a small size limit for pictures). */
    private fun decode(context: Context, url: String): Bitmap? {
        val cache = File(context.cacheDir, "widget-artwork-${url.hashCode()}")
        val bytes = if (cache.isFile) cache.readBytes() else {
            val data = when {
                url.startsWith("http", ignoreCase = true) -> {
                    val connection = URL(url).openConnection() as HttpURLConnection
                    connection.connectTimeout = 8_000
                    connection.readTimeout = 8_000
                    try { connection.inputStream.use { it.readBytes() } } finally { connection.disconnect() }
                }
                url.startsWith("file:") -> File(java.net.URI(url)).readBytes()
                File(url).isFile -> File(url).readBytes()
                else -> return null
            }
            // Only the last song's picture is kept.
            context.cacheDir.listFiles { file -> file.name.startsWith("widget-artwork-") }?.forEach { it.delete() }
            runCatching { cache.writeBytes(data) }
            data
        }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= 256) sample *= 2
        val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return null
        // Square, centred (as the app shows it).
        val side = minOf(decoded.width, decoded.height)
        val square = Bitmap.createBitmap(decoded, (decoded.width - side) / 2, (decoded.height - side) / 2, side, side)
        return if (side > 256) Bitmap.createScaledBitmap(square, 256, 256, true) else square
    }

    /** Rounded corners like the app's artwork (12 dp on the 56 dp picture). */
    private fun rounded(source: Bitmap): Bitmap {
        val output = Bitmap.createBitmap(source.width, source.height, Bitmap.Config.ARGB_8888)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { shader = BitmapShader(source, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP) }
        val radius = source.width * 12f / 56f
        Canvas(output).drawRoundRect(RectF(0f, 0f, source.width.toFloat(), source.height.toFloat()), radius, radius, paint)
        return output
    }

    /** The artwork's average colour, darkened so white text stays readable. */
    private fun cardColor(source: Bitmap): Int {
        val pixel = Bitmap.createScaledBitmap(source, 1, 1, true).getPixel(0, 0)
        val hsv = FloatArray(3)
        Color.colorToHSV(pixel, hsv)
        hsv[1] = (hsv[1] * 0.85f).coerceAtMost(0.75f)
        hsv[2] = (hsv[2] * 0.55f).coerceIn(0.14f, 0.38f)
        return Color.HSVToColor(hsv)
    }
}
