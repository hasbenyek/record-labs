package com.recordlabs.android

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.os.Build
import com.recordlabs.desktop.data.Platform
import java.io.File

/**
 * Prepares the shared code (written for the desktop JVM) for Android before
 * anything touches it: where its files and settings go, the device name, and
 * how links open.
 */
class RecordLabsApp : Application() {
    private var connectMulticastLock: android.net.wifi.WifiManager.MulticastLock? = null
    @Volatile private var lanNetwork: android.net.Network? = null

    override fun onCreate() {
        super.onCreate()
        recordCrashes()
        // The shared code was written for the desktop, where blocking work on the UI thread
        // is allowed; Android's default policy would turn it into crashes.
        android.os.StrictMode.setThreadPolicy(android.os.StrictMode.ThreadPolicy.Builder().permitAll().build())
        // The shared settings use java.util.prefs, which keeps its files under
        // user.home; on Android that must be the app's private folder.
        System.setProperty("user.home", filesDir.absolutePath)
        System.setProperty("java.util.prefs.userRoot", File(filesDir, "prefs").absolutePath)
        // The version shown in the app (Settings, About) is the Android app's own (versionName).
        runCatching { packageManager.getPackageInfo(packageName, 0).versionName }.getOrNull()
            ?.takeIf(String::isNotBlank)?.let { System.setProperty("recordlabs.app-version", it) }
        Platform.androidDataRoot = File(filesDir, "record-labs").apply { mkdirs() }
        Platform.androidCacheRoot = File(cacheDir, "record-labs").apply { mkdirs() }
        Platform.androidDeviceName = listOf(Build.MANUFACTURER, Build.MODEL)
            .filter { !it.isNullOrBlank() }
            .joinToString(" ")
            .ifBlank { "Android" }
        Platform.urlOpener = { url ->
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
        // The update banner's downloaded .apk, opened in Android's installer (which asks
        // to allow installing from Record Labs the first time).
        Platform.apkInstaller = { file ->
            runCatching {
                val uri = androidx.core.content.FileProvider.getUriForFile(this, "$packageName.updates", file)
                startActivity(
                    Intent(Intent.ACTION_VIEW)
                        .setDataAndType(uri, "application/vnd.android.package-archive")
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK),
                )
                true
            }.getOrDefault(false)
        }
        com.recordlabs.desktop.AndroidAppContext.init(this)
        // Discord Rich Presence with a Discord login (Settings > Integrations > Discord).
        runCatching { com.recordlabs.desktop.data.AndroidDiscord.install(this) }
        // Android drops multicast/broadcast packets unless an app holds this lock:
        // without it Record Labs Connect (devices list, handoff) never sees other devices.
        // The lock is kept for the whole process: an unreferenced lock is released when it
        // is garbage-collected, which silently stopped discovery soon after launch.
        runCatching {
            val wifi = applicationContext.getSystemService(android.net.wifi.WifiManager::class.java)
            connectMulticastLock = wifi?.createMulticastLock("record-labs-connect")
                ?.apply { setReferenceCounted(false); acquire() }
        }
        // Connect's sockets go over Wi-Fi/Ethernet even when that is not the default network
        // (Wi-Fi without internet, mobile data preferred, a VPN on).
        runCatching {
            val connectivity = getSystemService(android.net.ConnectivityManager::class.java)
            val request = android.net.NetworkRequest.Builder()
                .addTransportType(android.net.NetworkCapabilities.TRANSPORT_WIFI)
                .addTransportType(android.net.NetworkCapabilities.TRANSPORT_ETHERNET)
                .removeCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build()
            connectivity.registerNetworkCallback(request, object : android.net.ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: android.net.Network) { lanNetwork = network }
                override fun onLost(network: android.net.Network) { if (lanNetwork == network) lanNetwork = null }
            })
            Platform.lanSocketBinder = { socket ->
                lanNetwork?.let { network ->
                    when (socket) {
                        is java.net.DatagramSocket -> network.bindSocket(socket)
                        is java.net.Socket -> network.bindSocket(socket)
                    }
                }
            }
        }
        com.recordlabs.desktop.data.AndroidPlaybackEngine.init(this)
    }

    /**
     * Keeps the last crash in Android/data/com.recordlabs.android/files/crash.txt
     * (readable with "adb shell cat"), then lets Android handle it as usual.
     */
    private fun recordCrashes() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching {
                val dir = getExternalFilesDir(null) ?: filesDir
                File(dir, "crash.txt").writeText(
                    "${java.util.Date()} on ${thread.name}\n" + android.util.Log.getStackTraceString(error),
                )
            }
            previous?.uncaughtException(thread, error)
        }
    }
}
