package com.recordlabs.desktop

import androidx.compose.runtime.Composable
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerButtons
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.unit.Density
import com.sun.jna.Callback
import com.sun.jna.CallbackReference
import com.sun.jna.Library
import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.NativeLibrary
import com.sun.jna.Pointer
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.ImageInfo
import org.jetbrains.skia.Pixmap
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.math.roundToInt

/**
 * The floating lyrics as a native Wayland overlay (Linux Wayland desktops:
 * COSMIC, KDE, sway, Hyprland...).
 *
 * Java on Linux only speaks X11, and on COSMIC the see-through parts of X11
 * windows are drawn black (cosmic-comp #677); the X11 window also stuttered.
 * Here the lyrics are drawn by Compose into an image (ImageComposeScene) and
 * shown on a Wayland "layer shell" surface: transparent, above other windows,
 * never taking keyboard focus, and repainted in step with the screen's refresh
 * (frame callbacks). The pointer drives the same Compose content (hover
 * controls, buttons, scrolling); the drag strip and the resize corner move
 * and resize the overlay.
 *
 * Desktops without the layer shell (GNOME) keep the regular window.
 */
@OptIn(ExperimentalComposeUiApi::class)
internal object LinuxLyricsOverlay {
    /** True on a Wayland session whose compositor offers the layer shell. */
    val isAvailable: Boolean by lazy { !com.recordlabs.desktop.data.Platform.isWindows && runCatching { probe() }.getOrDefault(false) }

    /** Position (desktop coordinates, all screens) and size changed by the user: x, y, width, height. */
    @Volatile var onGeometryChanged: ((Int, Int, Int, Int) -> Unit)? = null

    /** A point on the screen the overlay should first appear on (the app window's centre), if known. */
    @Volatile var preferredPoint: (() -> java.awt.Point?)? = null

    private val commands = ConcurrentLinkedQueue<() -> Unit>()
    @Volatile private var thread: Thread? = null
    @Volatile private var running = false

    // Everything below is used only on the overlay thread. x/y are desktop
    // coordinates (logical pixels over all screens); margins are per screen.
    private var content: (@Composable () -> Unit)? = null
    private var visible = false
    private var x = 100
    private var y = 100
    private var width = 720
    private var height = 175

    /** Starts the overlay thread (once) with [content]; it stays hidden until [setVisible]. */
    fun start(content: @Composable () -> Unit, x: Int, y: Int, width: Int, height: Int) {
        post {
            this.content = content
            this.x = x; this.y = y; this.width = width; this.height = height
            Session.current?.contentChanged()
        }
        if (thread == null) {
            running = true
            thread = Thread({ runLoop() }, "record-labs-lyrics-overlay").apply { isDaemon = true; start() }
        }
    }

    fun setVisible(show: Boolean) = post {
        visible = show
        Session.current?.updateVisibility()
    }

    fun stop() = post {
        visible = false
        Session.current?.updateVisibility()
    }

    /** Called from the drag strip when the left button goes down: the overlay follows the pointer until release. */
    fun beginDrag() { Session.current?.beginGesture(Gesture.Move) }

    /** Called from the resize corner when the left button goes down. */
    fun beginResize() { Session.current?.beginGesture(Gesture.Resize) }

    private fun post(command: () -> Unit) { commands += command }

    private fun runLoop() {
        val session = runCatching { Session.connect() }.getOrNull()
        if (session == null) { running = false; thread = null; return }
        Session.current = session
        try {
            while (running) {
                while (true) commands.poll()?.invoke() ?: break
                session.step()
            }
        } catch (error: Throwable) {
            println("[LyricsOverlay] stopped: ${error.message}")
        } finally {
            runCatching { session.close() }
            Session.current = null
            thread = null
        }
    }

    private fun probe(): Boolean {
        if (System.getenv("WAYLAND_DISPLAY").isNullOrBlank() && System.getenv("WAYLAND_SOCKET").isNullOrBlank()) return false
        val wl = Wl.lib ?: return false
        val display = wl.wl_display_connect(null) ?: return false
        try {
            var found = false
            val registry = wl.wl_proxy_marshal_flags(display, 1, Wl.iface("wl_registry_interface"), 1, 0, null) ?: return false
            val global = object : RegistryGlobal {
                override fun invoke(data: Pointer?, registry: Pointer?, name: Int, iface: String?, version: Int) {
                    if (iface == "zwlr_layer_shell_v1") found = true
                }
            }
            val remove = object : RegistryRemove { override fun invoke(data: Pointer?, registry: Pointer?, name: Int) = Unit }
            val listener = Wl.listener(global, remove)
            wl.wl_proxy_add_listener(registry, listener, null)
            wl.wl_display_roundtrip(display)
            wl.wl_proxy_destroy(registry)
            return found
        } finally {
            wl.wl_display_disconnect(display)
        }
    }

    private enum class Gesture { None, Move, Resize }

    /** One Wayland connection with its globals, the surface while shown and the Compose scene. */
    private class Session(val display: Pointer) {
        private val wl = Wl.lib!!
        private var compositor: Pointer? = null
        private var compositorVersion = 1
        private var shm: Pointer? = null
        private var seat: Pointer? = null
        private var pointer: Pointer? = null
        private var layerShell: Pointer? = null
        private var xdgOutputManager: Pointer? = null

        /** One screen: its scale and its place on the desktop in logical pixels. */
        private inner class Output(val proxy: Pointer, val globalName: Int) {
            var scale = 1
            var pixelWidth = 0
            var pixelHeight = 0
            var geometryX = 0
            var geometryY = 0
            var logicalX = 0
            var logicalY = 0
            var logicalWidth = 0
            var logicalHeight = 0
            var logicalKnown = false
            val left get() = if (logicalKnown) logicalX else geometryX
            val top get() = if (logicalKnown) logicalY else geometryY
            val width get() = if (logicalKnown) logicalWidth else pixelWidth / scale.coerceAtLeast(1)
            val height get() = if (logicalKnown) logicalHeight else pixelHeight / scale.coerceAtLeast(1)
            fun contains(px: Int, py: Int) = width > 0 && px >= left && py >= top && px < left + width && py < top + height
        }
        private val outputs = mutableListOf<Output>()
        /** The screen the overlay is on (a layer surface lives on one screen). */
        private var current: Output? = null
        private val scale get() = current?.scale ?: 1
        private val outputLeft get() = current?.left ?: 0
        private val outputTop get() = current?.top ?: 0
        private val outputWidth get() = current?.width ?: 0
        private val outputHeight get() = current?.height ?: 0
        private fun outputFor(proxy: Pointer?) = outputs.firstOrNull { it.proxy == proxy }
        private val keepAlive = mutableListOf<Any>()

        private var surface: Pointer? = null
        private var layerSurface: Pointer? = null
        private var configured = false
        private var frameReady = true
        private var needsRender = true
        private var scene: ImageComposeScene? = null
        private var sceneWidth = 0
        private var sceneHeight = 0
        private var sceneScale = 0
        private val buffers = mutableListOf<ShmBuffer>()

        private var pointerInside = false
        private var pointerX = 0.0
        private var pointerY = 0.0
        private var primaryDown = false
        private var gesture = Gesture.None
        private var gestureStartX = 0.0
        private var gestureStartY = 0.0
        private var gestureStartRect = intArrayOf(0, 0, 0, 0)
        private var geometryDirty = false
        private var lastEnterSerial = 0
        // Where the compositor has the overlay for the pointer events now arriving:
        // a moved overlay counts only once a sync round trip confirmed the move.
        private var effectiveX = 0
        private var effectiveY = 0
        private var moveSyncPending = false
        private var cursorSurface: Pointer? = null

        fun contentChanged() {
            scene?.setContent { LinuxLyricsOverlay.content?.invoke() }
            needsRender = true
        }

        fun updateVisibility() {
            if (visible && content != null && surface == null) createSurface()
            if (!visible && surface != null) destroySurface()
        }

        fun beginGesture(kind: Gesture) {
            if (!primaryDown) return
            gesture = kind
            effectiveX = x; effectiveY = y
            firstMoveAcked = false
            firstMoveCommitted = false
            lastLocalX = pointerX; lastLocalY = pointerY
            gestureStartX = effectiveX + pointerX
            gestureStartY = effectiveY + pointerY
            gestureStartRect = intArrayOf(x, y, width, height)
        }

        fun step() {
            if (surface != null && configured && frameReady) {
                val sizeChanged = sceneWidth != width * scale || sceneHeight != height * scale || sceneScale != scale
                if (sizeChanged || needsRender || scene?.hasInvalidations() == true) render()
            }
            wl.wl_display_dispatch_pending(display)
            wl.wl_display_flush(display)
            if (wl.wl_display_prepare_read(display) == 0) {
                val fds = Memory(8).apply { setInt(0, wl.wl_display_get_fd(display)); setShort(4, 1); setShort(6, 0) }
                val ready = Libc.lib.poll(fds, 1, if (frameReady && configured && surface != null) 4 else 16)
                if (ready > 0 && (fds.getShort(6).toInt() and 1) != 0) wl.wl_display_read_events(display) else wl.wl_display_cancel_read(display)
            }
            wl.wl_display_dispatch_pending(display)
        }

        private fun createSurface() {
            val compositor = compositor ?: return
            val layerShell = layerShell ?: return
            val firstTime = x < 0 || y < 0
            if (current == null || current !in outputs) current = chooseOutput(firstTime)
            val surface = wl.wl_proxy_marshal_flags(compositor, 0, Wl.iface("wl_surface_interface"), compositorVersion, 0, null) ?: return
            this.surface = surface
            if (firstTime) {
                // First time: centred near the bottom of the screen, like on Windows.
                x = outputLeft + ((outputWidth - width) / 2).coerceAtLeast(0)
                y = outputTop + (outputHeight - height - 80).coerceAtLeast(0)
            }
            clampPosition()
            val layer = wl.wl_proxy_marshal_flags(
                layerShell, 0, Wl.layerSurfaceInterface, 1, 0,
                null, surface, current?.proxy, LAYER_TOP, "record-labs-lyrics",
            ) ?: return
            layerSurface = layer
            wl.wl_proxy_add_listener(layer, layerListener, null)
            wl.wl_proxy_marshal_flags(layer, 0, null, 1, 0, width, height) // set_size
            wl.wl_proxy_marshal_flags(layer, 1, null, 1, 0, ANCHOR_TOP or ANCHOR_LEFT) // set_anchor
            wl.wl_proxy_marshal_flags(layer, 3, null, 1, 0, y - outputTop, 0, 0, x - outputLeft) // set_margin (per screen)
            wl.wl_proxy_marshal_flags(layer, 4, null, 1, 0, 0) // no keyboard focus
            if (compositorVersion >= 3) wl.wl_proxy_marshal_flags(surface, 8, null, compositorVersion, 0, scale) // set_buffer_scale
            wl.wl_proxy_marshal_flags(surface, 6, null, compositorVersion, 0) // commit
            configured = false
            frameReady = true
            needsRender = true
        }

        private fun destroySurface() {
            layerSurface?.let { wl.wl_proxy_marshal_flags(it, 7, null, 1, WL_MARSHAL_FLAG_DESTROY) }
            surface?.let { wl.wl_proxy_marshal_flags(it, 0, null, compositorVersion, WL_MARSHAL_FLAG_DESTROY) }
            layerSurface = null
            surface = null
            configured = false
            pointerInside = false
            gesture = Gesture.None
            wl.wl_display_flush(display)
        }

        private fun render() {
            val surface = surface ?: return
            val pixelWidth = width * scale
            val pixelHeight = height * scale
            if (scene == null || sceneWidth != pixelWidth || sceneHeight != pixelHeight || sceneScale != scale) {
                scene?.close()
                scene = ImageComposeScene(pixelWidth, pixelHeight, Density(scale.toFloat())) { LinuxLyricsOverlay.content?.invoke() }
                sceneWidth = pixelWidth; sceneHeight = pixelHeight; sceneScale = scale
                if (pointerInside) { send(PointerEventType.Enter); send(PointerEventType.Move) }
            }
            val buffer = freeBuffer(pixelWidth, pixelHeight) ?: return
            val image = scene!!.render(System.nanoTime())
            val pixmap = Pixmap.make(ImageInfo(pixelWidth, pixelHeight, ColorType.BGRA_8888, ColorAlphaType.PREMUL), Pointer.nativeValue(buffer.memory), pixelWidth * 4)
            image.readPixels(pixmap, 0, 0, false)
            pixmap.close()
            image.close()
            buffer.busy = true
            if (compositorVersion >= 3) wl.wl_proxy_marshal_flags(surface, 8, null, compositorVersion, 0, scale)
            wl.wl_proxy_marshal_flags(surface, 1, null, compositorVersion, 0, buffer.proxy, 0, 0) // attach
            wl.wl_proxy_marshal_flags(surface, 2, null, compositorVersion, 0, 0, 0, width, height) // damage
            val callback = wl.wl_proxy_marshal_flags(surface, 3, Wl.iface("wl_callback_interface"), compositorVersion, 0, null)
            if (callback != null) {
                wl.wl_proxy_add_listener(callback, frameListener, null)
                frameReady = false
            }
            wl.wl_proxy_marshal_flags(surface, 6, null, compositorVersion, 0) // commit
            needsRender = false
        }

        private fun freeBuffer(pixelWidth: Int, pixelHeight: Int): ShmBuffer? {
            buffers.filter { (it.width != pixelWidth || it.height != pixelHeight) && !it.busy }.forEach { it.destroy(); buffers.remove(it) }
            buffers.firstOrNull { it.width == pixelWidth && it.height == pixelHeight && !it.busy }?.let { return it }
            if (buffers.count { it.width == pixelWidth && it.height == pixelHeight } >= 2) return null
            val shm = shm ?: return null
            return ShmBuffer.create(wl, shm, pixelWidth, pixelHeight, bufferListener)?.also { buffers += it }
        }

        private fun clampPosition() {
            if (outputWidth > 0) x = x.coerceIn(outputLeft, outputLeft + (outputWidth - width).coerceAtLeast(0))
            if (outputHeight > 0) y = y.coerceIn(outputTop, outputTop + (outputHeight - height).coerceAtLeast(0))
        }

        /**
         * The screen for the overlay: where it was last (its saved position),
         * the first time the screen with the app window, else the first screen.
         */
        private fun chooseOutput(firstTime: Boolean): Output? {
            if (outputs.isEmpty()) return null
            if (!firstTime) outputs.firstOrNull { it.contains(x + width / 2, y + height / 2) }?.let { return it }
            if (!firstTime) outputs.firstOrNull { it.contains(x, y) }?.let { return it }
            val point = runCatching { preferredPoint?.invoke() }.getOrNull()
            if (point != null) {
                outputs.firstOrNull { it.contains(point.x, point.y) }?.let { return it }
                // XWayland may report scaled coordinates.
                outputs.firstOrNull { o -> o.scale > 1 && o.contains(point.x / o.scale, point.y / o.scale) }?.let { return it }
            }
            return outputs.first()
        }

        /** Puts the overlay on [target] at the current position (clamped there): a new surface on that screen. */
        private fun moveToOutput(target: Output) {
            destroySurface()
            current = target
            clampPosition()
            createSurface()
            val rect = intArrayOf(x, y, width, height)
            java.awt.EventQueue.invokeLater { onGeometryChanged?.invoke(rect[0], rect[1], rect[2], rect[3]) }
        }

        private fun send(type: PointerEventType, scroll: Offset = Offset.Zero) {
            val scene = scene ?: return
            scene.sendPointerEvent(
                eventType = type,
                position = Offset((pointerX * scale).toFloat(), (pointerY * scale).toFloat()),
                scrollDelta = scroll,
                timeMillis = System.currentTimeMillis(),
                buttons = PointerButtons(isPrimaryPressed = primaryDown),
                button = if (type == PointerEventType.Press || type == PointerEventType.Release) PointerButton.Primary else null,
            )
            needsRender = true
        }

        /**
         * Compositors differ in which overlay position they measure pointer
         * coordinates from while a button is held: the position at the press
         * (sway, and smithay-based COSMIC) or the current one. Unknown until the
         * first move of a drag shows it (null), then kept for the session.
         */
        private var pointerFollowsMovedSurface: Boolean? = null
        private var firstMoveX = 0
        private var firstMoveY = 0
        private var firstMoveAcked = false
        private var firstMoveCommitted = false
        private var lastLocalX = 0.0
        private var lastLocalY = 0.0

        private fun followGesture() {
            when (gesture) {
                Gesture.Move -> followMove()
                Gesture.Resize -> {
                    // The top-left corner stays put, so pointer coordinates keep their origin.
                    val dx = (pointerX - (gestureStartX - gestureStartRect[0])).roundToInt()
                    val dy = (pointerY - (gestureStartY - gestureStartRect[1])).roundToInt()
                    val layer = layerSurface ?: return
                    val surface = surface ?: return
                    width = (gestureStartRect[2] + dx).coerceAtLeast(MIN_WIDTH)
                    height = (gestureStartRect[3] + dy).coerceAtLeast(MIN_HEIGHT)
                    if (outputWidth > 0) width = width.coerceAtMost(outputLeft + outputWidth - x)
                    if (outputHeight > 0) height = height.coerceAtMost(outputTop + outputHeight - y)
                    wl.wl_proxy_marshal_flags(layer, 0, null, 1, 0, width, height)
                    wl.wl_proxy_marshal_flags(surface, 6, null, compositorVersion, 0)
                    geometryDirty = true
                }
                Gesture.None -> Unit
            }
        }

        private fun followMove() {
            if (pointerFollowsMovedSurface == null && firstMoveAcked) {
                // In the drag direction: coordinates still from the press position.
                // Jumped back by about the move: from the moved overlay.
                val stepX = pointerX - lastLocalX
                val stepY = pointerY - lastLocalY
                val along = stepX * firstMoveX + stepY * firstMoveY
                val moveSquared = (firstMoveX * firstMoveX + firstMoveY * firstMoveY).toDouble()
                pointerFollowsMovedSurface = along < -0.5 * moveSquared
            }
            lastLocalX = pointerX
            lastLocalY = pointerY
            val originX = if (pointerFollowsMovedSurface == true) effectiveX else gestureStartRect[0]
            val originY = if (pointerFollowsMovedSurface == true) effectiveY else gestureStartRect[1]
            val dx = (originX + pointerX - gestureStartX).roundToInt()
            val dy = (originY + pointerY - gestureStartY).roundToInt()
            if (pointerFollowsMovedSurface == null) {
                // Learning drag: one clear first move (past a small threshold), then
                // nothing more until the compositor has applied it.
                if (firstMoveCommitted) return
                if (dx * dx + dy * dy < 12 * 12) return
                firstMoveCommitted = true
                firstMoveX = dx
                firstMoveY = dy
            }
            x = gestureStartRect[0] + dx
            y = gestureStartRect[1] + dy
            // Dragged over onto another screen: the overlay continues there (a layer
            // surface belongs to one screen, so it is recreated on the other one).
            val centreX = x + width / 2
            val centreY = y + height / 2
            val other = outputs.firstOrNull { it !== current && it.contains(centreX, centreY) }
            if (other != null && current?.contains(centreX, centreY) != true) {
                gesture = Gesture.None
                geometryDirty = false
                moveToOutput(other)
                return
            }
            clampPosition()
            geometryDirty = true
            if (!moveSyncPending) commitMove()
        }

        /** Moves the overlay to (x, y) and learns, through a sync round trip, when pointer events use the new place. */
        private fun commitMove() {
            val layer = layerSurface ?: return
            val surface = surface ?: return
            val targetX = x
            val targetY = y
            wl.wl_proxy_marshal_flags(layer, 3, null, 1, 0, targetY - outputTop, 0, 0, targetX - outputLeft)
            wl.wl_proxy_marshal_flags(surface, 6, null, compositorVersion, 0)
            val callback = wl.wl_proxy_marshal_flags(display, 0, Wl.iface("wl_callback_interface"), 1, 0, null) ?: return
            moveSyncPending = true
            pendingMoveX = targetX
            pendingMoveY = targetY
            wl.wl_proxy_add_listener(callback, moveSyncListener, null)
        }
        private var pendingMoveX = 0
        private var pendingMoveY = 0
        private val moveSyncDone = object : CallbackDone {
            override fun invoke(data: Pointer?, callback: Pointer?, time: Int) {
                callback?.let { wl.wl_proxy_destroy(it) }
                moveSyncPending = false
                effectiveX = pendingMoveX
                effectiveY = pendingMoveY
                if (firstMoveCommitted) firstMoveAcked = true
                if (gesture == Gesture.Move && (x != effectiveX || y != effectiveY)) commitMove()
            }
        }
        private val moveSyncListener = Wl.listener(moveSyncDone)

        private fun endGesture() {
            if (gesture == Gesture.None) return
            // Pointer coordinates were relative to the moving overlay; reset them.
            gesture = Gesture.None
            if (geometryDirty) {
                geometryDirty = false
                val rect = intArrayOf(x, y, width, height)
                java.awt.EventQueue.invokeLater { onGeometryChanged?.invoke(rect[0], rect[1], rect[2], rect[3]) }
            }
        }

        private fun setCursor(serial: Int) {
            val pointer = pointer ?: return
            val cursor = Cursor.leftPointer(shm ?: return) ?: return
            val cursorSurface = cursorSurface ?: wl.wl_proxy_marshal_flags(compositor ?: return, 0, Wl.iface("wl_surface_interface"), compositorVersion, 0, null)?.also { cursorSurface = it } ?: return
            wl.wl_proxy_marshal_flags(cursorSurface, 1, null, compositorVersion, 0, cursor.buffer, 0, 0)
            wl.wl_proxy_marshal_flags(cursorSurface, 2, null, compositorVersion, 0, 0, 0, cursor.width, cursor.height)
            wl.wl_proxy_marshal_flags(cursorSurface, 6, null, compositorVersion, 0)
            wl.wl_proxy_marshal_flags(pointer, 0, null, 1, 0, serial, cursorSurface, cursor.hotspotX, cursor.hotspotY)
        }

        // --- Wayland event handlers -------------------------------------------------

        private val registryGlobal = object : RegistryGlobal {
            override fun invoke(data: Pointer?, registry: Pointer?, name: Int, iface: String?, version: Int) {
                val reg = registry ?: return
                when (iface) {
                    "wl_compositor" -> { compositorVersion = minOf(version, 4); compositor = bind(reg, name, "wl_compositor_interface", compositorVersion) }
                    "wl_shm" -> shm = bind(reg, name, "wl_shm_interface", 1)
                    "wl_seat" -> seat = bind(reg, name, "wl_seat_interface", 1)?.also { wl.wl_proxy_add_listener(it, seatListener, null) }
                    "wl_output" -> bind(reg, name, "wl_output_interface", minOf(version, 2))?.let {
                        val output = Output(it, name)
                        outputs += output
                        wl.wl_proxy_add_listener(it, outputListener, null)
                        watchLogicalGeometry(output)
                    }
                    "zxdg_output_manager_v1" -> xdgOutputManager = wl.wl_proxy_marshal_flags(reg, 0, Wl.xdgOutputManagerInterface, 1, 0, name, "zxdg_output_manager_v1", 1, null)
                    "zwlr_layer_shell_v1" -> layerShell = wl.wl_proxy_marshal_flags(reg, 0, Wl.layerShellInterface, 1, 0, name, "zwlr_layer_shell_v1", 1, null)
                }
            }
        }
        private val registryRemove = object : RegistryRemove {
            override fun invoke(data: Pointer?, registry: Pointer?, name: Int) {
                val gone = outputs.firstOrNull { it.globalName == name } ?: return
                outputs.remove(gone)
                if (gone === current) {
                    // Its screen was unplugged: show the overlay on another one.
                    current = null
                    if (surface != null) { destroySurface(); updateVisibility() }
                }
            }
        }

        /** Logical (scaled) position and size of a screen, from xdg-output. */
        private fun watchLogicalGeometry(output: Output) {
            val manager = xdgOutputManager ?: return
            wl.wl_proxy_marshal_flags(manager, 1, Wl.xdgOutputInterface, 1, 0, null, output.proxy)?.let {
                xdgOutputs[it] = output
                wl.wl_proxy_add_listener(it, xdgOutputListener, null)
            }
        }
        private val xdgOutputs = mutableMapOf<Pointer, Output>()

        private fun bind(registry: Pointer, name: Int, symbol: String, version: Int): Pointer? {
            val iface = Wl.iface(symbol) ?: return null
            val ifaceName = iface.getPointer(0).getString(0)
            return wl.wl_proxy_marshal_flags(registry, 0, iface, version, 0, name, ifaceName, version, null)
        }

        private val seatCapabilities = object : SeatCapabilities {
            override fun invoke(data: Pointer?, seat: Pointer?, capabilities: Int) {
                if (capabilities and 1 != 0 && pointer == null && seat != null) {
                    pointer = wl.wl_proxy_marshal_flags(seat, 0, Wl.iface("wl_pointer_interface"), 1, 0, null)?.also {
                        wl.wl_proxy_add_listener(it, pointerListener, null)
                    }
                }
            }
        }
        private val seatName = object : SeatName { override fun invoke(data: Pointer?, seat: Pointer?, name: Pointer?) = Unit }

        private val pointerEnter = object : PointerEnter {
            override fun invoke(data: Pointer?, p: Pointer?, serial: Int, surf: Pointer?, sx: Int, sy: Int) {
                if (surf == null || surf != surface) return
                pointerInside = true
                pointerX = sx / 256.0; pointerY = sy / 256.0
                lastEnterSerial = serial
                runCatching { setCursor(serial) }
                send(PointerEventType.Enter)
            }
        }
        private val pointerLeave = object : PointerLeave {
            override fun invoke(data: Pointer?, p: Pointer?, serial: Int, surf: Pointer?) {
                if (!pointerInside) return
                if (gesture != Gesture.None) return // the pointer briefly leaves while the overlay catches up
                pointerInside = false
                primaryDown = false
                send(PointerEventType.Exit)
            }
        }
        private val pointerMotion = object : PointerMotion {
            override fun invoke(data: Pointer?, p: Pointer?, time: Int, sx: Int, sy: Int) {
                pointerX = sx / 256.0; pointerY = sy / 256.0
                if (gesture != Gesture.None) followGesture() else if (pointerInside) send(PointerEventType.Move)
            }
        }
        private val pointerButton = object : PointerButtonEvent {
            override fun invoke(data: Pointer?, p: Pointer?, serial: Int, time: Int, button: Int, state: Int) {
                if (button != BTN_LEFT) return
                if (state == 1) {
                    primaryDown = true
                    send(PointerEventType.Press)
                } else {
                    primaryDown = false
                    val wasGesture = gesture != Gesture.None
                    endGesture()
                    send(PointerEventType.Release)
                    if (wasGesture) send(PointerEventType.Move)
                }
            }
        }
        private val pointerAxis = object : PointerAxis {
            override fun invoke(data: Pointer?, p: Pointer?, time: Int, axis: Int, value: Int) {
                if (axis != 0 || !pointerInside) return
                send(PointerEventType.Scroll, Offset(0f, (value / 256f) / 10f))
            }
        }

        private val outputGeometry = object : OutputGeometry {
            override fun invoke(data: Pointer?, o: Pointer?, x: Int, y: Int, pw: Int, ph: Int, sub: Int, make: Pointer?, model: Pointer?, transform: Int) {
                outputFor(o)?.let { it.geometryX = x; it.geometryY = y }
            }
        }
        private val outputMode = object : OutputMode {
            override fun invoke(data: Pointer?, o: Pointer?, flags: Int, w: Int, h: Int, refresh: Int) {
                if (flags and 1 != 0) outputFor(o)?.let { it.pixelWidth = w; it.pixelHeight = h }
            }
        }
        private val outputDone = object : OutputDone { override fun invoke(data: Pointer?, o: Pointer?) = Unit }
        private val outputScale = object : OutputScale {
            override fun invoke(data: Pointer?, o: Pointer?, factor: Int) {
                outputFor(o)?.let { it.scale = factor.coerceIn(1, 4); if (it === current) needsRender = true }
            }
        }
        // A screen's place and size in logical pixels (right also with fractional scaling, e.g. 125 %).
        private val xdgLogicalPosition = object : XdgOutputPair {
            override fun invoke(data: Pointer?, o: Pointer?, a: Int, b: Int) {
                xdgOutputs[o]?.let { it.logicalX = a; it.logicalY = b }
            }
        }
        private val xdgLogicalSize = object : XdgOutputPair {
            override fun invoke(data: Pointer?, o: Pointer?, a: Int, b: Int) {
                if (a > 0 && b > 0) xdgOutputs[o]?.let { it.logicalWidth = a; it.logicalHeight = b; it.logicalKnown = true }
            }
        }
        private val xdgDone = object : OutputDone { override fun invoke(data: Pointer?, o: Pointer?) = Unit }
        private val xdgOutputListener = Wl.listener(xdgLogicalPosition, xdgLogicalSize, xdgDone)

        private val layerConfigure = object : LayerConfigure {
            override fun invoke(data: Pointer?, layer: Pointer?, serial: Int, w: Int, h: Int) {
                layer ?: return
                wl.wl_proxy_marshal_flags(layer, 6, null, 1, 0, serial) // ack_configure
                if (w > 0 && h > 0 && gesture != Gesture.Resize) { width = w; height = h }
                configured = true
                needsRender = true
            }
        }
        private val layerClosed = object : LayerClosed {
            override fun invoke(data: Pointer?, layer: Pointer?) {
                // The compositor closed it (e.g. its screen went away): show it again elsewhere.
                destroySurface()
                if (current !in outputs) current = null
                updateVisibility()
            }
        }
        private val frameDone = object : CallbackDone {
            override fun invoke(data: Pointer?, callback: Pointer?, time: Int) {
                callback?.let { wl.wl_proxy_destroy(it) }
                frameReady = true
            }
        }
        private val bufferRelease = object : BufferRelease {
            override fun invoke(data: Pointer?, buffer: Pointer?) {
                buffers.firstOrNull { it.proxy == buffer }?.busy = false
            }
        }

        private val seatListener = Wl.listener(seatCapabilities, seatName)
        private val pointerListener = Wl.listener(pointerEnter, pointerLeave, pointerMotion, pointerButton, pointerAxis)
        private val outputListener = Wl.listener(outputGeometry, outputMode, outputDone, outputScale)
        private val layerListener = Wl.listener(layerConfigure, layerClosed)
        private val frameListener = Wl.listener(frameDone)
        private val bufferListener = Wl.listener(bufferRelease)

        fun setup() {
            val registry = wl.wl_proxy_marshal_flags(display, 1, Wl.iface("wl_registry_interface"), 1, 0, null) ?: error("no registry")
            keepAlive += Wl.listener(registryGlobal, registryRemove).also { wl.wl_proxy_add_listener(registry, it, null) }
            wl.wl_display_roundtrip(display)
            wl.wl_display_roundtrip(display) // seat capabilities, output modes and scale
            // Screens announced before the xdg-output manager get their logical geometry now.
            outputs.filter { o -> o !in xdgOutputs.values }.forEach(::watchLogicalGeometry)
            wl.wl_display_roundtrip(display)
            check(compositor != null && shm != null && layerShell != null) { "missing Wayland globals" }
        }

        fun close() {
            destroySurface()
            buffers.forEach { it.destroy() }
            buffers.clear()
            scene?.close()
            wl.wl_display_flush(display)
            wl.wl_display_disconnect(display)
        }

        companion object {
            @Volatile var current: Session? = null
            fun connect(): Session? {
                val wl = Wl.lib ?: return null
                val display = wl.wl_display_connect(null) ?: return null
                return Session(display).also { session ->
                    runCatching { session.setup() }.onFailure {
                        wl.wl_display_disconnect(display)
                        return null
                    }
                    session.updateVisibility()
                }
            }
        }
    }

    /** One shared-memory buffer (ARGB8888) the compositor reads the image from. */
    private class ShmBuffer(val proxy: Pointer, val memory: Pointer, val size: Long, val width: Int, val height: Int, private val wl: Wl.Client) {
        @Volatile var busy = false
        fun destroy() {
            wl.wl_proxy_marshal_flags(proxy, 0, null, 1, WL_MARSHAL_FLAG_DESTROY)
            Libc.lib.munmap(memory, size)
        }
        companion object {
            fun create(wl: Wl.Client, shm: Pointer, width: Int, height: Int, listener: Pointer): ShmBuffer? {
                val stride = width * 4
                val size = stride.toLong() * height
                val fd = Libc.lib.memfd_create("record-labs-lyrics", 1 /* MFD_CLOEXEC */)
                if (fd < 0) return null
                try {
                    if (Libc.lib.ftruncate(fd, size) != 0) return null
                    val memory = Libc.lib.mmap(null, size, 3 /* PROT_READ|WRITE */, 1 /* MAP_SHARED */, fd, 0) ?: return null
                    if (Pointer.nativeValue(memory) == -1L) return null
                    val pool = wl.wl_proxy_marshal_flags(shm, 0, Wl.iface("wl_shm_pool_interface"), 1, 0, null, fd, size.toInt()) ?: return null
                    val buffer = wl.wl_proxy_marshal_flags(pool, 0, Wl.iface("wl_buffer_interface"), 1, 0, null, 0, width, height, stride, 0 /* ARGB8888 */)
                    wl.wl_proxy_marshal_flags(pool, 1, null, 1, WL_MARSHAL_FLAG_DESTROY) // pool.destroy (buffer keeps the memory)
                    buffer ?: return null
                    wl.wl_proxy_add_listener(buffer, listener, null)
                    return ShmBuffer(buffer, memory, size, width, height, wl)
                } finally {
                    Libc.lib.close(fd)
                }
            }
        }
    }

    /** The system pointer image (cursor theme), set while the pointer is over the overlay. */
    private object Cursor {
        class Image(val buffer: Pointer, val width: Int, val height: Int, val hotspotX: Int, val hotspotY: Int)
        private var cached: Image? = null
        private var tried = false
        fun leftPointer(shm: Pointer): Image? {
            if (tried) return cached
            tried = true
            cached = runCatching {
                val lib = Native.load("wayland-cursor", CursorLib::class.java)
                val theme = lib.wl_cursor_theme_load(System.getenv("XCURSOR_THEME"), System.getenv("XCURSOR_SIZE")?.toIntOrNull() ?: 24, shm) ?: return@runCatching null
                val cursor = listOf("default", "left_ptr").firstNotNullOfOrNull { lib.wl_cursor_theme_get_cursor(theme, it) } ?: return@runCatching null
                val image = cursor.getPointer(8).getPointer(0)
                val buffer = lib.wl_cursor_image_get_buffer(image) ?: return@runCatching null
                Image(buffer, image.getInt(0), image.getInt(4), image.getInt(8), image.getInt(12))
            }.getOrNull()
            return cached
        }
    }

    private interface CursorLib : Library {
        fun wl_cursor_theme_load(name: String?, size: Int, shm: Pointer): Pointer?
        fun wl_cursor_theme_get_cursor(theme: Pointer, name: String): Pointer?
        fun wl_cursor_image_get_buffer(image: Pointer): Pointer?
    }

    /** libwayland-client, plus the layer-shell protocol description it doesn't ship. */
    private object Wl {
        interface Client : Library {
            fun wl_display_connect(name: String?): Pointer?
            fun wl_display_disconnect(display: Pointer)
            fun wl_display_get_fd(display: Pointer): Int
            fun wl_display_roundtrip(display: Pointer): Int
            fun wl_display_dispatch_pending(display: Pointer): Int
            fun wl_display_flush(display: Pointer): Int
            fun wl_display_prepare_read(display: Pointer): Int
            fun wl_display_read_events(display: Pointer): Int
            fun wl_display_cancel_read(display: Pointer)
            fun wl_proxy_add_listener(proxy: Pointer, implementation: Pointer, data: Pointer?): Int
            fun wl_proxy_destroy(proxy: Pointer)
            fun wl_proxy_marshal_flags(proxy: Pointer, opcode: Int, iface: Pointer?, version: Int, flags: Int, vararg args: Any?): Pointer?
        }

        val lib: Client? by lazy { runCatching { Native.load("wayland-client", Client::class.java) }.getOrNull() }
        private val native: NativeLibrary? by lazy { runCatching { NativeLibrary.getInstance("wayland-client") }.getOrNull() }
        private val keep = mutableListOf<Any>()

        fun iface(symbol: String): Pointer? = runCatching { native?.getGlobalVariableAddress(symbol) }.getOrNull()

        /** A listener table (one function pointer per event, in protocol order). */
        fun listener(vararg callbacks: Callback): Pointer {
            val table = Memory(Native.POINTER_SIZE.toLong() * callbacks.size)
            callbacks.forEachIndexed { index, callback ->
                table.setPointer(index.toLong() * Native.POINTER_SIZE, CallbackReference.getFunctionPointer(callback))
            }
            synchronized(keep) { keep.add(table); keep.addAll(callbacks) }
            return table
        }

        private fun cString(text: String): Memory = Memory(text.toByteArray().size + 1L).apply { setString(0, text) }.also { keep += it }

        /** struct wl_message { const char *name; const char *signature; const struct wl_interface **types; } */
        private fun messages(vararg items: Triple<String, String, List<Pointer?>>): Memory? {
            if (items.isEmpty()) return null
            val size = 3L * Native.POINTER_SIZE
            val block = Memory(size * items.size)
            items.forEachIndexed { index, (name, signature, types) ->
                val typeArray = Memory(Native.POINTER_SIZE.toLong() * types.size.coerceAtLeast(1))
                types.forEachIndexed { i, type -> typeArray.setPointer(i.toLong() * Native.POINTER_SIZE, type) }
                if (types.isEmpty()) typeArray.setPointer(0, null)
                keep += typeArray
                block.setPointer(index * size, cString(name))
                block.setPointer(index * size + Native.POINTER_SIZE, cString(signature))
                block.setPointer(index * size + 2 * Native.POINTER_SIZE, typeArray)
            }
            keep += block
            return block
        }

        /** struct wl_interface { const char *name; int version; int method_count; const wl_message *methods; int event_count; const wl_message *events; } */
        private fun fillInterface(target: Memory, name: String, version: Int, methods: Memory?, methodCount: Int, events: Memory?, eventCount: Int) {
            target.setPointer(0, cString(name))
            target.setInt(8, version)
            target.setInt(12, methodCount)
            target.setPointer(16, methods)
            target.setInt(24, eventCount)
            target.setPointer(32, events)
        }

        val layerSurfaceInterface: Memory = Memory(40).also { keep += it }
        val xdgOutputInterface: Memory = Memory(40).also { keep += it }
        val xdgOutputManagerInterface: Memory = Memory(40).also { keep += it }
        val layerShellInterface: Memory = Memory(40).also { keep += it }

        init {
            val n = null as Pointer?
            val surfaceMethods = messages(
                Triple("set_size", "uu", listOf(n, n)),
                Triple("set_anchor", "u", listOf(n)),
                Triple("set_exclusive_zone", "i", listOf(n)),
                Triple("set_margin", "iiii", listOf(n, n, n, n)),
                Triple("set_keyboard_interactivity", "u", listOf(n)),
                Triple("get_popup", "o", listOf(n)),
                Triple("ack_configure", "u", listOf(n)),
                Triple("destroy", "", emptyList()),
            )
            val surfaceEvents = messages(
                Triple("configure", "uuu", listOf(n, n, n)),
                Triple("closed", "", emptyList()),
            )
            fillInterface(layerSurfaceInterface, "zwlr_layer_surface_v1", 1, surfaceMethods, 8, surfaceEvents, 2)
            val shellMethods = messages(
                Triple("get_layer_surface", "no?ous", listOf(layerSurfaceInterface, iface("wl_surface_interface"), iface("wl_output_interface"), n, n)),
            )
            fillInterface(layerShellInterface, "zwlr_layer_shell_v1", 1, shellMethods, 1, null, 0)
            fillInterface(
                xdgOutputInterface, "zxdg_output_v1", 1,
                messages(Triple("destroy", "", emptyList())), 1,
                messages(Triple("logical_position", "ii", listOf(n, n)), Triple("logical_size", "ii", listOf(n, n)), Triple("done", "", emptyList())), 3,
            )
            fillInterface(
                xdgOutputManagerInterface, "zxdg_output_manager_v1", 1,
                messages(Triple("destroy", "", emptyList()), Triple("get_xdg_output", "no", listOf(xdgOutputInterface, iface("wl_output_interface")))), 2,
                null, 0,
            )
        }
    }

    private interface Libc : Library {
        fun memfd_create(name: String, flags: Int): Int
        fun ftruncate(fd: Int, length: Long): Int
        fun mmap(address: Pointer?, length: Long, protection: Int, flags: Int, fd: Int, offset: Long): Pointer?
        fun munmap(address: Pointer, length: Long): Int
        fun close(fd: Int): Int
        fun poll(fds: Pointer, count: Long, timeoutMs: Int): Int
        companion object { val lib: Libc by lazy { Native.load("c", Libc::class.java) } }
    }

    // Event callback signatures (data, proxy, arguments...).
    private interface RegistryGlobal : Callback { fun invoke(data: Pointer?, registry: Pointer?, name: Int, iface: String?, version: Int) }
    private interface RegistryRemove : Callback { fun invoke(data: Pointer?, registry: Pointer?, name: Int) }
    private interface SeatCapabilities : Callback { fun invoke(data: Pointer?, seat: Pointer?, capabilities: Int) }
    private interface SeatName : Callback { fun invoke(data: Pointer?, seat: Pointer?, name: Pointer?) }
    private interface PointerEnter : Callback { fun invoke(data: Pointer?, p: Pointer?, serial: Int, surf: Pointer?, sx: Int, sy: Int) }
    private interface PointerLeave : Callback { fun invoke(data: Pointer?, p: Pointer?, serial: Int, surf: Pointer?) }
    private interface PointerMotion : Callback { fun invoke(data: Pointer?, p: Pointer?, time: Int, sx: Int, sy: Int) }
    private interface PointerButtonEvent : Callback { fun invoke(data: Pointer?, p: Pointer?, serial: Int, time: Int, button: Int, state: Int) }
    private interface PointerAxis : Callback { fun invoke(data: Pointer?, p: Pointer?, time: Int, axis: Int, value: Int) }
    private interface OutputGeometry : Callback { fun invoke(data: Pointer?, o: Pointer?, x: Int, y: Int, pw: Int, ph: Int, sub: Int, make: Pointer?, model: Pointer?, transform: Int) }
    private interface OutputMode : Callback { fun invoke(data: Pointer?, o: Pointer?, flags: Int, w: Int, h: Int, refresh: Int) }
    private interface OutputDone : Callback { fun invoke(data: Pointer?, o: Pointer?) }
    private interface OutputScale : Callback { fun invoke(data: Pointer?, o: Pointer?, factor: Int) }
    private interface XdgOutputPair : Callback { fun invoke(data: Pointer?, o: Pointer?, a: Int, b: Int) }
    private interface LayerConfigure : Callback { fun invoke(data: Pointer?, layer: Pointer?, serial: Int, w: Int, h: Int) }
    private interface LayerClosed : Callback { fun invoke(data: Pointer?, layer: Pointer?) }
    private interface CallbackDone : Callback { fun invoke(data: Pointer?, callback: Pointer?, time: Int) }
    private interface BufferRelease : Callback { fun invoke(data: Pointer?, buffer: Pointer?) }

    private const val WL_MARSHAL_FLAG_DESTROY = 1
    private const val LAYER_TOP = 2
    private const val ANCHOR_TOP = 1
    private const val ANCHOR_LEFT = 4
    private const val BTN_LEFT = 0x110
    private const val MIN_WIDTH = 360
    private const val MIN_HEIGHT = 120
}
