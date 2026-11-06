package com.recordlabs.desktop

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.window.WindowDraggableArea
import androidx.compose.material.Icon
import androidx.compose.material.IconButton
import androidx.compose.material.Surface
import androidx.compose.material.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.TextLayoutResult
import com.recordlabs.desktop.data.RichLyricLine
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.text.drawText
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.WindowScope
import androidx.compose.ui.window.rememberWindowState
import com.recordlabs.desktop.data.Track
import com.recordlabs.desktop.data.WindowsLyricsProviderRegistry
import com.recordlabs.desktop.data.WindowsPreferences
import com.recordlabs.desktop.data.WindowsRichLyrics
import java.awt.GraphicsEnvironment
import java.awt.Rectangle
import java.awt.Toolkit
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlin.math.roundToInt


internal fun getAllUsableScreenBounds(): List<Rectangle> {
    return runCatching {
        val ge = GraphicsEnvironment.getLocalGraphicsEnvironment()
        ge.screenDevices.mapNotNull { dev ->
            val gc = dev.defaultConfiguration ?: return@mapNotNull null
            val bounds = gc.bounds
            val insets = runCatching { Toolkit.getDefaultToolkit().getScreenInsets(gc) }.getOrNull()
            if (insets != null) {
                Rectangle(
                    bounds.x + insets.left,
                    bounds.y + insets.top,
                    (bounds.width - insets.left - insets.right).coerceAtLeast(100),
                    (bounds.height - insets.top - insets.bottom).coerceAtLeast(100),
                )
            } else {
                bounds
            }
        }
    }.getOrDefault(emptyList()).ifEmpty {
        val d = Toolkit.getDefaultToolkit().screenSize
        listOf(Rectangle(0, 0, d.width, (d.height - 48).coerceAtLeast(100)))
    }
}

internal fun getPrimaryUsableScreenBounds(): Rectangle {
    return runCatching {
        val ge = GraphicsEnvironment.getLocalGraphicsEnvironment()
        val dev = ge.defaultScreenDevice
        val gc = dev.defaultConfiguration
        val bounds = gc.bounds
        val insets = runCatching { Toolkit.getDefaultToolkit().getScreenInsets(gc) }.getOrNull()
        if (insets != null) {
            Rectangle(
                bounds.x + insets.left,
                bounds.y + insets.top,
                (bounds.width - insets.left - insets.right).coerceAtLeast(100),
                (bounds.height - insets.top - insets.bottom).coerceAtLeast(100),
            )
        } else {
            bounds
        }
    }.getOrElse {
        val d = Toolkit.getDefaultToolkit().screenSize
        Rectangle(0, 0, d.width, (d.height - 48).coerceAtLeast(100))
    }
}

internal fun clampPositionToScreens(x: Int, y: Int, width: Int, height: Int): Pair<Int, Int> {
    val usableScreens = getAllUsableScreenBounds()
    val windowRect = Rectangle(x, y, width, height)

    var bestScreen: Rectangle? = null
    var maxIntersectionArea = 0

    for (screen in usableScreens) {
        val inter = windowRect.intersection(screen)
        if (!inter.isEmpty) {
            val area = inter.width * inter.height
            if (area > maxIntersectionArea) {
                maxIntersectionArea = area
                bestScreen = screen
            }
        }
    }

    return if (bestScreen != null && maxIntersectionArea >= 3200) {
        val minX = bestScreen.x
        val maxX = (bestScreen.x + bestScreen.width - width).coerceAtLeast(minX)
        val minY = bestScreen.y
        val maxY = (bestScreen.y + bestScreen.height - height).coerceAtLeast(minY)
        Pair(x.coerceIn(minX, maxX), y.coerceIn(minY, maxY))
    } else {
        val primary = getPrimaryUsableScreenBounds()
        val safeX = primary.x + ((primary.width - width) / 2).coerceAtLeast(0)
        val safeY = primary.y + (primary.height - height - 30).coerceAtLeast(0)
        Pair(safeX, safeY)
    }
}

internal fun getScreenTopologySignature(): String {
    return runCatching {
        val ge = GraphicsEnvironment.getLocalGraphicsEnvironment()
        ge.screenDevices.joinToString(";") { dev ->
            val b = dev.defaultConfiguration?.bounds ?: Rectangle(0, 0, 0, 0)
            "${dev.iDstring}:${b.x},${b.y},${b.width},${b.height}"
        }
    }.getOrDefault("")
}

internal fun ensureWindowTranslucency(window: java.awt.Window) {
    runCatching {
        val gc = window.graphicsConfiguration ?: return
        if (!gc.isTranslucencyCapable()) {
            val dev = gc.device ?: return
            val transGc = dev.configurations.firstOrNull { it.isTranslucencyCapable() }
            if (transGc != null) {
                java.awt.EventQueue.invokeLater {
                    runCatching {
                        // Toggling from opaque (alpha 255) to transparent (alpha 0) triggers
                        // java.awt.Window.setBackground's internal translucency configuration switch,
                        // re-acquiring the 32-bit ARGB visual on the new X11/Xwayland screen device.
                        window.background = java.awt.Color(0, 0, 0, 255)
                        window.background = java.awt.Color(0, 0, 0, 0)
                        (window as? javax.swing.JFrame)?.rootPane?.isOpaque = false
                        (window as? javax.swing.JFrame)?.contentPane?.let {
                            (it as? javax.swing.JComponent)?.isOpaque = false
                            it.background = java.awt.Color(0, 0, 0, 0)
                        }
                        window.revalidate()
                        window.repaint()
                    }
                }
            }
        }
    }
}

internal fun recoverFloatingWindow(window: java.awt.Window, windowState: androidx.compose.ui.window.WindowState) {
    runCatching {
        val currentW = window.width.takeIf { it > 100 } ?: windowState.size.width.value.roundToInt()
        val currentH = window.height.takeIf { it > 50 } ?: windowState.size.height.value.roundToInt()
        val currentX = window.x
        val currentY = window.y

        val (clampedX, clampedY) = clampPositionToScreens(currentX, currentY, currentW, currentH)

        if (clampedX != currentX || clampedY != currentY) {
            window.setLocation(clampedX, clampedY)
            windowState.position = WindowPosition(clampedX.dp, clampedY.dp)
            WindowsPreferences.saveFloatingLyricsPosition(clampedX.toFloat(), clampedY.toFloat())
        }

        if (com.recordlabs.desktop.data.Platform.isWindows) {
            window.isAlwaysOnTop = false
            window.isAlwaysOnTop = true
            window.toFront()

            window.background = java.awt.Color(0, 0, 0, 0)

            // 1-pixel size nudge forces Skiko / SkiaLayer to re-create its Direct3D/DWM swapchain
            window.setSize(currentW + 1, currentH)
            window.setSize(currentW, currentH)

            window.revalidate()
            window.repaint()
        } else {
            ensureWindowTranslucency(window)
            runCatching {
                (window as? javax.swing.JFrame)?.rootPane?.isOpaque = false
                (window as? javax.swing.JFrame)?.contentPane?.let {
                    (it as? javax.swing.JComponent)?.isOpaque = false
                    it.background = java.awt.Color(0, 0, 0, 0)
                }
            }
            window.isAlwaysOnTop = false
            window.isAlwaysOnTop = true
            window.toFront()
        }
    }
}

@Composable
fun FloatingLyricsWindow(
    onClose: () -> Unit = {
        FloatingLyricsState.setPipEnabled(false)
    }
) {
    val track = FloatingLyricsState.currentTrack ?: return
    val savedPos = remember { WindowsPreferences.floatingLyricsPosition() }
    // A size below the minimum (a window manager squeezed the window once and
    // it was saved) opened the lyrics as a thin line: start over at the default.
    val savedSize = remember {
        WindowsPreferences.floatingLyricsSize()
            ?.takeIf { it.first >= PIP_MIN_WIDTH && it.second >= PIP_MIN_HEIGHT }
    }

    val initialWidth = savedSize?.first?.dp ?: 720.dp
    val initialHeight = savedSize?.second?.dp ?: 175.dp

    val initialPos = remember {
        val w = initialWidth.value.roundToInt()
        val h = initialHeight.value.roundToInt()
        if (savedPos != null) {
            val (safeX, safeY) = clampPositionToScreens(savedPos.first.roundToInt(), savedPos.second.roundToInt(), w, h)
            WindowPosition(safeX.dp, safeY.dp)
        } else {
            val primary = getPrimaryUsableScreenBounds()
            val defaultX = (primary.x + ((primary.width - w) / 2)).coerceAtLeast(primary.x)
            val defaultY = (primary.y + (primary.height - h - 30)).coerceAtLeast(primary.y)
            WindowPosition(defaultX.dp, defaultY.dp)
        }
    }

    if (LinuxLyricsOverlay.isAvailable) {
        // Wayland desktop with the layer shell: a native, see-through overlay.
        val currentOnClose by androidx.compose.runtime.rememberUpdatedState(onClose)
        DisposableEffect(Unit) {
            LinuxLyricsOverlay.onGeometryChanged = { x, y, w, h ->
                WindowsPreferences.saveFloatingLyricsPosition(x.toFloat(), y.toFloat())
                WindowsPreferences.saveFloatingLyricsSize(w.toFloat(), h.toFloat())
            }
            // First time, the overlay opens on the screen showing the app.
            LinuxLyricsOverlay.preferredPoint = {
                java.awt.Frame.getFrames().firstOrNull { it.isShowing && it.title == "Record Labs" }
                    ?.let { java.awt.Point(it.x + it.width / 2, it.y + it.height / 2) }
            }
            LinuxLyricsOverlay.start(
                content = { OverlayLyrics(onClose = { currentOnClose() }) },
                x = savedPos?.first?.roundToInt() ?: -1,
                y = savedPos?.second?.roundToInt() ?: -1,
                width = initialWidth.value.roundToInt(),
                height = initialHeight.value.roundToInt(),
            )
            onDispose { LinuxLyricsOverlay.stop() }
        }
        LaunchedEffect(FloatingLyricsState.isPipMinimized) {
            LinuxLyricsOverlay.setVisible(!FloatingLyricsState.isPipMinimized)
        }
        return
    }

    val windowState = rememberWindowState(
        position = initialPos,
        width = initialWidth,
        height = initialHeight,
    )

    LaunchedEffect(windowState.position, windowState.size) {
        val pos = windowState.position
        val size = windowState.size
        WindowsPreferences.saveFloatingLyricsPosition(pos.x.value, pos.y.value)
        if (size.width.value >= PIP_MIN_WIDTH && size.height.value >= PIP_MIN_HEIGHT) {
            WindowsPreferences.saveFloatingLyricsSize(size.width.value, size.height.value)
        }
    }

    Window(
        onCloseRequest = onClose,
        state = windowState,
        visible = !FloatingLyricsState.isPipMinimized,
        title = "Floating Lyrics - Record Labs",
        icon = painterResource("recordlabs.ico"),
        undecorated = true,
        transparent = true,
        alwaysOnTop = true,
        resizable = true,
        // Linux window managers give keyboard focus to every window that appears,
        // so the lyrics window took focus away from the app the user had just
        // switched to (on Windows a topmost window shown this way does not).
        // Its buttons, dragging and scrolling still work without focus.
        focusable = com.recordlabs.desktop.data.Platform.isWindows,
    ) {
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
            FloatingLyricsWindowBody(track, windowState, initialWidth, initialHeight, onClose)
        }
    }
}

@Composable
private fun androidx.compose.ui.window.FrameWindowScope.FloatingLyricsWindowBody(
    track: Track,
    windowState: androidx.compose.ui.window.WindowState,
    initialWidth: androidx.compose.ui.unit.Dp,
    initialHeight: androidx.compose.ui.unit.Dp,
    onClose: () -> Unit,
) {
    DisposableEffect(window) {
        window.minimumSize = java.awt.Dimension(PIP_MIN_WIDTH.toInt(), PIP_MIN_HEIGHT.toInt())
        // Window managers that ignore the minimum size: grow it back right away.
        val keepMinimum = object : ComponentAdapter() {
            override fun componentResized(e: ComponentEvent?) {
                if (window.isVisible && (window.width < PIP_MIN_WIDTH || window.height < PIP_MIN_HEIGHT)) {
                    java.awt.EventQueue.invokeLater {
                        window.setSize(maxOf(window.width, PIP_MIN_WIDTH.toInt()), maxOf(window.height, PIP_MIN_HEIGHT.toInt()))
                    }
                }
            }
        }
        window.addComponentListener(keepMinimum)
        onDispose { window.removeComponentListener(keepMinimum) }
    }
    // Grown back to the minimum if the window manager shrank it anyway.
    LaunchedEffect(window, FloatingLyricsState.isPipMinimized) {
        if (FloatingLyricsState.isPipMinimized) return@LaunchedEffect
        repeat(3) {
            delay(400L)
            if (window.width < PIP_MIN_WIDTH || window.height < PIP_MIN_HEIGHT) {
                window.setSize(
                    maxOf(window.width, initialWidth.value.roundToInt(), PIP_MIN_WIDTH.toInt()),
                    maxOf(window.height, initialHeight.value.roundToInt(), PIP_MIN_HEIGHT.toInt()),
                )
            }
        }
    }
    // Multi-monitor dynamic watcher: catches monitor plug/unplug & resolution/topology changes
    LaunchedEffect(window) {
        var lastSignature = getScreenTopologySignature()
        while (isActive) {
            delay(1000L)
            val currentSignature = getScreenTopologySignature()
            if (currentSignature != lastSignature) {
                lastSignature = currentSignature
                if (!FloatingLyricsState.isPipMinimized) {
                    recoverFloatingWindow(window, windowState)
                    // Second pass after 800ms when Windows finishes display handshake and DWM settles
                    delay(800L)
                    recoverFloatingWindow(window, windowState)
                }
            }
        }
    }

    // Recover window position, topmost Z-order, and Skiko rendering surface when shown / unminimized
    LaunchedEffect(window, FloatingLyricsState.isPipMinimized) {
        if (!FloatingLyricsState.isPipMinimized) {
            recoverFloatingWindow(window, windowState)
        }
    }

    // Keep transparency alive when dragged across displays with different scaling factors or X11 screen devices
    DisposableEffect(window) {
        ensureWindowTranslucency(window)

        val gcListener = java.beans.PropertyChangeListener { evt ->
            if (evt.propertyName == "graphicsConfiguration") {
                ensureWindowTranslucency(window)
            }
        }
        window.addPropertyChangeListener("graphicsConfiguration", gcListener)

        val moveListener = object : ComponentAdapter() {
            override fun componentMoved(e: ComponentEvent?) {
                ensureWindowTranslucency(window)
            }
        }
        window.addComponentListener(moveListener)

        onDispose {
            window.removePropertyChangeListener("graphicsConfiguration", gcListener)
            window.removeComponentListener(moveListener)
        }
    }

    androidx.compose.runtime.CompositionLocalProvider(LocalPipWindowScope provides this) {
    Box(Modifier.fillMaxSize()) {
    FloatingLyricsContent(
        track = track,
        isPlaying = FloatingLyricsState.isPlaying,
        // Same timing as the lyrics page: the Lyrics offset setting and the Linux lead.
        positionMs = LyricsTiming.position(FloatingLyricsState.positionMs),
        onPlayPause = { FloatingLyricsState.onPlayPause?.invoke() },
        onPrevious = { FloatingLyricsState.onPrevious?.invoke() },
        onNext = { FloatingLyricsState.onNext?.invoke() },
        onSeek = { pos -> FloatingLyricsState.onSeek?.invoke(pos) },
        onOpenDesktop = {
            FloatingLyricsState.isPipMinimized = true
            FloatingLyricsState.requestRestoreDesktop?.invoke()
        },
        onClose = onClose,
    )
    // Linux window managers can't resize a borderless window, so it gets
    // its own handle (bottom-right corner) plus interactive border zones. Windows resizes it natively.
    if (!com.recordlabs.desktop.data.Platform.isWindows) {
        val w = LocalPipWindowScope.current?.window
        if (w != null) {
            PipAllResizeBorders(w)
        }
        PipResizeHandle(Modifier.align(Alignment.BottomEnd))
    }
    }
    }
}

private val LocalPipWindowScope = androidx.compose.runtime.staticCompositionLocalOf<androidx.compose.ui.window.WindowScope?> { null }
private val LocalPipOverlay = androidx.compose.runtime.staticCompositionLocalOf { false }

/** Runs a button's action on the AWT thread (the overlay draws on its own thread). */
private fun onAwt(action: () -> Unit) = java.awt.EventQueue.invokeLater(action)

/** The floating lyrics inside the Wayland overlay (see LinuxLyricsOverlay). */
@Composable
private fun OverlayLyrics(onClose: () -> Unit) {
    val track = FloatingLyricsState.currentTrack ?: return
    androidx.compose.runtime.CompositionLocalProvider(LocalPipOverlay provides true) {
        Box(Modifier.fillMaxSize()) {
            FloatingLyricsContent(
                track = track,
                isPlaying = FloatingLyricsState.isPlaying,
                positionMs = LyricsTiming.position(FloatingLyricsState.positionMs),
                onPlayPause = { onAwt { FloatingLyricsState.onPlayPause?.invoke() } },
                onPrevious = { onAwt { FloatingLyricsState.onPrevious?.invoke() } },
                onNext = { onAwt { FloatingLyricsState.onNext?.invoke() } },
                onSeek = { pos -> onAwt { FloatingLyricsState.onSeek?.invoke(pos) } },
                onOpenDesktop = {
                    onAwt {
                        FloatingLyricsState.isPipMinimized = true
                        FloatingLyricsState.requestRestoreDesktop?.invoke()
                    }
                },
                onClose = { onAwt(onClose) },
            )
            PipResizeHandle(Modifier.align(Alignment.BottomEnd))
        }
    }
}

/**
 * How the floating lyrics window looks. On COSMIC the desktop draws every X11
 * app's see-through areas black (cosmic-comp issue #677), and Java apps are
 * X11 apps there, so the lyrics get a solid dark card with square corners
 * instead of floating over the desktop. Elsewhere it stays see-through.
 */
private object PipLook {
    val opaque: Boolean = !com.recordlabs.desktop.data.Platform.isWindows && !LinuxLyricsOverlay.isAvailable &&
        listOf("XDG_CURRENT_DESKTOP", "XDG_SESSION_DESKTOP", "DESKTOP_SESSION")
            .any { System.getenv(it).orEmpty().contains("cosmic", ignoreCase = true) }
    val opaqueBackground = Color(0xFF121214)
    val shape = if (opaque) androidx.compose.foundation.shape.RoundedCornerShape(0.dp) else RoundedCornerShape(16.dp)
}

/**
 * The strip that moves the window. Windows moves it itself; on Linux the
 * window manager is asked to (Wayland desktops ignore an X11 app that sets
 * its own window position, so the old way did nothing there).
 */
@Composable
private fun PipDragArea(modifier: Modifier, content: @Composable () -> Unit) {
    if (LocalPipOverlay.current) {
        // Wayland overlay: it follows the pointer itself until the button is released.
        Box(modifier.pointerInput(Unit) {
            awaitEachGesture {
                awaitFirstDown(requireUnconsumed = false)
                LinuxLyricsOverlay.beginDrag()
            }
        }) { content() }
        return
    }
    val scope = LocalPipWindowScope.current ?: run { Box(modifier) { content() }; return }
    if (com.recordlabs.desktop.data.Platform.isWindows) {
        with(scope) { WindowDraggableArea(modifier) { content() } }
        return
    }
    val window = scope.window
    Box(
        modifier.pointerInput(window) {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                val pointer = java.awt.MouseInfo.getPointerInfo()?.location
                val scale = window.graphicsConfiguration?.defaultTransform?.scaleX ?: 1.0
                val handedOver = pointer != null && LinuxX11Window.beginMoveResize(
                    window, (pointer.x * scale).roundToInt(), (pointer.y * scale).roundToInt(), LinuxX11Window.MOVE,
                )
                if (handedOver) return@awaitEachGesture
                // No window manager answer possible (no X11): move it ourselves.
                val start = window.location
                var offset = androidx.compose.ui.geometry.Offset.Zero
                drag(down.id) { change ->
                    offset += change.positionChange()
                    change.consume()
                    window.setLocation(start.x + (offset.x / density).roundToInt(), start.y + (offset.y / density).roundToInt())
                }
            }
        },
    ) { content() }
}

private const val PIP_MIN_WIDTH = 360f
private const val PIP_MIN_HEIGHT = 120f

@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun PipResizeHandle(modifier: Modifier) {
    val density = LocalDensity.current.density
    var hovered by remember { mutableStateOf(false) }
    val overlay = LocalPipOverlay.current
    val window = LocalPipWindowScope.current?.window
    Box(
        modifier
            .size(28.dp)
            .then(if (overlay) Modifier else Modifier.pointerHoverIcon(androidx.compose.ui.input.pointer.PointerIcon(java.awt.Cursor(java.awt.Cursor.SE_RESIZE_CURSOR))))
            .onPointerEvent(PointerEventType.Enter) { hovered = true }
            .onPointerEvent(PointerEventType.Exit) { hovered = false }
            .then(if (overlay) Modifier.pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false).consume()
                    LinuxLyricsOverlay.beginResize()
                }
            } else Modifier)
            .then(if (overlay || window == null) Modifier else Modifier.pointerInput(window) {
                var width = 0f
                var height = 0f
                detectDragGestures(
                    onDragStart = {
                        width = window.width.toFloat()
                        height = window.height.toFloat()
                    },
                ) { change, drag ->
                    change.consume()
                    // The corner moves with the pointer, so these deltas stay exact.
                    width = (width + drag.x / density).coerceAtLeast(PIP_MIN_WIDTH)
                    height = (height + drag.y / density).coerceAtLeast(PIP_MIN_HEIGHT)
                    window.setSize(width.roundToInt(), height.roundToInt())
                }
            }),
        contentAlignment = Alignment.BottomEnd,
    ) {
        androidx.compose.foundation.Canvas(Modifier.padding(7.dp).size(12.dp)) {
            val color = Color.White.copy(alpha = if (hovered) 0.85f else 0.35f)
            val stroke = 1.6.dp.toPx()
            for (step in 1..3) {
                val inset = size.width * (1f - step / 3f)
                drawLine(color, Offset(size.width, inset), Offset(inset, size.height), stroke, cap = androidx.compose.ui.graphics.StrokeCap.Round)
            }
        }
    }
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun PipEdgeResize(
    modifier: Modifier,
    window: java.awt.Window,
    direction: Long,
    cursorType: Int,
) {
    val density = LocalDensity.current.density
    Box(
        modifier
            .pointerHoverIcon(androidx.compose.ui.input.pointer.PointerIcon(java.awt.Cursor(cursorType)))
            .pointerInput(window) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val pointer = java.awt.MouseInfo.getPointerInfo()?.location
                    val scale = window.graphicsConfiguration?.defaultTransform?.scaleX ?: 1.0
                    val handedOver = pointer != null && LinuxX11Window.beginMoveResize(
                        window, (pointer.x * scale).roundToInt(), (pointer.y * scale).roundToInt(), direction,
                    )
                    if (handedOver) return@awaitEachGesture

                    val startLoc = window.location
                    val startW = window.width.toFloat()
                    val startH = window.height.toFloat()
                    var totalDragX = 0f
                    var totalDragY = 0f

                    drag(down.id) { change ->
                        val delta = change.positionChange()
                        change.consume()
                        totalDragX += delta.x / density
                        totalDragY += delta.y / density

                        var newX = startLoc.x
                        var newY = startLoc.y
                        var newW = startW
                        var newH = startH

                        when (direction) {
                            LinuxX11Window.SIZE_RIGHT -> {
                                newW = (startW + totalDragX).coerceAtLeast(PIP_MIN_WIDTH)
                            }
                            LinuxX11Window.SIZE_LEFT -> {
                                val potentialW = (startW - totalDragX).coerceAtLeast(PIP_MIN_WIDTH)
                                val appliedDelta = startW - potentialW
                                newX = (startLoc.x + appliedDelta).roundToInt()
                                newW = potentialW
                            }
                            LinuxX11Window.SIZE_BOTTOM -> {
                                newH = (startH + totalDragY).coerceAtLeast(PIP_MIN_HEIGHT)
                            }
                            LinuxX11Window.SIZE_TOP -> {
                                val potentialH = (startH - totalDragY).coerceAtLeast(PIP_MIN_HEIGHT)
                                val appliedDelta = startH - potentialH
                                newY = (startLoc.y + appliedDelta).roundToInt()
                                newH = potentialH
                            }
                            LinuxX11Window.SIZE_TOP_LEFT -> {
                                val potW = (startW - totalDragX).coerceAtLeast(PIP_MIN_WIDTH)
                                val potH = (startH - totalDragY).coerceAtLeast(PIP_MIN_HEIGHT)
                                newX = (startLoc.x + (startW - potW)).roundToInt()
                                newY = (startLoc.y + (startH - potH)).roundToInt()
                                newW = potW
                                newH = potH
                            }
                            LinuxX11Window.SIZE_TOP_RIGHT -> {
                                val potH = (startH - totalDragY).coerceAtLeast(PIP_MIN_HEIGHT)
                                newY = (startLoc.y + (startH - potH)).roundToInt()
                                newW = (startW + totalDragX).coerceAtLeast(PIP_MIN_WIDTH)
                                newH = potH
                            }
                            LinuxX11Window.SIZE_BOTTOM_LEFT -> {
                                val potW = (startW - totalDragX).coerceAtLeast(PIP_MIN_WIDTH)
                                newX = (startLoc.x + (startW - potW)).roundToInt()
                                newW = potW
                                newH = (startH + totalDragY).coerceAtLeast(PIP_MIN_HEIGHT)
                            }
                        }

                        if (newX != window.x || newY != window.y) {
                            window.setLocation(newX, newY)
                        }
                        window.setSize(newW.roundToInt(), newH.roundToInt())
                    }
                }
            }
    )
}

@Composable
private fun androidx.compose.foundation.layout.BoxScope.PipAllResizeBorders(window: java.awt.Window) {
    val borderThickness = 6.dp
    val cornerSize = 14.dp

    // Top edge
    PipEdgeResize(
        Modifier
            .fillMaxWidth()
            .height(borderThickness)
            .padding(horizontal = cornerSize)
            .align(Alignment.TopCenter),
        window,
        LinuxX11Window.SIZE_TOP,
        java.awt.Cursor.N_RESIZE_CURSOR
    )
    // Bottom edge
    PipEdgeResize(
        Modifier
            .fillMaxWidth()
            .height(borderThickness)
            .padding(horizontal = cornerSize)
            .align(Alignment.BottomCenter),
        window,
        LinuxX11Window.SIZE_BOTTOM,
        java.awt.Cursor.S_RESIZE_CURSOR
    )
    // Left edge
    PipEdgeResize(
        Modifier
            .fillMaxHeight()
            .width(borderThickness)
            .padding(vertical = cornerSize)
            .align(Alignment.CenterStart),
        window,
        LinuxX11Window.SIZE_LEFT,
        java.awt.Cursor.W_RESIZE_CURSOR
    )
    // Right edge
    PipEdgeResize(
        Modifier
            .fillMaxHeight()
            .width(borderThickness)
            .padding(vertical = cornerSize)
            .align(Alignment.CenterEnd),
        window,
        LinuxX11Window.SIZE_RIGHT,
        java.awt.Cursor.E_RESIZE_CURSOR
    )
    // Corners
    PipEdgeResize(
        Modifier
            .size(cornerSize)
            .align(Alignment.TopStart),
        window,
        LinuxX11Window.SIZE_TOP_LEFT,
        java.awt.Cursor.NW_RESIZE_CURSOR
    )
    PipEdgeResize(
        Modifier
            .size(cornerSize)
            .align(Alignment.TopEnd),
        window,
        LinuxX11Window.SIZE_TOP_RIGHT,
        java.awt.Cursor.NE_RESIZE_CURSOR
    )
    PipEdgeResize(
        Modifier
            .size(cornerSize)
            .align(Alignment.BottomStart),
        window,
        LinuxX11Window.SIZE_BOTTOM_LEFT,
        java.awt.Cursor.SW_RESIZE_CURSOR
    )
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun FloatingLyricsContent(
    track: Track,
    isPlaying: Boolean,
    positionMs: Long,
    onPlayPause: () -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onSeek: (Long) -> Unit,
    onOpenDesktop: () -> Unit,
    onClose: () -> Unit,
) {
    // A music video shows its song's lyrics, as on the lyrics page (see [LyricsSource]).
    val lyricsSource = rememberLyricsSource(track)
    val lyricsTrack = lyricsSource.track
    val positionMs = lyricsSource.lyricsPosition(positionMs)
    val onSeek: (Long) -> Unit = { ms -> onSeek(lyricsSource.playbackPosition(ms)) }
    val cachedLyrics = remember(lyricsTrack.id) { WindowsLyricsProviderRegistry.peekCached(lyricsTrack) }
    var rawLyrics by remember(lyricsTrack.id) { mutableStateOf(cachedLyrics?.lyrics) }
    var isLoadingLyrics by remember(lyricsTrack.id) { mutableStateOf(rawLyrics.isNullOrBlank()) }
    val lyricsScope = rememberCoroutineScope()

    DisposableEffect(lyricsTrack.id) {
        val unsubscribe = WindowsLyricsProviderRegistry.addListener { updatedTrackId, result ->
            if (updatedTrackId == lyricsTrack.id) {
                lyricsScope.launch {
                    rawLyrics = result.lyrics
                    isLoadingLyrics = false
                }
            }
        }
        val latest = WindowsLyricsProviderRegistry.peekCached(lyricsTrack)
        if (latest != null && latest.lyrics.isNotBlank() && latest.lyrics != rawLyrics) {
            rawLyrics = latest.lyrics
            isLoadingLyrics = false
        }
        onDispose {
            unsubscribe()
        }
    }

    LaunchedEffect(lyricsTrack.id) {
        if (rawLyrics.isNullOrBlank()) {
            isLoadingLyrics = true
            val result = runCatching { WindowsLyricsProviderRegistry.getLyricsResult(lyricsTrack) }.getOrNull()
            if (result != null && result.lyrics.isNotBlank()) {
                rawLyrics = result.lyrics
            }
            isLoadingLyrics = false
        }
        val current = WindowsLyricsProviderRegistry.peekCached(lyricsTrack)
        if (current != null && !current.manuallySelected && current.provider != "Paxsenix") {
            val upgraded = runCatching {
                WindowsLyricsProviderRegistry.getPreferredLyricsUpgrade(lyricsTrack, current)
            }.getOrNull()
            if (upgraded != null && upgraded.lyrics.isNotBlank()) {
                rawLyrics = upgraded.lyrics
            }
        }
    }

    val lines = remember(rawLyrics) {
        WindowsRichLyrics.parse(rawLyrics.orEmpty())
    }
    val vocalLines = remember(lines) {
        lines.filter { !it.isBackground && it.text.isNotBlank() }
    }

    val currentIndex = remember(vocalLines, positionMs) {
        if (vocalLines.isEmpty()) -1
        else vocalLines.indexOfLast { it.startMs <= positionMs }
    }

    val interactionSource = remember { MutableInteractionSource() }
    val isHovered by interactionSource.collectIsHoveredAsState()
    val density = LocalDensity.current

    var manualBrowseOverride by remember { mutableStateOf(false) }
    var userManualOffset by remember { mutableStateOf(0f) }
    var isDragging by remember { mutableStateOf(false) }
    var lastScrollTimestamp by remember { mutableStateOf(0L) }

    LaunchedEffect(track.id) {
        manualBrowseOverride = false
        userManualOffset = 0f
    }

    LaunchedEffect(manualBrowseOverride, lastScrollTimestamp) {
        if (manualBrowseOverride) {
            delay(4500L)
            manualBrowseOverride = false
            userManualOffset = 0f
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .hoverable(interactionSource)
            .clip(PipLook.shape)
            .background(
                when {
                    PipLook.opaque -> PipLook.opaqueBackground
                    isHovered -> Color.Black.copy(alpha = 0.45f)
                    else -> Color.Transparent
                }
            )
            .then(
                if (isHovered) {
                    Modifier.border(1.dp, Color.White.copy(alpha = 0.15f), PipLook.shape)
                } else Modifier
            )
            .onPointerEvent(PointerEventType.Scroll) { event ->
                val change = event.changes.firstOrNull() ?: return@onPointerEvent
                val delta = change.scrollDelta.y
                if (delta != 0f) {
                    manualBrowseOverride = true
                    lastScrollTimestamp = System.currentTimeMillis()
                    userManualOffset += delta * 45f
                    change.consume()
                }
            },
    ) {
        // Dedicated draggable strip across top of window so user can reposition the window freely
        PipDragArea(
            modifier = Modifier
                .fillMaxWidth()
                .height(34.dp)
                .align(Alignment.TopCenter),
        ) {
            Box(Modifier.fillMaxSize())
        }

        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = 34.dp, bottom = 12.dp, start = 14.dp, end = 14.dp)
                .clipToBounds()
                .pointerInput(Unit) {
                    detectVerticalDragGestures(
                        onDragStart = {
                            isDragging = true
                            manualBrowseOverride = true
                            lastScrollTimestamp = System.currentTimeMillis()
                        },
                        onDragEnd = {
                            isDragging = false
                        },
                        onDragCancel = {
                            isDragging = false
                        },
                        onVerticalDrag = { change, dragAmount ->
                            change.consume()
                            manualBrowseOverride = true
                            lastScrollTimestamp = System.currentTimeMillis()
                            userManualOffset -= dragAmount
                        },
                    )
                },
            contentAlignment = Alignment.Center,
        ) {
            val gapPx = with(density) {
                maxOf(8.dp, (maxHeight * 0.05f).coerceIn(8.dp, 14.dp)).toPx()
            }
            val defaultLineHeight = with(density) { 26.dp.toPx() }
            val itemHeights = remember(track.id, rawLyrics) { mutableStateMapOf<Int, Int>() }

            // Global cumulative centers for all vocal lines in document coordinates
            val globalPositions = remember(vocalLines, itemHeights.toMap(), gapPx, defaultLineHeight) {
                val map = mutableMapOf<Int, Float>()
                var currentY = 0f
                for (i in vocalLines.indices) {
                    val h = itemHeights[i]?.toFloat() ?: defaultLineHeight
                    map[i] = currentY + h / 2f
                    currentY += h + gapPx
                }
                map
            }

            val baseCameraY = if (currentIndex in vocalLines.indices) {
                globalPositions[currentIndex] ?: 0f
            } else if (vocalLines.isNotEmpty()) {
                (globalPositions[0] ?: 0f) - (defaultLineHeight + gapPx)
            } else 0f

            val minCamera = (globalPositions[0] ?: 0f) - 60f
            val maxCamera = (globalPositions[vocalLines.lastIndex] ?: 0f) + 60f
            val targetCameraY = if (vocalLines.isNotEmpty()) {
                (baseCameraY + userManualOffset).coerceIn(minCamera, maxCamera)
            } else 0f

            val animatedCameraY by animateFloatAsState(
                targetValue = targetCameraY,
                animationSpec = tween(
                    durationMillis = if (isDragging) 60 else 500,
                    easing = CubicBezierEasing(0.16f, 1f, 0.3f, 1f),
                ),
                label = "floating-camera-scroll",
            )

            val focusedIndex = if (!manualBrowseOverride && currentIndex in vocalLines.indices) {
                currentIndex
            } else if (vocalLines.isNotEmpty()) {
                vocalLines.indices.minByOrNull { i ->
                    kotlin.math.abs((globalPositions[i] ?: 0f) - animatedCameraY)
                } ?: currentIndex
            } else -1

            if (vocalLines.isEmpty()) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    NetflixSubtitleText(
                        text = "♫  ${track.title}",
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Medium,
                        alpha = 0.75f,
                        modifier = Modifier.fillMaxWidth(0.92f),
                    )
                    Spacer(Modifier.height(8.dp))
                    NetflixSubtitleText(
                        text = if (isLoadingLyrics) "Loading lyrics..." else track.artist,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        alpha = 1.0f,
                        modifier = Modifier.fillMaxWidth(0.92f),
                    )
                }
            } else if (currentIndex == -1 && !manualBrowseOverride) {
                // Intro interval / before first lyric starts
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    NetflixSubtitleText(
                        text = "♫  ${track.title} • ${track.artist}",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Normal,
                        alpha = 0.65f,
                        modifier = Modifier.fillMaxWidth(0.92f),
                    )
                    Spacer(Modifier.height(8.dp))
                    NetflixSubtitleText(
                        text = vocalLines.firstOrNull()?.text ?: "• • •",
                        fontSize = 19.sp,
                        fontWeight = FontWeight.Bold,
                        alpha = 0.90f,
                        modifier = Modifier.fillMaxWidth(0.92f),
                    )
                }
            } else {
                val maxVisibleDist = constraints.maxHeight * 1.35f
                val visibleIndices = vocalLines.indices.filter { index ->
                    val relY = (globalPositions[index] ?: 0f) - animatedCameraY
                    kotlin.math.abs(relY) <= maxVisibleDist
                }

                visibleIndices.forEach { index ->
                    key(index, vocalLines[index].startMs) {
                        val relY = (globalPositions[index] ?: 0f) - animatedCameraY
                        val distFromFocus = kotlin.math.abs(index - focusedIndex)
                        val isCenterLine = distFromFocus == 0

                        val targetAlpha = when (distFromFocus) {
                            0 -> 1.0f
                            1 -> if (index > focusedIndex) 0.70f else 0.50f
                            2 -> if (constraints.maxHeight >= with(density) { 180.dp.toPx() }) 0.35f else 0.0f
                            else -> 0.0f
                        }
                        val animatedAlpha by animateFloatAsState(
                            targetValue = targetAlpha,
                            animationSpec = tween(
                                durationMillis = 450,
                                easing = CubicBezierEasing(0.16f, 1f, 0.3f, 1f),
                            ),
                            label = "floating-line-alpha-$index",
                        )

                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .offset { IntOffset(0, relY.roundToInt()) }
                                .graphicsLayer { alpha = animatedAlpha }
                                .onSizeChanged { itemHeights[index] = it.height }
                                .clickable(
                                    interactionSource = remember { MutableInteractionSource() },
                                    indication = null,
                                    onClick = {
                                        onSeek(vocalLines[index].startMs)
                                        manualBrowseOverride = false
                                        userManualOffset = 0f
                                    },
                                ),
                            contentAlignment = Alignment.Center,
                        ) {
                            NetflixSubtitleKaraokeLine(
                                line = vocalLines[index],
                                isActive = isCenterLine,
                                distFromFocus = distFromFocus,
                                positionMs = positionMs,
                                isPlaying = isPlaying,
                                fontSize = 18.sp,
                                lineHeight = 25.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.fillMaxWidth(0.92f),
                            )
                        }
                    }
                }
            }
        }

        // Top control bar visible on hover
        AnimatedVisibility(
            visible = isHovered,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 4.dp),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(28.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                PipDragArea(
                    modifier = Modifier.weight(1f).fillMaxHeight(),
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        modifier = Modifier.fillMaxSize(),
                    ) {
                        Icon(
                            Icons.Default.DragHandle,
                            contentDescription = "Drag window",
                            tint = Color.White.copy(alpha = 0.60f),
                            modifier = Modifier.size(18.dp),
                        )
                        Text(
                            fontFamily = AppFonts.lyrics,
                            text = "${track.title} • ${track.artist}",
                            color = Color.White.copy(alpha = 0.75f),
                            fontSize = 11.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    IconButton(
                        onClick = onPrevious,
                        modifier = Modifier.size(24.dp),
                    ) {
                        Icon(
                            Icons.Default.SkipPrevious,
                            contentDescription = "Previous",
                            tint = Color.White,
                            modifier = Modifier.size(16.dp),
                        )
                    }
                    IconButton(
                        onClick = onPlayPause,
                        modifier = Modifier.size(24.dp),
                    ) {
                        Icon(
                            if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                            contentDescription = if (isPlaying) "Pause" else "Play",
                            tint = Color.White,
                            modifier = Modifier.size(16.dp),
                        )
                    }
                    IconButton(
                        onClick = onNext,
                        modifier = Modifier.size(24.dp),
                    ) {
                        Icon(
                            Icons.Default.SkipNext,
                            contentDescription = "Next",
                            tint = Color.White,
                            modifier = Modifier.size(16.dp),
                        )
                    }
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    IconButton(
                        onClick = onOpenDesktop,
                        modifier = Modifier.size(24.dp),
                    ) {
                        Icon(
                            Icons.Default.OpenInNew,
                            contentDescription = "Open Record Labs",
                            tint = Color.White.copy(alpha = 0.85f),
                            modifier = Modifier.size(16.dp),
                        )
                    }
                    IconButton(
                        onClick = onClose,
                        modifier = Modifier.size(24.dp),
                    ) {
                        Icon(
                            Icons.Default.Close,
                            contentDescription = "Close floating lyrics",
                            tint = Color.White.copy(alpha = 0.85f),
                            modifier = Modifier.size(16.dp),
                        )
                    }
                }
            }
        }

        // Floating "Sync" pill when user scrolled away from live playback
        AnimatedVisibility(
            visible = manualBrowseOverride,
            enter = fadeIn(tween(250)),
            exit = fadeOut(tween(250)),
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 6.dp),
        ) {
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = Color.Black.copy(alpha = 0.68f),
                border = BorderStroke(1.dp, Color.White.copy(alpha = 0.25f)),
                modifier = Modifier.clickable {
                    manualBrowseOverride = false
                    userManualOffset = 0f
                },
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Icon(
                        Icons.Default.Refresh,
                        contentDescription = "Sync",
                        tint = Color.White.copy(alpha = 0.90f),
                        modifier = Modifier.size(13.dp),
                    )
                    Text(
                        fontFamily = AppFonts.lyrics,
                        text = "Sync",
                        color = Color.White.copy(alpha = 0.90f),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
        }
    }
}

@Composable
fun NetflixSubtitleText(
    text: String,
    fontSize: TextUnit,
    fontWeight: FontWeight = FontWeight.Normal,
    textColor: Color = Color.White,
    alpha: Float = 1.0f,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        // Layer 1: Extra wide ambient diffuse halo
        Text(
            fontFamily = AppFonts.lyrics,
            text = text,
            fontSize = fontSize,
            fontWeight = fontWeight,
            textAlign = TextAlign.Center,
            softWrap = true,
            color = Color.Black.copy(alpha = 0.95f),
            style = TextStyle(
                shadow = Shadow(
                    color = Color.Black.copy(alpha = 0.95f),
                    offset = Offset(0f, 2.5f),
                    blurRadius = 22f,
                ),
            ),
        )
        // Layer 2: Medium ambient spreading shadow
        Text(
            fontFamily = AppFonts.lyrics,
            text = text,
            fontSize = fontSize,
            fontWeight = fontWeight,
            textAlign = TextAlign.Center,
            softWrap = true,
            color = Color.Black.copy(alpha = 0.92f),
            style = TextStyle(
                shadow = Shadow(
                    color = Color.Black.copy(alpha = 0.92f),
                    offset = Offset(0f, 1.5f),
                    blurRadius = 12f,
                ),
            ),
        )
        // Layer 3: Tight contour outline shadow (for crisp stroke and edge definition)
        Text(
            fontFamily = AppFonts.lyrics,
            text = text,
            fontSize = fontSize,
            fontWeight = fontWeight,
            textAlign = TextAlign.Center,
            softWrap = true,
            color = Color.Black.copy(alpha = 0.90f),
            style = TextStyle(
                shadow = Shadow(
                    color = Color.Black.copy(alpha = 0.90f),
                    offset = Offset(0f, 0f),
                    blurRadius = 4f,
                ),
            ),
        )
        // Layer 4: Foreground crisp white text
        Text(
            fontFamily = AppFonts.lyrics,
            text = text,
            fontSize = fontSize,
            fontWeight = fontWeight,
            textAlign = TextAlign.Center,
            softWrap = true,
            color = textColor.copy(alpha = alpha),
            style = TextStyle(
                shadow = Shadow(
                    color = Color.Black.copy(alpha = 0.85f),
                    offset = Offset(0f, 1f),
                    blurRadius = 2f,
                ),
            ),
        )
    }
}

@Composable
private fun smoothPlaybackClock(
    key: Any,
    reportedPositionMs: Long,
    isPlaying: Boolean,
    animate: Boolean,
): Long {
    var anchorPositionMs by remember(key) { mutableStateOf(reportedPositionMs) }
    var anchorNanos by remember(key) { mutableStateOf(System.nanoTime()) }
    var frameTick by remember(key) { mutableStateOf(0L) }
    var previousReportedPositionMs by remember(key) { mutableStateOf(reportedPositionMs) }

    LaunchedEffect(reportedPositionMs, isPlaying, key) {
        val now = System.nanoTime()
        val estimated = if (isPlaying) {
            anchorPositionMs + ((now - anchorNanos) / 1_000_000L).coerceAtLeast(0L)
        } else anchorPositionMs

        val backwardSeek = reportedPositionMs + 350L < previousReportedPositionMs
        val visualTooFarAhead = estimated - reportedPositionMs > 400L
        if (!isPlaying || backwardSeek || reportedPositionMs - estimated > 320L || visualTooFarAhead) {
            anchorPositionMs = if (isPlaying && !backwardSeek && !visualTooFarAhead) {
                maxOf(reportedPositionMs, estimated)
            } else reportedPositionMs
            anchorNanos = now
        }
        previousReportedPositionMs = reportedPositionMs
    }
    LaunchedEffect(isPlaying, animate, key) {
        if (!isPlaying || !animate) return@LaunchedEffect
        while (true) withFrameNanos { _ ->
            frameTick++
        }
    }
    @Suppress("UNUSED_EXPRESSION")
    frameTick
    return if (isPlaying && animate) {
        anchorPositionMs + ((System.nanoTime() - anchorNanos) / 1_000_000L).coerceAtLeast(0L)
    } else reportedPositionMs
}

@Composable
fun NetflixSubtitleKaraokeLine(
    line: RichLyricLine,
    isActive: Boolean,
    distFromFocus: Int,
    positionMs: Long,
    isPlaying: Boolean,
    fontSize: TextUnit = 18.sp,
    lineHeight: TextUnit = 25.sp,
    fontWeight: FontWeight = FontWeight.Bold,
    modifier: Modifier = Modifier,
) {
    val plain = remember(line) {
        if (line.words.isEmpty()) line.text
        else buildString {
            line.words.forEachIndexed { index, word ->
                append(word.text)
                if (word.trailingSpace && index < line.words.lastIndex) append(' ')
            }
        }
    }

    val smoothPositionMs = smoothPlaybackClock(
        key = "pip-line-${line.startMs}",
        reportedPositionMs = positionMs,
        isPlaying = isPlaying,
        animate = isActive && line.words.isNotEmpty(),
    )

    var textLayoutResult by remember { mutableStateOf<TextLayoutResult?>(null) }

    val globalSweep: Float = if (isActive && line.words.isNotEmpty()) run {
        var cursor = 0
        var previousStartMs = Long.MIN_VALUE
        line.words.forEachIndexed { index, word ->
            val wordStart = cursor
            val trailing = if (word.trailingSpace && index < line.words.lastIndex) 1 else 0
            val wordEnd = (wordStart + word.text.length).coerceAtMost(plain.length)
            val normalizedStart = maxOf(word.startMs, previousStartMs)
            val nextStart = line.words.getOrNull(index + 1)?.startMs?.takeIf { it > normalizedStart }
            val normalizedEnd = maxOf(word.endMs, normalizedStart + 40L).let { end -> nextStart?.let { minOf(end, it) } ?: end }
            val wordLen = word.text.length + trailing
            when {
                smoothPositionMs >= normalizedEnd -> { /* completed */ }
                smoothPositionMs >= normalizedStart -> {
                    val progress = ((smoothPositionMs - normalizedStart).toFloat() / (normalizedEnd - normalizedStart).coerceAtLeast(1L)).coerceIn(0f, 1f)
                    return@run wordStart.toFloat() + progress * wordLen
                }
                else -> {
                    return@run wordStart.toFloat()
                }
            }
            cursor = wordEnd + trailing
            previousStartMs = normalizedStart
        }
        plain.length.toFloat()
    } else 0f

    val baseTextColor = if (isActive) Color(0xFFB8B8B8) else Color(0xFFA0A0A0)

    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        // Layer 1: Extra wide ambient diffuse halo
        Text(
            fontFamily = AppFonts.lyrics,
            text = plain,
            fontSize = fontSize,
            lineHeight = lineHeight,
            fontWeight = fontWeight,
            textAlign = TextAlign.Center,
            softWrap = true,
            color = Color.Black.copy(alpha = 0.95f),
            style = TextStyle(
                shadow = Shadow(
                    color = Color.Black.copy(alpha = 0.95f),
                    offset = Offset(0f, 2.5f),
                    blurRadius = 22f,
                ),
            ),
            modifier = Modifier.fillMaxWidth(),
        )
        // Layer 2: Medium ambient spreading shadow
        Text(
            fontFamily = AppFonts.lyrics,
            text = plain,
            fontSize = fontSize,
            lineHeight = lineHeight,
            fontWeight = fontWeight,
            textAlign = TextAlign.Center,
            softWrap = true,
            color = Color.Black.copy(alpha = 0.92f),
            style = TextStyle(
                shadow = Shadow(
                    color = Color.Black.copy(alpha = 0.92f),
                    offset = Offset(0f, 1.5f),
                    blurRadius = 12f,
                ),
            ),
            modifier = Modifier.fillMaxWidth(),
        )
        // Layer 3: Tight contour outline shadow (for crisp stroke and edge definition)
        Text(
            fontFamily = AppFonts.lyrics,
            text = plain,
            fontSize = fontSize,
            lineHeight = lineHeight,
            fontWeight = fontWeight,
            textAlign = TextAlign.Center,
            softWrap = true,
            color = Color.Black.copy(alpha = 0.90f),
            style = TextStyle(
                shadow = Shadow(
                    color = Color.Black.copy(alpha = 0.90f),
                    offset = Offset(0f, 0f),
                    blurRadius = 4f,
                ),
            ),
            modifier = Modifier.fillMaxWidth(),
        )
        // Layer 4: Base foreground gray text
        Text(
            fontFamily = AppFonts.lyrics,
            text = plain,
            fontSize = fontSize,
            lineHeight = lineHeight,
            fontWeight = fontWeight,
            textAlign = TextAlign.Center,
            softWrap = true,
            color = baseTextColor,
            style = TextStyle(
                shadow = Shadow(
                    color = Color.Black.copy(alpha = 0.85f),
                    offset = Offset(0f, 1f),
                    blurRadius = 2f,
                ),
            ),
            onTextLayout = { textLayoutResult = it },
            modifier = Modifier.fillMaxWidth(),
        )

        // Layer 5: Active white highlight text (pure white, no glow/cahaya, smooth soft-edge wipe)
        if (isActive) {
            if (line.words.isNotEmpty()) {
                if (globalSweep > 0.01f) {
                    Text(
                        fontFamily = AppFonts.lyrics,
                        text = plain,
                        fontSize = fontSize,
                        lineHeight = lineHeight,
                        fontWeight = fontWeight,
                        textAlign = TextAlign.Center,
                        softWrap = true,
                        color = Color.White,
                        onTextLayout = { textLayoutResult = it },
                        modifier = Modifier
                            .fillMaxWidth()
                            .drawWithContent {
                                if (globalSweep >= plain.length.toFloat()) {
                                    drawContent()
                                    return@drawWithContent
                                }
                                val layout = textLayoutResult ?: return@drawWithContent
                                val sweepIndex = globalSweep.toInt().coerceIn(0, maxOf(0, plain.length - 1))
                                val sweepRem = globalSweep - sweepIndex
                                val rect = layout.getBoundingBox(sweepIndex)
                                val lineIndex = layout.getLineForOffset(sweepIndex)

                                // Right-to-left scripts (Arabic, Hebrew...) are sung from the right.
                                val rtl = layout.getBidiRunDirection(sweepIndex) == androidx.compose.ui.text.style.ResolvedTextDirection.Rtl
                                val charWidth = rect.right - rect.left
                                val currentX = if (rtl) rect.right - charWidth * sweepRem else rect.left + charWidth * sweepRem

                                val wipeBrush = if (rtl) {
                                    Brush.horizontalGradient(
                                        0.0f to Color.Black,
                                        1.0f to Color.Transparent,
                                        startX = currentX - 2f,
                                        endX = currentX + 10f,
                                    )
                                } else {
                                    Brush.horizontalGradient(
                                        0.0f to Color.Transparent,
                                        1.0f to Color.Black,
                                        startX = currentX - 10f,
                                        endX = currentX + 2f,
                                    )
                                }

                                val bounds = Rect(-200f, -200f, size.width + 200f, size.height + 200f)
                                drawContext.canvas.saveLayer(bounds, Paint())
                                drawContent()

                                // Soft-erase the un-sung part on current line
                                drawRect(
                                    brush = wipeBrush,
                                    topLeft = Offset(-200f, layout.getLineTop(lineIndex)),
                                    size = Size(size.width + 400f, layout.getLineBottom(lineIndex) - layout.getLineTop(lineIndex)),
                                    blendMode = BlendMode.DstOut,
                                )

                                // Hard-erase all lines below the current line
                                if (lineIndex < layout.lineCount - 1) {
                                    drawRect(
                                        color = Color.Black,
                                        topLeft = Offset(-200f, layout.getLineBottom(lineIndex)),
                                        size = Size(size.width + 400f, size.height + 200f),
                                        blendMode = BlendMode.DstOut,
                                    )
                                }

                                drawContext.canvas.restore()
                            },
                    )
                }
            } else {
                val activeEmphasis by animateFloatAsState(
                    targetValue = if (isActive) 1f else 0f,
                    animationSpec = tween(350, easing = CubicBezierEasing(0.16f, 1f, 0.3f, 1f)),
                    label = "pip-line-active-fade",
                )
                if (activeEmphasis > 0.01f) {
                    Text(
                        fontFamily = AppFonts.lyrics,
                        text = plain,
                        fontSize = fontSize,
                        lineHeight = lineHeight,
                        fontWeight = fontWeight,
                        textAlign = TextAlign.Center,
                        softWrap = true,
                        color = Color.White.copy(alpha = activeEmphasis),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
    }
}
