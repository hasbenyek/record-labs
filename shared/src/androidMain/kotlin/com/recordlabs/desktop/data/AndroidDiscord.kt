package com.recordlabs.desktop.data

import android.app.Activity
import android.app.Application
import android.os.Bundle
import com.recordlabs.music.discord.DiscordActivity
import com.recordlabs.music.discord.DiscordRpcManager
import com.recordlabs.music.discord.DiscordTokenStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import java.lang.ref.WeakReference

/**
 * Discord Rich Presence on Android, with the same Discord application and login as the
 * original Record Labs Android app: the user logs in with Discord once (OAuth in the
 * browser, PKCE), and the presence goes through Discord's gateway with that login (see
 * com.recordlabs.music.discord). The shared [DiscordRichPresence] decides what to show
 * and when; this only connects and sends.
 */
object AndroidDiscord : DiscordRemoteBackend {
    private lateinit var app: Application
    @Volatile private var installed = false
    @Volatile private var managerReady = false
    @Volatile private var resumed: WeakReference<Activity>? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** Called once by the app (Application.onCreate). */
    fun install(application: Application) {
        if (installed) return
        installed = true
        app = application
        // Opens the encrypted login store (Android keystore) off the main thread.
        scope.launch { DiscordTokenStore.init(application) }
        // The login page needs an activity to open from: the one on screen.
        application.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityResumed(activity: Activity) { resumed = WeakReference(activity) }
            override fun onActivityPaused(activity: Activity) = Unit
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
            override fun onActivityStarted(activity: Activity) = Unit
            override fun onActivityStopped(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
            override fun onActivityDestroyed(activity: Activity) {
                if (resumed?.get() === activity) resumed = null
            }
        })
        DiscordRichPresence.remoteBackend = this
        // The Discord code reports through Timber, which a release build does not print:
        // its messages go to logcat (tag DiscordSvc) and to discord-debug.log in the app's
        // data folder, and the last problem is shown in Settings.
        timber.log.Timber.plant(DiscordLogTree)
        // Connected, dropped, logged in or out, user known: the settings and the presence follow.
        scope.launch {
            combine(DiscordRpcManager.connectionStatus, DiscordRpcManager.currentUser, DiscordRpcManager.accessTokenFlow) { _, _, _ -> }
                .collect { DiscordRichPresence.remoteChanged() }
        }
    }

    override val loggedIn: Boolean
        get() = DiscordRpcManager.isAuthorized() || !DiscordTokenStore.retrieve().isNullOrEmpty()

    override fun state(): DiscordConnectionState {
        val user = DiscordRpcManager.currentUser.value
        val error = DiscordRpcManager.lastError.value
        return when (DiscordRpcManager.connectionStatus.value) {
            DiscordRpcManager.Status.Connected -> DiscordConnectionState.Connected(user?.name ?: user?.username, user?.avatar)
            DiscordRpcManager.Status.Authorizing -> DiscordConnectionState.Connecting
            DiscordRpcManager.Status.Disconnected -> when {
                // A login that did not go through says why (before, it only showed "Not logged in").
                error != null -> DiscordConnectionState.Failed(errorText(error))
                !loggedIn -> DiscordConnectionState.SignedOut
                else -> DiscordConnectionState.Connecting
            }
        }
    }

    override fun start() {
        if (!managerReady) {
            managerReady = true
            // Connects by itself with a saved login.
            DiscordRpcManager.init(app)
            return
        }
        if (DiscordRpcManager.isReady()) return
        DiscordTokenStore.retrieve()?.takeIf(String::isNotEmpty)?.let(DiscordRpcManager::reconnectWithToken)
    }

    override fun stop() {
        if (managerReady) DiscordRpcManager.disconnect()
    }

    override fun setActivity(content: DiscordActivityContent?, display: Int, trackId: String?, isPlaying: Boolean): Boolean {
        if (!DiscordRpcManager.isReady()) return false
        if (content == null) {
            DiscordRpcManager.clear()
            return true
        }
        DiscordRpcManager.setActivity(
            DiscordActivity(
                activityType = DiscordActivity.TYPE_LISTENING,
                // Discord drops an activity without a name (it was sent, never shown). As in
                // the original Android app, the name is what Discord shows next to the user
                // ("Listening to ..."), following "Shown in the member list".
                name = when (display) {
                    0 -> "Record Labs"
                    2 -> content.details
                    else -> content.state
                },
                state = content.state,
                details = content.details,
                startTimestamp = content.startMs ?: 0L,
                endTimestamp = content.endMs,
                largeImage = content.largeImage,
                largeText = content.largeText,
                smallImage = null,
                smallText = null,
                button1Label = content.buttonLabel,
                button1Url = content.buttonUrl,
                // Left out over the gateway, like the original app (the name does its job).
                statusDisplayType = null,
            ),
            songId = trackId,
            isPlaying = isPlaying,
        )
        return true
    }

    override fun login() {
        val activity = resumed?.get() ?: return
        if (!managerReady) {
            managerReady = true
            DiscordRpcManager.init(app)
        }
        DiscordRpcManager.clearLastError()
        DiscordRpcManager.authorize(activity) { DiscordRichPresence.remoteChanged() }
    }

    override fun logout() {
        if (DiscordRpcManager.isReady()) DiscordRpcManager.clear()
        if (!managerReady) {
            managerReady = true
            DiscordRpcManager.init(app)
        }
        DiscordRpcManager.logout()
    }

    private fun errorText(code: String?): String {
        val text = when (code) {
            "discord_error_no_browser" -> "No browser to open Discord's login page"
            "discord_error_token_refresh_failed" -> "Your Discord login expired · log in again"
            "discord_error_invalid_scope" -> "Discord did not accept the login · log in again"
            else -> if (loggedIn) "Couldn't reach Discord · it tries again by itself" else "The Discord login did not finish · try again"
        }
        return DiscordLogTree.lastProblem?.let { "$text ($it)" } ?: text
    }
}

/** See [AndroidDiscord.install]. */
private object DiscordLogTree : timber.log.Timber.Tree() {
    @Volatile var lastProblem: String? = null
    private val lock = Any()

    override fun isLoggable(tag: String?, priority: Int): Boolean = tag == "DiscordSvc" && priority >= android.util.Log.INFO

    override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
        val line = message + (t?.let { " · ${it.javaClass.simpleName}: ${it.message}" } ?: "")
        android.util.Log.println(priority, "DiscordSvc", line)
        if (priority >= android.util.Log.WARN) {
            lastProblem = (t?.message ?: message).take(140)
        } else if (message.startsWith("gateway: READY") || message.startsWith("token exchange: success")) {
            lastProblem = null
        }
        runCatching {
            synchronized(lock) {
                val file = java.io.File(Platform.dataRoot, "discord-debug.log")
                if (file.length() > 512_000L) file.writeText("")
                val time = java.text.SimpleDateFormat("MM-dd HH:mm:ss.SSS", java.util.Locale.ROOT).format(java.util.Date())
                file.appendText("$time ${"VDIWEA".getOrElse(priority - 2) { '?' }} $line\n")
                t?.let { file.appendText(android.util.Log.getStackTraceString(it) + "\n") }
            }
        }
    }
}
