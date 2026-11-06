package com.recordlabs.desktop

import androidx.compose.runtime.mutableStateOf
import java.awt.GraphicsEnvironment
import java.awt.Toolkit
import java.io.File
import java.util.prefs.Preferences
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.roundToInt
import kotlin.math.sqrt
import kotlin.system.exitProcess

/** Linux XWayland scale selection, hardware DPI detection, and diagnostics. */
internal object DesktopUiScale {
    private val prefs by lazy { Preferences.userRoot().node("com/recordlabs/desktop/display") }
    private val scales = listOf(0.75f, 0.9f, 1f, 1.1f, 1.25f, 1.5f, 1.75f, 2f)

    val options: List<Pair<String, String>> = listOf("auto" to "Automatic") +
        scales.map { it.toString() to "${(it * 100).toInt()}%" }

    fun isLinux(): Boolean = System.getProperty("os.name").lowercase().contains("linux")
    fun isWindows(): Boolean = System.getProperty("os.name").lowercase().startsWith("windows")

    val currentModeState = mutableStateOf(selectedMode())

    fun selectedMode(): String = prefs.get("scale", "auto").takeIf { mode ->
        mode == "auto" || scales.any { it.toString() == mode }
    } ?: "auto"

    fun saveMode(mode: String) {
        if (options.any { it.first == mode }) {
            prefs.put("scale", mode)
            currentModeState.value = mode
            runCatching { prefs.flush() }
        }
    }

    internal fun detectScaleForDisplay(diagInches: Double, hActive: Int, vActive: Int): Float {
        if (diagInches <= 0.0 || hActive <= 0 || vActive <= 0) return 1.0f

        val dpi = sqrt((hActive * hActive + vActive * vActive).toDouble()) / diagInches

        // 14" Full HD laptops:
        // Diagonals typically measure ~13.3" to ~14.5" (16:9 or 16:10).
        // Resolution is >= 1920x1080 (DPI ~ 155-165).
        // On 14" FHD, 100% (1.0x) makes the UI look physically too small.
        // The standard recommended scale (matching Windows default) is 125% (1.25f).
        if (diagInches in 12.5..14.6 && (hActive >= 1920 || vActive >= 1080)) {
            return 1.25f
        }

        // 15.6" Full HD (diagonal 15.0" to 16.5", resolution 1920x1080, DPI ~ 141):
        // 1.0x (100%) is comfortable and normal.
        if (diagInches in 14.8..16.5 && hActive <= 1920 && vActive <= 1200) {
            return 1.0f
        }

        // External desktop monitors (21" to 32" Full HD / QHD, DPI ~ 70-110):
        // 1.0x is standard.
        if (diagInches >= 20.0 && hActive <= 2560 && vActive <= 1440) {
            return 1.0f
        }

        // Ultra High-DPI displays (4K UHD or very dense screens):
        return when {
            dpi >= 250.0 -> 2.0f
            dpi >= 200.0 -> 1.5f
            dpi >= 150.0 && diagInches < 15.0 -> 1.25f
            else -> 1.0f
        }
    }

    private fun detectLaptopEdidScale(): Float? = runCatching {
        val drmDir = File("/sys/class/drm")
        if (!drmDir.isDirectory) return@runCatching null

        val cardDirs = drmDir.listFiles { f -> f.isDirectory && f.name.startsWith("card") } ?: return@runCatching null

        // 1. Prioritize internal laptop display (eDP)
        val edpDirs = cardDirs.filter { it.name.contains("eDP", ignoreCase = true) }
        val checkDirs = if (edpDirs.isNotEmpty()) edpDirs else cardDirs.toList()

        for (dir in checkDirs) {
            val statusFile = File(dir, "status")
            if (statusFile.isFile && statusFile.readText().trim() == "connected") {
                val edidFile = File(dir, "edid")
                if (edidFile.isFile && edidFile.length() >= 128) {
                    val bytes = edidFile.readBytes()
                    if (bytes.size >= 128 && bytes.sliceArray(0..7).contentEquals(byteArrayOf(0, -1, -1, -1, -1, -1, -1, 0))) {
                        val wCm = bytes[21].toInt() and 0xFF
                        val hCm = bytes[22].toInt() and 0xFF
                        if (wCm > 0 && hCm > 0) {
                            val diagInches = sqrt((wCm * wCm + hCm * hCm).toDouble()) / 2.54
                            // Preferred mode resolution from Detailed Timing Descriptor 1
                            val hActive = (bytes[56].toInt() and 0xFF) or ((bytes[58].toInt() and 0xF0) shl 4)
                            val vActive = (bytes[59].toInt() and 0xFF) or ((bytes[61].toInt() and 0xF0) shl 4)
                            if (hActive > 0 && vActive > 0) {
                                return@runCatching detectScaleForDisplay(diagInches, hActive, vActive)
                            }
                        }
                    }
                }
            }
        }
        null
    }.getOrNull()

    private val mutterScaleRegex = Regex("""\(\s*\d+\s*,\s*\d+\s*,\s*([0-9.]+)\s*,\s*uint32\s+\d+\s*,\s*(true|false)""")

    private fun mutterCurrentState(): String? = commandOutput(
        "gdbus", "call", "--session",
        "--dest", "org.gnome.Mutter.DisplayConfig",
        "--object-path", "/org/gnome/Mutter/DisplayConfig",
        "--method", "org.gnome.Mutter.DisplayConfig.GetCurrentState"
    )

    internal fun detectGnomeMutterScale(): Float? = runCatching {
        val output = mutterCurrentState() ?: return@runCatching null

        val matches = mutterScaleRegex.findAll(output).toList()
        for (match in matches) {
            val scaleStr = match.groupValues[1]
            val isPrimary = match.groupValues[2].equals("true", ignoreCase = true)
            if (isPrimary) {
                return@runCatching scaleStr.toFloatOrNull()
            }
        }
        matches.firstOrNull()?.groupValues?.get(1)?.toFloatOrNull()
    }.getOrNull()

    // ---------------------------------------------------------------------
    // XWayland native scaling (Fedora's GNOME default, upstream GNOME 50+)
    // ---------------------------------------------------------------------
    //
    // Fedora ships mutter with the "xwayland-native-scaling" feature turned on.
    // With it, X11 apps (Java/AWT always is one) are no longer stretched by the
    // compositor. Instead mutter gives the whole X screen one integer scale
    // G = ceil(largest monitor scale) and shrinks the result by scale/G when it
    // composites it: at 125% and 166% the X screen is "2x" and gets drawn at
    // 62.5% / 83%. An app that only multiplies by the fractional scale ends up
    // too small. GTK/Qt handle this by rendering at the integer factor G, and
    // so does Record Labs now (sun.java2d.uiScale = G).
    //
    // Debian / Ubuntu / other desktops don't have this setup: the detection
    // below then finds nothing and the original code path runs unchanged.

    /** X11 is rendered at [x11Scale] and mutter shows it at [monitorScale]. */
    internal data class XwaylandNativeScaling(val x11Scale: Int, val monitorScale: Float)

    internal data class MutterLogicalMonitor(
        val x: Int,
        val y: Int,
        val scale: Float,
        val transform: Int,
        val primary: Boolean,
        val connector: String,
    )

    internal data class MutterDisplayState(
        /** 1 = logical layout (scaled framebuffer), 2 = physical layout. */
        val layoutMode: Int?,
        /** Current mode size (pixels) per connector. */
        val currentModes: Map<String, Pair<Int, Int>>,
        val logicalMonitors: List<MutterLogicalMonitor>,
    ) {
        val primaryScale: Float?
            get() = (logicalMonitors.firstOrNull { it.primary } ?: logicalMonitors.firstOrNull())?.scale

        /** Width of the whole desktop in logical pixels, or null if it can't be worked out. */
        fun logicalLayoutWidth(): Int? {
            if (logicalMonitors.isEmpty()) return null
            var right = 0
            for (monitor in logicalMonitors) {
                val (w, h) = currentModes[monitor.connector] ?: return null
                if (monitor.scale <= 0f) return null
                // Transforms 1, 3, 5, 7 are rotated by 90°/270°.
                val pixelWidth = if (monitor.transform % 2 == 1) h else w
                right = maxOf(right, monitor.x + (pixelWidth / monitor.scale).roundToInt())
            }
            return right.takeIf { it > 0 }
        }
    }

    private val monitorBlockRegex = Regex("""\(\('([^']*)'""")
    private val currentModeRegex = Regex(
        """\('[^']*',\s*(\d+),\s*(\d+),\s*[0-9.eE+-]+,\s*[0-9.eE+-]+,\s*\[[^\]]*],\s*\{([^}]*)}\)"""
    )
    private val logicalMonitorRegex = Regex(
        """\(\s*(-?\d+)\s*,\s*(-?\d+)\s*,\s*([0-9.]+)\s*,\s*uint32\s+(\d+)\s*,\s*(true|false)\s*,\s*\[\s*\('([^']*)'"""
    )
    private val layoutModeRegex = Regex("""'layout-mode':\s*<uint32\s+(\d+)>""")

    /** Parses the text `gdbus call ... GetCurrentState` prints. */
    internal fun parseMutterState(output: String): MutterDisplayState {
        val layoutMode = layoutModeRegex.find(output)?.groupValues?.get(1)?.toIntOrNull()

        val logicalMonitors = logicalMonitorRegex.findAll(output).mapNotNull { m ->
            val scale = m.groupValues[3].toFloatOrNull() ?: return@mapNotNull null
            MutterLogicalMonitor(
                x = m.groupValues[1].toInt(),
                y = m.groupValues[2].toInt(),
                scale = scale,
                transform = m.groupValues[4].toIntOrNull() ?: 0,
                primary = m.groupValues[5] == "true",
                connector = m.groupValues[6],
            )
        }.toList()

        // The monitors list comes first; each entry starts with (('CONNECTOR', ...
        // and is followed by its modes. Logical monitors start with "(x, y," so
        // they never match the monitor-block pattern.
        val logicalStart = logicalMonitorRegex.find(output)?.range?.first ?: output.length
        val blocks = monitorBlockRegex.findAll(output).filter { it.range.first < logicalStart }.toList()
        val currentModes = mutableMapOf<String, Pair<Int, Int>>()
        blocks.forEachIndexed { index, block ->
            val end = if (index + 1 < blocks.size) blocks[index + 1].range.first else logicalStart
            val segment = output.substring(block.range.first, end)
            currentModeRegex.findAll(segment).firstOrNull { mode ->
                Regex("""'is-current':\s*<true>""").containsMatchIn(mode.groupValues[3])
            }?.let { mode ->
                currentModes[block.groupValues[1]] = mode.groupValues[1].toInt() to mode.groupValues[2].toInt()
            }
        }
        return MutterDisplayState(layoutMode, currentModes, logicalMonitors)
    }

    /**
     * Decides from mutter's layout and the X screen size whether XWayland runs
     * with native scaling. [x11ScreenWidth] is the X root window width (null if
     * unknown); [experimentalFeatures] is `org.gnome.mutter experimental-features`.
     */
    internal fun decideXwaylandNativeScaling(
        state: MutterDisplayState,
        x11ScreenWidth: Int?,
        experimentalFeatures: String?,
    ): XwaylandNativeScaling? {
        // Native scaling only exists with the scaled ("logical") layout.
        if (state.layoutMode != 1) return null
        val maxScale = state.logicalMonitors.maxOfOrNull { it.scale } ?: return null
        val primaryScale = state.primaryScale ?: return null
        // Integer factor mutter uses for the X screen (1.0000001 must stay 1).
        val g = ceil(maxScale - 0.01f).toInt()
        // At 100% there is nothing to fix; keep the original behaviour.
        if (g < 2) return null

        val layoutWidth = state.logicalLayoutWidth()
        if (x11ScreenWidth != null && x11ScreenWidth > 0 && layoutWidth != null) {
            val ratio = x11ScreenWidth.toDouble() / layoutWidth
            return when {
                // X screen is the logical desktop times G -> native scaling.
                abs(ratio - g) <= 0.04 * g -> XwaylandNativeScaling(g, primaryScale)
                // Anything else (the X screen is the logical desktop, i.e. the
                // compositor stretches X11 apps itself): not our case.
                else -> null
            }
        }
        // X screen size unavailable: fall back to the GNOME setting.
        val nativeFlag = experimentalFeatures?.contains("xwayland-native-scaling") == true
        return if (nativeFlag) XwaylandNativeScaling(g, primaryScale) else null
    }

    /** The Xlib calls needed to read the X screen size without starting AWT. */
    private interface XlibScreen : com.sun.jna.Library {
        fun XOpenDisplay(name: String?): com.sun.jna.Pointer?
        fun XDefaultScreen(display: com.sun.jna.Pointer): Int
        fun XDisplayWidth(display: com.sun.jna.Pointer, screen: Int): Int
        fun XCloseDisplay(display: com.sun.jna.Pointer): Int
    }

    private fun x11ScreenWidth(): Int? = runCatching {
        if (System.getenv("DISPLAY").isNullOrBlank()) return@runCatching null
        val xlib = com.sun.jna.Native.load("X11", XlibScreen::class.java)
        val display = xlib.XOpenDisplay(null) ?: return@runCatching null
        try {
            xlib.XDisplayWidth(display, xlib.XDefaultScreen(display)).takeIf { it > 0 }
        } finally {
            xlib.XCloseDisplay(display)
        }
    }.getOrNull()

    private fun isWaylandSession(): Boolean =
        !System.getenv("WAYLAND_DISPLAY").isNullOrBlank() ||
            System.getenv("XDG_SESSION_TYPE").equals("wayland", ignoreCase = true)

    /**
     * Detected once, before AWT starts (the X11 scale AWT uses can't change
     * afterwards anyway). Null everywhere except GNOME/Wayland with XWayland
     * native scaling active at a fractional or >= 2x scale.
     */
    internal val xwaylandNativeScaling: XwaylandNativeScaling? by lazy {
        if (!isLinux() || !isWaylandSession()) return@lazy null
        if (System.getenv("J2D_UISCALE") != null || System.getProperty("sun.java2d.uiScale") != null) {
            // Someone forced Java's scale by hand; respect it.
            return@lazy null
        }
        runCatching {
            val state = parseMutterState(mutterCurrentState() ?: return@runCatching null)
            decideXwaylandNativeScaling(
                state = state,
                x11ScreenWidth = x11ScreenWidth(),
                experimentalFeatures = commandOutput("gsettings", "get", "org.gnome.mutter", "experimental-features"),
            )
        }.getOrNull()
    }

    /**
     * Compose density multiplier on top of Java's own scale when XWayland
     * native scaling is active: Java already renders at G and mutter shows it
     * at the monitor scale, so Automatic needs no extra factor and a manual
     * choice is relative to the monitor scale.
     */
    internal fun nativeScalingDensity(mode: String, native: XwaylandNativeScaling): Float {
        if (mode == "auto") return 1.0f
        val wanted = mode.toFloatOrNull() ?: return 1.0f
        return wanted / native.monitorScale
    }

    /**
     * Detects optimal scale for Linux screens.
     * Respects GNOME Settings -> Displays -> Scale (100%, 125%, 166%, 200%),
     * GNOME text-scaling-factor / GDK_SCALE if configured,
     * or queries physical screen hardware EDID for 14" FHD laptops.
     */
    fun detectAutoLinuxScale(): Float {
        if (!isLinux()) return 1.0f

        // 1. Check GNOME Mutter Wayland Display Scale (directly reflects GNOME Settings -> Displays -> Scale)
        val mutterScale = detectGnomeMutterScale()
        if (mutterScale != null && mutterScale > 1.05f) {
            return mutterScale
        }

        // 2. Check GNOME text-scaling-factor (e.g. 1.25 if user enabled large text or custom scaling)
        val textScale = commandOutput("gsettings", "get", "org.gnome.desktop.interface", "text-scaling-factor")
            ?.toFloatOrNull()
        if (textScale != null && textScale > 1.05f) {
            return textScale
        }

        // 3. Check GDK_SCALE / GDK_DPI_SCALE
        System.getenv("GDK_SCALE")?.toFloatOrNull()?.let { if (it > 1.05f) return it }

        // 4. Hardware EDID check for laptop screens (eDP)
        val edidScale = detectLaptopEdidScale()
        if (edidScale != null) {
            return edidScale
        }

        return mutterScale ?: 1.0f
    }

    /**
     * The effective scale factor applied to Compose LocalDensity.
     * On Windows, returns 1.0f because Windows handles DPI scaling natively.
     */
    fun effectiveDensityScale(): Float {
        if (!isLinux()) return 1.0f
        val mode = currentModeState.value
        xwaylandNativeScaling?.let { return nativeScalingDensity(mode, it) }
        return if (mode == "auto") {
            detectAutoLinuxScale()
        } else {
            mode.toFloatOrNull() ?: 1.0f
        }
    }

    /** Must remain the first operation in main: AWT caches this during startup. */
    fun configureBeforeAwt() {
        if (!isLinux()) return
        val mode = selectedMode()
        val native = xwaylandNativeScaling
        if (native != null) {
            // Render X11 at mutter's integer factor, like GTK/Qt apps do there.
            System.setProperty("sun.java2d.uiScale", native.x11Scale.toString())
            val shown = if (mode == "auto") native.monitorScale else (mode.toFloatOrNull() ?: native.monitorScale)
            System.setProperty("recordlabs.displayScale", shown.toString())
            return
        }
        val scale = if (mode == "auto") detectAutoLinuxScale() else (mode.toFloatOrNull() ?: 1.0f)
        if (scale >= 1.9f) {
            // Integer 2.0x is supported natively by Java2D on X11 for 4K displays
            System.setProperty("sun.java2d.uiScale", "2")
        }
        System.setProperty("recordlabs.displayScale", scale.toString())
    }

    private fun commandOutput(vararg command: String): String? = runCatching {
        val process = ProcessBuilder(*command).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().use { it.readText() }
        if (process.waitFor() == 0) output.trim() else null
    }.getOrNull()

    /** Read-only startup report. Invoke with `record-labs --self-test-display`. */
    fun printSelfTest() {
        configureBeforeAwt()
        println("Record Labs display diagnostics")
        println("os.name=${System.getProperty("os.name")}")
        println("XDG_SESSION_TYPE=${System.getenv("XDG_SESSION_TYPE")}")
        println("XDG_CURRENT_DESKTOP=${System.getenv("XDG_CURRENT_DESKTOP")}")
        println("DISPLAY=${System.getenv("DISPLAY")}")
        println("WAYLAND_DISPLAY=${System.getenv("WAYLAND_DISPLAY")}")
        println("J2D_UISCALE=${System.getenv("J2D_UISCALE")}")
        println("GDK_SCALE=${System.getenv("GDK_SCALE")}")
        println("sun.java2d.uiScale=${System.getProperty("sun.java2d.uiScale")}")
        println("recordlabs.displayScale=${System.getProperty("recordlabs.displayScale")}")
        println("xwaylandNativeScaling=${xwaylandNativeScaling ?: "inactive"}")
        println("x11ScreenWidth=${x11ScreenWidth() ?: "unavailable"}")
        println("effectiveDensityScale=${effectiveDensityScale()}")
        println("detectedAutoScale=${detectAutoLinuxScale()}")
        println("xrandr:\n${commandOutput("xrandr", "--query") ?: "unavailable"}")
        println("Xft resources:\n${commandOutput("xrdb", "-query") ?: "unavailable"}")
        println("GNOME scaling-factor=${commandOutput("gsettings", "get", "org.gnome.desktop.interface", "scaling-factor") ?: "unavailable"}")
        println("GNOME text-scaling-factor=${commandOutput("gsettings", "get", "org.gnome.desktop.interface", "text-scaling-factor") ?: "unavailable"}")
        println("GNOME experimental-features=${commandOutput("gsettings", "get", "org.gnome.mutter", "experimental-features") ?: "unavailable"}")
        // Transform/screen queries intentionally happen after the startup values
        // are printed because they initialize AWT and freeze its effective scale.
        runCatching {
            val device = GraphicsEnvironment.getLocalGraphicsEnvironment().defaultScreenDevice
            val transform = device.defaultConfiguration.defaultTransform
            val size = Toolkit.getDefaultToolkit().screenSize
            println("awt.defaultTransform=${transform.scaleX}x${transform.scaleY}")
            println("awt.screenSize=${size.width}x${size.height}")
        }.onFailure { println("awt.displayError=${it.message}") }
        println("PiP Wayland per-output scale: not available in this XWayland build")
    }

    fun restartWithCurrentScale() {
        val info = ProcessHandle.current().info()
        val executable = info.command().orElse(null)
        val args = info.arguments().orElse(emptyArray())
        if (executable != null) {
            runCatching { ProcessBuilder(listOf(executable) + args).start() }
                .onSuccess {
                    com.recordlabs.desktop.data.AppUpdater.exitApp?.invoke() ?: exitProcess(0)
                    return
                }
        }
        // If the app was launched from an IDE and cannot re-exec itself, close
        // cleanly so it can be started again manually with the saved preference.
        com.recordlabs.desktop.data.AppUpdater.exitApp?.invoke() ?: exitProcess(0)
    }
}
