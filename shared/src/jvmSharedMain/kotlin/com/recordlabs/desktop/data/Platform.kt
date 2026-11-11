package com.recordlabs.desktop.data

import java.io.File
import java.net.InetAddress
import java.util.Locale

/**
 * The operating system Record Labs runs on, and the few places where Windows
 * and Linux differ (folders, bundled program names, device name).
 *
 * Everything that is the same on both systems keeps using the existing code;
 * only callers that touch the OS ask this object which path to take.
 */
object Platform {
    enum class Os { Windows, Linux, MacOs, Android, Other }

    val os: Os = System.getProperty("os.name").orEmpty().lowercase(Locale.ROOT).let { name ->
        val vm = (System.getProperty("java.vm.vendor").orEmpty() + System.getProperty("java.vendor").orEmpty()).lowercase(Locale.ROOT)
        when {
            // Android reports "Linux" as the OS name; its Java runtime gives it away.
            vm.contains("android") -> Os.Android
            name.startsWith("windows") -> Os.Windows
            name.contains("linux") -> Os.Linux
            name.contains("mac") || name.contains("darwin") -> Os.MacOs
            else -> Os.Other
        }
    }

    val isWindows: Boolean get() = os == Os.Windows
    val isLinux: Boolean get() = os == Os.Linux
    val isAndroid: Boolean get() = os == Os.Android

    /**
     * Android: the app's private data and cache folders, and how to open links
     * and name the device. The Android app sets these before anything else runs.
     */
    @Volatile var androidDataRoot: File? = null
    /** Android/data/<app>/files: diagnostics a computer can read (see AppPlatform.diagnosticsRoot). */
    @Volatile var androidDiagnosticsRoot: File? = null
    @Volatile var androidCacheRoot: File? = null
    @Volatile var androidDeviceName: String? = null
    /** Android: the app's package (application id); the releases are com.recordlabs.music.debug. */
    @Volatile var androidPackageName: String? = null

    /**
     * Android: a debug switch set with `adb shell setprop debug.recordlabs.<name> <value>`
     * (for measuring on a phone without a special build). Empty when not set, and always
     * on the desktop.
     */
    fun debugProp(name: String): String = if (!isAndroid) "" else runCatching {
        Class.forName("android.os.SystemProperties").getMethod("get", String::class.java)
            .invoke(null, "debug.recordlabs.$name") as String
    }.getOrDefault("")
    @Volatile var urlOpener: ((String) -> Unit)? = null

    /**
     * Android: the URI the player opens for a YouTube song (resolved by the
     * Android playback engine itself, like the original Android app), and its
     * resolver for everything else that needs a stream URL (downloads).
     */
    var androidStreamUri: ((String) -> String)? get() = StreamHooks.streamUri; set(value) { StreamHooks.streamUri = value }
    var androidStreamResolver: (suspend (String) -> String?)? get() = StreamHooks.streamResolver; set(value) { StreamHooks.streamResolver = value }
    /** Android: the signed-in account (name, photo) from the original Android app's client. */
    var androidAccountProfile: (suspend () -> GoogleAccountProfile?)? get() = StreamHooks.accountProfile; set(value) { StreamHooks.accountProfile = value }
    /** Android: true = signed in, false = the account request refused the session, null = could not tell (offline). */
    var androidValidateSession: (suspend () -> Boolean?)? get() = StreamHooks.validateSession; set(value) { StreamHooks.validateSession = value }
    /** Android: resolve a song and cache its first seconds ahead of time (next song in the queue). */
    var androidPrefetch: (suspend (String) -> Unit)? get() = StreamHooks.prefetch; set(value) { StreamHooks.prefetch = value }
    /** Android: binds a Record Labs Connect socket (DatagramSocket or Socket, not yet connected) to the Wi-Fi/Ethernet network. */
    @Volatile var lanSocketBinder: ((Any) -> Unit)? = null
    /** Android: opens a downloaded update (.apk) in the system installer; false when it could not. */
    @Volatile var apkInstaller: ((File) -> Boolean)? = null

    private val home: String get() = System.getProperty("user.home").orEmpty()

    /**
     * Root folder for the app's own data.
     * Windows: %LOCALAPPDATA%\RecordLabs (unchanged from earlier versions).
     * Linux: $XDG_DATA_HOME/record-labs, normally ~/.local/share/record-labs.
     */
    val dataRoot: File by lazy {
        androidDataRoot?.let { return@lazy it }
        if (isWindows) {
            val appData = System.getenv("LOCALAPPDATA")
                ?: System.getenv("APPDATA")
                ?: "$home/AppData/Local"
            File(appData, "RecordLabs")
        } else {
            val xdg = System.getenv("XDG_DATA_HOME")?.takeIf { it.isNotBlank() && File(it).isAbsolute }
                ?: "$home/.local/share"
            File(xdg, "record-labs")
        }
    }

    /** Temporary/cache root: %LOCALAPPDATA%\RecordLabs on Windows, ~/.cache/record-labs on Linux. */
    val cacheRoot: File by lazy {
        androidCacheRoot?.let { return@lazy it }
        if (isWindows) dataRoot else {
            val xdg = System.getenv("XDG_CACHE_HOME")?.takeIf { it.isNotBlank() && File(it).isAbsolute }
                ?: "$home/.cache"
            File(xdg, "record-labs")
        }
    }

    /** Folder name for bundled native files in a development checkout (src/appResources/<name>). */
    val nativeResourceDirName: String get() = if (isWindows) "windows-x64" else "linux-x64"

    /** Adds ".exe" on Windows: "deno" -> "deno.exe". */
    fun executableName(baseName: String): String = if (isWindows) "$baseName.exe" else baseName

    /** Name shown to other devices in Record Labs Connect. */
    val deviceName: String by lazy {
        androidDeviceName?.let { return@lazy it }
        val name = if (isWindows) System.getenv("COMPUTERNAME") else
            System.getenv("HOSTNAME")
                ?: runCatching { File("/etc/hostname").readText().trim() }.getOrNull()
                ?: runCatching { InetAddress.getLocalHost().hostName }.getOrNull()
        name.orEmpty().ifBlank { if (isWindows) "Windows PC" else "Linux PC" }
    }

    /**
     * Linux AppImage builds run from a new temporary mount every launch, so the
     * install location cannot identify the installation. Linux therefore uses
     * one stable data folder per user.
     */
    val usesStableInstallId: Boolean get() = !isWindows

    /** Opens a web link in the default browser. Linux falls back to xdg-open when AWT can't. */
    fun openUrl(url: String) {
        urlOpener?.let { opener -> runCatching { opener(url) }; return }
        val viaAwt = runCatching {
            val desktop = java.awt.Desktop.getDesktop()
            check(desktop.isSupported(java.awt.Desktop.Action.BROWSE))
            desktop.browse(java.net.URI(url))
        }.isSuccess
        if (!viaAwt && !isWindows) runCatching { ProcessBuilder("xdg-open", url).start() }
    }

    /** Opens a folder in the file manager. Linux falls back to xdg-open when AWT can't. */
    fun openFolder(folder: File) {
        val viaAwt = runCatching {
            val desktop = java.awt.Desktop.getDesktop()
            check(desktop.isSupported(java.awt.Desktop.Action.OPEN))
            desktop.open(folder)
        }.isSuccess
        if (!viaAwt && !isWindows) runCatching { ProcessBuilder("xdg-open", folder.absolutePath).start() }
    }

    /** Path of the running AppImage file, when Record Labs was started from one. */
    val appImagePath: File? get() = System.getenv("APPIMAGE")?.takeIf(String::isNotBlank)?.let(::File)?.takeIf(File::isFile)
}
