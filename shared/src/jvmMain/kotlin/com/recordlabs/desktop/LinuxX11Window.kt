package com.recordlabs.desktop

import com.sun.jna.Native
import com.sun.jna.NativeLong
import com.sun.jna.platform.unix.X11
import com.sun.jna.ptr.IntByReference
import com.sun.jna.ptr.NativeLongByReference
import com.sun.jna.ptr.PointerByReference

/**
 * Talks to the window manager about a Java window directly over X11 (Java on
 * Linux always runs on X11, on Wayland desktops through XWayland).
 *
 * Used for fullscreen: AWT's own fullscreen mode asks once and then puts the
 * old bounds back right away. On COSMIC the "leave fullscreen" request was
 * sometimes not applied, so the window stayed a fullscreen surface showing the
 * app at its old size in the middle of a grey screen. Here the request is
 * checked against the window's real state and repeated until it took effect.
 */
internal object LinuxX11Window {
    private val x11: X11? by lazy { runCatching { X11.INSTANCE }.getOrNull() }

    /** True when the window manager currently shows [window] fullscreen; null if it can't be read. */
    fun isFullscreen(window: java.awt.Window): Boolean? = withDisplay { display ->
        val x = x11 ?: return@withDisplay null
        val client = clientWindow(display, window) ?: return@withDisplay null
        val state = x.XInternAtom(display, "_NET_WM_STATE", false)
        val fullscreen = x.XInternAtom(display, "_NET_WM_STATE_FULLSCREEN", false)
        val actualType = X11.AtomByReference()
        val actualFormat = IntByReference()
        val items = NativeLongByReference()
        val after = NativeLongByReference()
        val data = PointerByReference()
        val status = x.XGetWindowProperty(
            display, client, state, NativeLong(0), NativeLong(64), false, X11.XA_ATOM,
            actualType, actualFormat, items, after, data,
        )
        if (status != 0) return@withDisplay null
        val pointer = data.value ?: return@withDisplay false
        try {
            val count = items.value.toInt()
            (0 until count).any { index ->
                val atom = if (Native.LONG_SIZE == 8) pointer.getLong(index * 8L) else pointer.getInt(index * 4L).toLong()
                atom == fullscreen.toLong()
            }
        } finally {
            x.XFree(pointer)
        }
    }

    /** Asks the window manager to add or remove fullscreen. False if X11 is not usable. */
    fun requestFullscreen(window: java.awt.Window, enabled: Boolean): Boolean = withDisplay { display ->
        val x = x11 ?: return@withDisplay false
        sendToWindowManager(
            display, window, "_NET_WM_STATE",
            if (enabled) 1L else 0L, // _NET_WM_STATE_ADD / _REMOVE
            x.XInternAtom(display, "_NET_WM_STATE_FULLSCREEN", false).toLong(),
            0L,
            1L, // request from a normal application
            0L,
        )
    } ?: false

    /** _NET_WM_MOVERESIZE directions. */
    const val SIZE_TOP_LEFT = 0L
    const val SIZE_TOP = 1L
    const val SIZE_TOP_RIGHT = 2L
    const val SIZE_RIGHT = 3L
    const val SIZE_BOTTOM_RIGHT = 4L
    const val SIZE_BOTTOM = 5L
    const val SIZE_BOTTOM_LEFT = 6L
    const val SIZE_LEFT = 7L
    const val MOVE = 8L

    /**
     * Lets the window manager move (or resize) [window] with the pointer, as when
     * dragging a title bar. Wayland desktops ignore an X11 app that sets its own
     * window position, so a borderless window can only be dragged this way.
     * Call while the left button is held down; [rootX]/[rootY] is the pointer on screen.
     */
    fun beginMoveResize(window: java.awt.Window, rootX: Int, rootY: Int, direction: Long): Boolean = withDisplay { display ->
        val x = x11 ?: return@withDisplay false
        // The pointer is still grabbed by AWT for the pressed button; the window
        // manager can only take over the drag once that grab is released.
        releaseAwtPointerGrab()
        sendToWindowManager(display, window, "_NET_WM_MOVERESIZE", rootX.toLong(), rootY.toLong(), direction, 1L, 1L)
    } ?: false

    /**
     * Releases AWT's pointer grab on its own X11 connection, under AWT's lock
     * (Xlib connections are not thread-safe). Needs the JVM option
     * --add-exports java.desktop/sun.awt=ALL-UNNAMED (and sun.awt.X11), which
     * the Linux build sets; without it the drag still works on desktops that
     * take the pointer over themselves (Wayland compositors through XWayland).
     */
    private fun releaseAwtPointerGrab() {
        val display = awtDisplay() ?: return
        val grabs = pointerGrabs ?: return
        val toolkit = runCatching { Class.forName("sun.awt.SunToolkit") }.getOrNull() ?: return
        val lock = runCatching { toolkit.getMethod("awtLock") }.getOrNull() ?: return
        val unlock = runCatching { toolkit.getMethod("awtUnlock") }.getOrNull() ?: return
        runCatching {
            lock.invoke(null)
            try {
                grabs.XUngrabPointer(display, NativeLong(0))
                grabs.XFlush(display)
            } finally {
                unlock.invoke(null)
            }
        }
    }

    /** AWT's own X11 connection (needed to release its pointer grab), when the JVM allows reaching it. */
    private fun awtDisplay(): com.sun.jna.Pointer? = runCatching {
        val toolkit = Class.forName("sun.awt.X11.XToolkit")
        val address = toolkit.getMethod("getDisplay").invoke(null) as Long
        if (address == 0L) null else com.sun.jna.Pointer(address)
    }.getOrNull()

    /** The Xlib calls JNA's X11 mapping lacks. */
    private interface PointerGrabs : com.sun.jna.Library {
        fun XUngrabPointer(display: com.sun.jna.Pointer, time: NativeLong): Int
        fun XFlush(display: com.sun.jna.Pointer): Int
    }
    private val pointerGrabs: PointerGrabs? by lazy { runCatching { Native.load("X11", PointerGrabs::class.java) }.getOrNull() }

    private fun sendToWindowManager(display: X11.Display, window: java.awt.Window, messageType: String, vararg data: Long): Boolean {
        val x = x11 ?: return false
        val client = clientWindow(display, window) ?: return false
        val event = X11.XEvent()
        event.type = X11.ClientMessage
        event.setType(X11.XClientMessageEvent::class.java)
        event.xclient.type = X11.ClientMessage
        event.xclient.serial = NativeLong(0)
        event.xclient.send_event = 1
        event.xclient.display = display
        event.xclient.window = client
        event.xclient.message_type = x.XInternAtom(display, messageType, false)
        event.xclient.format = 32
        event.xclient.data.setType(Array<NativeLong>::class.java)
        for (index in 0 until 5) event.xclient.data.l[index] = NativeLong(data.getOrElse(index) { 0L })
        event.xclient.write()
        val sent = x.XSendEvent(
            display, x.XDefaultRootWindow(display), 0,
            NativeLong((X11.SubstructureRedirectMask or X11.SubstructureNotifyMask).toLong()), event,
        )
        x.XFlush(display)
        return sent != 0
    }

    /**
     * The X11 window the window manager manages for [window]: AWT reports an
     * inner drawable, so this climbs to the highest ancestor that still carries
     * the app's WM_CLASS (window-manager frames don't).
     */
    private fun clientWindow(display: X11.Display, window: java.awt.Window): X11.Window? {
        val x = x11 ?: return null
        val id = runCatching { Native.getWindowID(window) }.getOrNull()?.takeIf { it != 0L } ?: return null
        val wmClass = x.XInternAtom(display, "WM_CLASS", false)
        var current = X11.Window(id)
        var best: X11.Window? = if (hasProperty(display, current, wmClass)) current else null
        repeat(8) {
            val root = X11.WindowByReference()
            val parent = X11.WindowByReference()
            val children = PointerByReference()
            val count = IntByReference()
            if (x.XQueryTree(display, current, root, parent, children, count) == 0) return best ?: current
            children.value?.let { x.XFree(it) }
            val up = parent.value ?: return best ?: current
            if (up.toLong() == 0L || up.toLong() == root.value?.toLong()) return best ?: current
            current = up
            if (hasProperty(display, current, wmClass)) best = current
        }
        return best ?: current
    }

    private fun hasProperty(display: X11.Display, window: X11.Window, property: X11.Atom): Boolean {
        val x = x11 ?: return false
        val actualType = X11.AtomByReference()
        val actualFormat = IntByReference()
        val items = NativeLongByReference()
        val after = NativeLongByReference()
        val data = PointerByReference()
        val status = x.XGetWindowProperty(
            display, window, property, NativeLong(0), NativeLong(1), false, X11.Atom(X11.AnyPropertyType.toLong()),
            actualType, actualFormat, items, after, data,
        )
        data.value?.let { x.XFree(it) }
        return status == 0 && (actualType.value?.toLong() ?: 0L) != 0L
    }

    private fun <T> withDisplay(block: (X11.Display) -> T): T? {
        val x = x11 ?: return null
        return runCatching {
            val display = x.XOpenDisplay(null) ?: return null
            try {
                block(display)
            } finally {
                x.XCloseDisplay(display)
            }
        }.getOrNull()
    }
}
