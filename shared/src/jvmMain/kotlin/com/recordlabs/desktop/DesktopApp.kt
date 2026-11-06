package com.recordlabs.desktop

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import kotlinx.coroutines.launch
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.PointerIcon

import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import kotlinx.coroutines.launch
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.Button
import androidx.compose.material.ButtonDefaults
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.CompositingStrategy
import com.recordlabs.desktop.ui.Heroicons
import com.recordlabs.desktop.ui.CustomPlayerIcons
import androidx.compose.animation.animateColorAsState


import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.material.AlertDialog as MaterialAlertDialog
import androidx.compose.material.Divider
import androidx.compose.material.darkColors
import androidx.compose.material.DropdownMenu
import androidx.compose.material.DropdownMenuItem
import androidx.compose.material.FloatingActionButton
import androidx.compose.material.Icon
import androidx.compose.material.IconButton
import androidx.compose.material.MaterialTheme
import androidx.compose.material.OutlinedButton
import androidx.compose.material.RadioButton
import androidx.compose.material.Scaffold
import androidx.compose.material.Slider
import androidx.compose.material.SliderDefaults
import androidx.compose.material.Surface
import androidx.compose.material.Switch
import androidx.compose.material.Text


import androidx.compose.material.TextField
import androidx.compose.material.TextFieldDefaults
import androidx.compose.material.TextButton
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Album
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.HourglassEmpty
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Group
import androidx.compose.material.icons.filled.Headset
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.QueueMusic
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material.icons.filled.Speed
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.produceState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.MutableState
import androidx.compose.ui.Alignment
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.zIndex
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.asComposeCanvas
import androidx.compose.ui.text.drawText
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.asComposeRenderEffect

import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.layout
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import com.recordlabs.desktop.data.WindowsMusicRepository
import com.recordlabs.desktop.data.Track
import com.recordlabs.desktop.data.catalogKindFor
import com.recordlabs.desktop.data.playsAsIs
import com.recordlabs.desktop.data.HomeFeed
import com.recordlabs.desktop.data.HomeChip
import com.recordlabs.desktop.data.HomeItem
import com.recordlabs.desktop.data.HomeShelf
import com.recordlabs.desktop.data.HomeShelfLayout
import com.recordlabs.desktop.data.SearchHistoryStore
import com.recordlabs.desktop.data.SearchResults
import com.recordlabs.desktop.data.SearchSummaryShelf
import com.recordlabs.desktop.data.SearchTopResult
import com.recordlabs.desktop.data.rankSongsForQuery
import com.recordlabs.desktop.data.searchNameMatches
import com.recordlabs.desktop.data.CatalogItem
import com.recordlabs.desktop.data.CatalogPageData
import com.recordlabs.desktop.data.WindowsMediaPlayer
import com.recordlabs.desktop.data.WindowsLibraryStore
import com.recordlabs.desktop.data.WindowsLibrarySnapshot
import com.recordlabs.desktop.data.mostPlayed
import com.recordlabs.desktop.data.WindowsListeningTracker
import com.recordlabs.desktop.data.WindowsArtistLogoRepository
import androidx.compose.ui.graphics.toComposeImageBitmap
import com.recordlabs.desktop.data.WindowsPlaylist
import com.recordlabs.desktop.data.WindowsAudioCache
import com.recordlabs.desktop.data.WindowsAnimatedArtworkRepository
import com.recordlabs.desktop.data.WindowsOfflineArtworkCache
import com.recordlabs.desktop.data.WindowsLocalMusicLibrary
import coil3.compose.AsyncImage
import kotlinx.coroutines.delay
import kotlinx.coroutines.async
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt
import kotlin.math.sqrt
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import java.io.File
import java.net.URI

import androidx.compose.ui.res.painterResource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import com.recordlabs.desktop.data.PlayerColorExtractor
import com.recordlabs.desktop.data.WindowsLyricsProviderRegistry
import com.recordlabs.desktop.data.LyricsTranslationSettings
import com.recordlabs.desktop.data.WindowsLyricsTranslator
import com.recordlabs.desktop.data.WindowsPreferences
import com.recordlabs.desktop.data.WindowsListenTogetherClient
import com.recordlabs.desktop.data.ListenTogetherConnection
import com.recordlabs.desktop.data.ListenTogetherUser
import com.recordlabs.desktop.data.RichLyricLine
import com.recordlabs.desktop.data.LyricsCandidate
import com.recordlabs.desktop.data.LyricsResult
import com.recordlabs.desktop.data.WindowsRichLyrics
import com.recordlabs.desktop.data.WindowsRomanizer
import com.recordlabs.desktop.data.WindowsRecordLabsConnect
import com.recordlabs.desktop.data.ConnectPlaybackState
import com.recordlabs.desktop.data.ConnectDevice
import com.recordlabs.desktop.data.WindowsConnectDiscoveryStatus
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Radio
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material.icons.filled.MotionPhotosOn
import androidx.compose.material.icons.filled.MotionPhotosOff
import androidx.compose.material.icons.filled.VolumeOff
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material.icons.filled.PictureInPicture
import androidx.compose.material.icons.filled.PictureInPictureAlt
import androidx.compose.material.CircularProgressIndicator
import androidx.compose.material.Shapes

/**
 * A windowed-borderless fullscreen controller for Windows multi-monitor setups.
 *
 * Compose's [WindowPlacement.Fullscreen] maps to a native fullscreen mode that
 * Windows is allowed to minimize when another monitor receives focus. Keeping
 * the same AWT frame alive as a borderless window avoids that focus-loss
 * behaviour and also gives us one explicit source of truth for entering and
 * leaving fullscreen.
 */
internal class DesktopFullscreenController(
    private val frame: java.awt.Frame,
) {
    private var active = false
    private var previousStyle: Int? = null
    private var previousPlacement: com.sun.jna.platform.win32.WinUser.WINDOWPLACEMENT? = null

    private val user32 get() = com.sun.jna.platform.win32.User32.INSTANCE

    fun setFullscreen(enabled: Boolean) = onAwtThread {
        if (enabled == active) return@onAwtThread
        if (!DesktopUiScale.isWindows()) {
            val handled = runCatching { LinuxX11Window.requestFullscreen(frame, enabled) }.getOrDefault(false)
            if (!handled) {
                val device = frame.graphicsConfiguration?.device
                if (device?.isFullScreenSupported == true) {
                    device.fullScreenWindow = if (enabled) frame else null
                }
            }
            active = enabled
            return@onAwtThread
        }
        if (enabled) enter() else leave()
    }

    private fun enter() {
        val hwnd = hwnd()
        previousStyle = user32.GetWindowLong(hwnd, GWL_STYLE)
        previousPlacement = com.sun.jna.platform.win32.WinUser.WINDOWPLACEMENT().also { placement ->
            placement.length = placement.size()
            check(user32.GetWindowPlacement(hwnd, placement).booleanValue())
        }
        // GraphicsConfiguration bounds are expressed in Java's DPI-aware
        // coordinate space. Feeding Win32's physical pixels back into AWT made
        // high-DPI secondary monitors shrink or shift the window.
        val monitorBounds = java.awt.Rectangle(frame.graphicsConfiguration.bounds)
        frame.extendedState = java.awt.Frame.NORMAL
        frame.bounds = monitorBounds
        val borderlessStyle = previousStyle!! and FULLSCREEN_DECORATIONS.inv()
        user32.SetWindowLong(hwnd, GWL_STYLE, borderlessStyle)
        check(
            user32.SetWindowPos(
                hwnd,
                null,
                0,
                0,
                0,
                0,
                SWP_NOMOVE or SWP_NOSIZE or SWP_NOZORDER or SWP_NOOWNERZORDER or SWP_FRAMECHANGED,
            ),
        )
        active = true
    }

    private fun leave() {
        val hwnd = hwnd()
        previousStyle?.let { user32.SetWindowLong(hwnd, GWL_STYLE, it) }
        previousPlacement?.let { user32.SetWindowPlacement(hwnd, it) }
        user32.SetWindowPos(
            hwnd,
            null,
            0,
            0,
            0,
            0,
            SWP_NOMOVE or SWP_NOSIZE or SWP_NOZORDER or SWP_NOOWNERZORDER or SWP_FRAMECHANGED,
        )
        active = false
    }

    private fun hwnd(): com.sun.jna.platform.win32.WinDef.HWND =
        com.sun.jna.platform.win32.WinDef.HWND(com.sun.jna.Native.getWindowPointer(frame))

    private inline fun onAwtThread(crossinline action: () -> Unit) {
        if (java.awt.EventQueue.isDispatchThread()) action()
        else java.awt.EventQueue.invokeAndWait { action() }
    }

    private companion object {
        const val GWL_STYLE = -16
        const val WS_CAPTION = 0x00C00000
        const val WS_THICKFRAME = 0x00040000
        const val WS_MINIMIZEBOX = 0x00020000
        const val WS_MAXIMIZEBOX = 0x00010000
        const val WS_SYSMENU = 0x00080000
        const val FULLSCREEN_DECORATIONS =
            WS_CAPTION or WS_THICKFRAME or WS_MINIMIZEBOX or WS_MAXIMIZEBOX or WS_SYSMENU
        const val SWP_NOSIZE = 0x0001
        const val SWP_NOMOVE = 0x0002
        const val SWP_NOZORDER = 0x0004
        const val SWP_NOOWNERZORDER = 0x0200
        const val SWP_FRAMECHANGED = 0x0020
    }
}

/** The desktop app (Windows and Linux); the desktop project's main() calls this. */
fun runDesktopApp(args: Array<String>) {
    // Set Java2D's Linux scale before Compose or AWT initializes its display toolkit.
    DesktopUiScale.configureBeforeAwt()
    if (args.contains("--self-test-display")) {
        DesktopUiScale.printSelfTest()
        return
    }
    // Packaging check: play a tone through the real audio path, no window.
    if ("--self-test-audio" in args) kotlin.system.exitProcess(runAudioSelfTest())
    if ("--self-test-login" in args) kotlin.system.exitProcess(runLoginSelfTest())
    if ("--self-test-connect" in args) kotlin.system.exitProcess(runConnectSelfTest())
    // Load JavaFX media natives before the off-screen video decoder starts.
    ensureJavaFxMediaNatives()
    val shutdownAction = {
        runCatching { com.recordlabs.desktop.data.WindowsAudioProxy.stop() }
        runCatching { WindowsLyricsProviderRegistry.close() }
    }
    Runtime.getRuntime().addShutdownHook(Thread {
        // Windows shutdown / sign-out ends the app without a close request:
        // still write the latest playback position.
        runCatching { BackgroundPreferenceWriter.flushOnClose() }
        // The play in progress still counts in the listening stats.
        runCatching { com.recordlabs.desktop.data.WindowsListeningTracker.flushOnExit() }
        shutdownAction()
    })
    application {
        val savedWindow = remember { WindowsPreferences.windowSession() }
        val logicalScreenSize = remember {
            runCatching { java.awt.Toolkit.getDefaultToolkit().screenSize }.getOrNull()
        }
        val safeWindowWidth = (savedWindow?.width ?: 1180f).coerceAtMost(
            (logicalScreenSize?.width?.times(.96f) ?: Float.MAX_VALUE).coerceAtLeast(720f),
        )
        val safeWindowHeight = (savedWindow?.height ?: 760f).coerceAtMost(
            (logicalScreenSize?.height?.times(.90f) ?: Float.MAX_VALUE).coerceAtLeast(520f),
        )
        val initialPlacement = when (savedWindow?.placement) {
            "Floating" -> WindowPlacement.Floating
            "Maximized" -> WindowPlacement.Maximized
            else -> WindowPlacement.Maximized
        }
        val windowState = rememberWindowState(
            placement = initialPlacement,
            width = safeWindowWidth.dp,
            height = safeWindowHeight.dp,
        )
        var isFullscreen by remember { mutableStateOf(false) }
        val toggleFullscreen = { isFullscreen = !isFullscreen }
        LaunchedEffect(windowState, isFullscreen) {
            // Written on a background thread, and only when it changed.
            var savedSession: Triple<String, Float, Float>? = null
            while (true) {
                if (!isFullscreen) {
                    val session = Triple(
                        windowState.placement.name,
                        windowState.size.width.value,
                        windowState.size.height.value,
                    )
                    if (session != savedSession) {
                        savedSession = session
                        BackgroundPreferenceWriter.later("windowSession") {
                            WindowsPreferences.saveWindowSession(session.first, session.second, session.third)
                        }
                    }
                }
                delay(600L)
            }
        }
        val closeRecordLabs: () -> Unit = {
            if (!isFullscreen) {
                val placement = windowState.placement.name
                val width = windowState.size.width.value
                val height = windowState.size.height.value
                BackgroundPreferenceWriter.now("windowSession") {
                    WindowsPreferences.saveWindowSession(placement, width, height)
                }
            }
            BackgroundPreferenceWriter.flushOnClose()
            shutdownAction()
            // A downloaded update installs once Record Labs has closed.
            runCatching { com.recordlabs.desktop.data.AppUpdater.installOnExit() }
            exitApplication()
            kotlin.system.exitProcess(0)
        }
        androidx.compose.runtime.SideEffect { com.recordlabs.desktop.data.AppUpdater.exitApp = closeRecordLabs }
        LaunchedEffect(Unit) {
            // Looks for a new release shortly after start, then every 6 hours.
            withContext(Dispatchers.IO) { com.recordlabs.desktop.data.AppUpdater.cleanUp() }
            delay(8_000L)
            while (true) {
                com.recordlabs.desktop.data.AppUpdater.check(manual = false)
                delay(6L * 60L * 60L * 1000L)
            }
        }
        Window(
            onCloseRequest = closeRecordLabs,
            title = "Record Labs",
            icon = painterResource("recordlabs.ico"),
            state = windowState,
            // Keep native window decoration stable. Toggling this property at
            // runtime recreates a Compose Desktop window on Windows and was
            // the reason F11 could appear to close the application.
            undecorated = false,
            // Fullscreen should not become a permanent topmost window; that
            // can interfere with resize/focus transitions on multi-monitor
            // Windows setups.
            alwaysOnTop = false,
        ) {
            val fullscreenController = remember(window) { DesktopFullscreenController(window) }
            val currentToggleFullscreen by rememberUpdatedState(toggleFullscreen)
            val currentlyFullscreen by rememberUpdatedState(isFullscreen)

            LaunchedEffect(window) {
                if (DesktopUiScale.isWindows()) {
                    try {
                        val hwnd = com.sun.jna.platform.win32.WinDef.HWND(com.sun.jna.Native.getWindowPointer(window))
                        val useDarkMode = com.sun.jna.ptr.IntByReference(1)
                        val result = RecordLabsDwmapi.INSTANCE.DwmSetWindowAttribute(hwnd, 20, useDarkMode, 4)
                        if (result != 0) {
                            RecordLabsDwmapi.INSTANCE.DwmSetWindowAttribute(hwnd, 19, useDarkMode, 4)
                        }
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }
            }

            LaunchedEffect(fullscreenController, isFullscreen) {
                fullscreenController.setFullscreen(isFullscreen)
            }
            DisposableEffect(window, fullscreenController) {
                val dispatcher = java.awt.KeyEventDispatcher { event ->
                    if (event.id != java.awt.event.KeyEvent.KEY_PRESSED) return@KeyEventDispatcher false
                    when {
                        event.keyCode == java.awt.event.KeyEvent.VK_F11 -> {
                            currentToggleFullscreen()
                            true
                        }
                        event.keyCode == java.awt.event.KeyEvent.VK_ESCAPE && currentlyFullscreen -> {
                            currentToggleFullscreen()
                            true
                        }
                        else -> false
                    }
                }
                val desktopStateListener = java.awt.event.WindowStateListener { event ->
                    val isAppVisible = event.newState and java.awt.Frame.ICONIFIED == 0
                    setAnimatedArtworkAppVisible(isAppVisible)
                    if (isAppVisible) {
                        FloatingLyricsState.isPipMinimized = true
                    } else if (FloatingLyricsState.isEnabled) {
                        FloatingLyricsState.isPipMinimized = false
                    }
                }
                val desktopFocusListener = object : java.awt.event.WindowFocusListener {
                    override fun windowGainedFocus(e: java.awt.event.WindowEvent?) {
                        // User switched to / focused on Record Labs -> hide floating lyrics
                        FloatingLyricsState.isPipMinimized = true
                    }
                    override fun windowLostFocus(e: java.awt.event.WindowEvent?) {
                        // User switched to another app (Chrome, game, Discord, etc.)
                        val opposite = e?.oppositeWindow
                        if (opposite != null && (opposite === window || window.ownedWindows.contains(opposite))) {
                            return
                        }
                        if (FloatingLyricsState.isEnabled) {
                            // PiP is still enabled -> show floating lyrics
                            FloatingLyricsState.isPipMinimized = false
                        }
                    }
                }
                FloatingLyricsState.requestMinimizeDesktop = {
                    windowState.isMinimized = true
                    runCatching {
                        window.extendedState = window.extendedState or java.awt.Frame.ICONIFIED
                    }
                }
                FloatingLyricsState.requestRestoreDesktop = {
                    windowState.isMinimized = false
                    runCatching {
                        window.extendedState = window.extendedState and java.awt.Frame.ICONIFIED.inv()
                        window.toFront()
                        window.requestFocus()
                    }
                }
                java.awt.KeyboardFocusManager.getCurrentKeyboardFocusManager().addKeyEventDispatcher(dispatcher)
                window.addWindowStateListener(desktopStateListener)
                window.addWindowFocusListener(desktopFocusListener)
                val initialAppVisible = window.extendedState and java.awt.Frame.ICONIFIED == 0
                setAnimatedArtworkAppVisible(initialAppVisible)
                if (initialAppVisible) {
                    FloatingLyricsState.isPipMinimized = true
                } else if (FloatingLyricsState.isEnabled) {
                    FloatingLyricsState.isPipMinimized = false
                }
                onDispose {
                    java.awt.KeyboardFocusManager.getCurrentKeyboardFocusManager().removeKeyEventDispatcher(dispatcher)
                    window.removeWindowStateListener(desktopStateListener)
                    window.removeWindowFocusListener(desktopFocusListener)
                    setAnimatedArtworkAppVisible(false)
                    fullscreenController.setFullscreen(false)
                    FloatingLyricsState.requestMinimizeDesktop = null
                    FloatingLyricsState.requestRestoreDesktop = null
                }
            }
            val densityScale = DesktopUiScale.effectiveDensityScale()
            val baseDensity = androidx.compose.ui.platform.LocalDensity.current
            val customDensity = remember(baseDensity, densityScale) {
                androidx.compose.ui.unit.Density(
                    density = baseDensity.density * densityScale,
                    fontScale = baseDensity.fontScale * densityScale
                )
            }
            androidx.compose.runtime.CompositionLocalProvider(
                androidx.compose.ui.platform.LocalDensity provides customDensity
            ) {
                Box(Modifier.fillMaxSize()) {
                    RecordLabsWindow(
                        isFullscreen = isFullscreen,
                        onToggleFullscreen = toggleFullscreen,
                    )
                    // A music video opened full screen from the player/lyrics artwork.
                    MusicVideoFullscreenOverlay(
                        currentTrackId = FloatingLyricsState.currentTrack?.id,
                        isWindowFullscreen = isFullscreen,
                        onWindowFullscreen = { isFullscreen = it },
                    )
                }
            }
        }
        if (FloatingLyricsState.isEnabled && FloatingLyricsState.currentTrack != null) {
            FloatingLyricsWindow()
        }
    }
}

private val blurShader = org.jetbrains.skia.RuntimeEffect.makeForShader("""
    uniform shader normal;
    uniform shader blurred;
    uniform float headerHeight;
    uniform float footerHeight;
    uniform float viewHeight;

    vec4 main(vec2 coord) {
        vec4 cNormal = normal.eval(coord);
        vec4 cBlurred = blurred.eval(coord);
        
        float blurAmount = 0.0;
        // Top (behind the search box): fully blurred at the very top, easing to
        // sharp a little below the box, so there is no visible edge.
        if (headerHeight > 0.5) {
            blurAmount = 1.0 - smoothstep(headerHeight * 0.2, headerHeight * 1.15, coord.y);
        }
        // Bottom edge (behind the portrait navigation): blurrier toward the bottom.
        float footerStart = viewHeight - footerHeight;
        if (footerHeight > 0.5) {
            blurAmount = max(blurAmount, smoothstep(footerStart, viewHeight - footerHeight * 0.1, coord.y));
        }
        
        return mix(cNormal, cBlurred, blurAmount);
    }
""")

@Composable
actual fun Modifier.progressiveHeaderBlur(
    compact: Boolean,
    /** Blurred band at the bottom (behind the portrait navigation), 0 for none. */
    footerHeight: androidx.compose.ui.unit.Dp,
    headerHeight: androidx.compose.ui.unit.Dp,
    /** 0..1: how far the header has slid away (the top band shrinks with it). */
    headerHide: () -> Float,
): Modifier {
    if (compact) return this
    val density = androidx.compose.ui.platform.LocalDensity.current.density
    // The bottom band depends on the page's height, so the effect is rebuilt
    // when that changes (kept here: [height, effect]).
    val cache = remember(density, footerHeight, headerHeight) { arrayOfNulls<Any>(2) }
    val cacheKey = remember(density, footerHeight, headerHeight) { FloatArray(2) { -1f } }
    fun effectFor(height: Float, hide: Float): androidx.compose.ui.graphics.RenderEffect {
        if (cacheKey[0] == height && cacheKey[1] == hide) return cache[1] as androidx.compose.ui.graphics.RenderEffect
        val builder = org.jetbrains.skia.RuntimeShaderBuilder(blurShader)
        builder.uniform("headerHeight", headerHeight.value * density * (1f - hide))
        builder.uniform("footerHeight", footerHeight.value * density)
        builder.uniform("viewHeight", height)
        val normalFilter = org.jetbrains.skia.ImageFilter.makeOffset(0f, 0f, null, null)
        val blurFilter = org.jetbrains.skia.ImageFilter.makeBlur(30f, 30f, org.jetbrains.skia.FilterTileMode.CLAMP, null, null)
        val combinedFilter = org.jetbrains.skia.ImageFilter.makeRuntimeShader(
            builder,
            arrayOf("normal", "blurred"),
            arrayOf(normalFilter, blurFilter)
        )
        return combinedFilter.asComposeRenderEffect().also { cacheKey[0] = height; cacheKey[1] = hide; cache[1] = it }
    }
    // The blur spreads past the page's edge; it is cut off on the right so it
    // never shows behind the queue panel (left, top and bottom stay as before).
    val rightEdgeClip = remember {
        androidx.compose.foundation.shape.GenericShape { size, _ ->
            addRect(androidx.compose.ui.geometry.Rect(-100_000f, -100_000f, size.width, size.height + 100_000f))
        }
    }
    return this.graphicsLayer {
        renderEffect = effectFor(size.height, headerHide().coerceIn(0f, 1f))
        shape = rightEdgeClip
        clip = true
    }
}
interface RecordLabsDwmapi : com.sun.jna.win32.StdCallLibrary {
    companion object {
        val INSTANCE: RecordLabsDwmapi = com.sun.jna.Native.load("dwmapi", RecordLabsDwmapi::class.java, com.sun.jna.win32.W32APIOptions.DEFAULT_OPTIONS)
    }
    fun DwmSetWindowAttribute(hwnd: com.sun.jna.platform.win32.WinDef.HWND, attr: Int, attrValue: com.sun.jna.ptr.IntByReference, attrSize: Int): Int
}