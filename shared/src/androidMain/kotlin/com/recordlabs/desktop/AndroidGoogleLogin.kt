package com.recordlabs.desktop

import android.annotation.SuppressLint
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import com.recordlabs.desktop.data.WindowsPreferences
import com.recordlabs.innertube.YouTube
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val ANDROID_LOGIN_URL = "https://accounts.google.com/ServiceLogin?continue=https%3A%2F%2Fmusic.youtube.com"

/**
 * Google login for YouTube Music, done like the original Record Labs Android
 * app's LoginScreen: the page reports YouTube's visitor / data-sync ids, and
 * once music.youtube.com is open with the sign-in cookie the account is
 * checked with the InnerTube account request before anything is saved. The
 * session and the account name/photo are then saved where the shared app
 * reads them (the same place the desktop login uses).
 */
@SuppressLint("SetJavaScriptEnabled", "JavascriptInterface")
@Composable
internal fun AndroidGoogleLogin(onAuthenticated: () -> Unit) {
    val scope = rememberCoroutineScope()
    val latestOnAuthenticated = rememberUpdatedState(onAuthenticated)
    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { context ->
            val ids = object {
                @Volatile var visitorData = ""
                @Volatile var dataSyncId = ""
                @Volatile var completing = false
                @Volatile var done = false

                @JavascriptInterface
                fun onRetrieveVisitorData(value: String?) {
                    if (!value.isNullOrBlank() && value != "undefined") visitorData = value
                }

                @JavascriptInterface
                fun onRetrieveDataSyncId(value: String?) {
                    if (!value.isNullOrBlank() && value != "undefined") dataSyncId = value.substringBefore("||")
                }
            }
            CookieManager.getInstance().setAcceptCookie(true)
            WebView(context).apply {
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                addJavascriptInterface(ids, "Android")
                webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView, url: String?) {
                        view.loadUrl("javascript:Android.onRetrieveVisitorData(window.yt.config_.VISITOR_DATA)")
                        view.loadUrl("javascript:Android.onRetrieveDataSyncId(window.yt.config_.DATASYNC_ID)")
                        if (ids.done || ids.completing || url?.contains("music.youtube.com") != true) return
                        val cookie = CookieManager.getInstance().getCookie("https://music.youtube.com").orEmpty()
                        if (!cookie.contains("SAPISID=")) return
                        ids.completing = true
                        scope.launch {
                            // Let the ids above arrive (they are reported asynchronously).
                            kotlinx.coroutines.delay(800)
                            YouTube.cookie = cookie
                            YouTube.dataSyncId = ids.dataSyncId.ifBlank { null }
                            if (ids.visitorData.isNotBlank()) YouTube.visitorData = ids.visitorData
                            val info = withContext(Dispatchers.IO) { YouTube.accountInfo() }
                                .onFailure { System.err.println("Record Labs: login check failed: ${it.message}") }
                                .getOrNull()
                            if (info == null) {
                                // Not a finished sign-in yet (account chooser, consent page...): keep the page.
                                ids.completing = false
                                return@launch
                            }
                            withContext(Dispatchers.IO) {
                                WindowsPreferences.saveGoogleSession(cookie, ids.visitorData, ids.dataSyncId)
                                WindowsPreferences.saveGoogleAccountProfile(info.name, info.thumbnailUrl, info.email, info.channelHandle)
                                runCatching { java.util.prefs.Preferences.userRoot().flush() }
                            }
                            ids.done = true
                            latestOnAuthenticated.value()
                        }
                    }
                }
                // A fresh sign-in every time: the web view keeps Google's cookies after
                // Logout, and with them the login page went straight back into the
                // previous account (no way to pick another one). Cleared first, then
                // the page opens.
                clearCache(true)
                runCatching { android.webkit.WebStorage.getInstance().deleteAllData() }
                val cookies = CookieManager.getInstance()
                cookies.removeAllCookies {
                    cookies.flush()
                    loadUrl(ANDROID_LOGIN_URL)
                }
            }
        },
    )
}
