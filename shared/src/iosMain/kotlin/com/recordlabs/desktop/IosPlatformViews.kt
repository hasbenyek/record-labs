@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlinx.cinterop.BetaInteropApi::class)

package com.recordlabs.desktop

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.UIKitView
import com.recordlabs.desktop.data.WindowsMusicRepository
import com.recordlabs.desktop.data.WindowsPreferences
import com.recordlabs.desktop.platform.IoDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import platform.CoreGraphics.CGRectMake
import platform.Foundation.NSDate
import platform.Foundation.NSHTTPCookie
import platform.Foundation.NSURL
import platform.Foundation.NSURLRequest
import platform.Foundation.dateWithTimeIntervalSince1970
import platform.WebKit.WKNavigation
import platform.WebKit.WKNavigationDelegateProtocol
import platform.WebKit.WKWebView
import platform.WebKit.WKWebViewConfiguration
import platform.WebKit.WKWebsiteDataStore
import platform.darwin.NSObject
import kotlin.math.abs

private const val IOS_LOGIN_URL = "https://accounts.google.com/ServiceLogin?continue=https%3A%2F%2Fmusic.youtube.com"
private const val IOS_SAFARI_AGENT =
    "Mozilla/5.0 (iPhone; CPU iPhone OS 18_2 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/18.2 Mobile/15E148 Safari/604.1"

private class LoginNavigationDelegate(private val onPageFinished: (WKWebView) -> Unit) : NSObject(), WKNavigationDelegateProtocol {
    @kotlinx.cinterop.ObjCSignatureOverride
    override fun webView(webView: WKWebView, didFinishNavigation: WKNavigation?) {
        onPageFinished(webView)
    }
}

/**
 * Google sign-in on the iPhone, like the Android app's login page: a web page
 * (WKWebView) with Google's sign-in; once music.youtube.com opens with the
 * account's cookie, the session (cookie, visitor and data-sync ids) and the
 * account's name and picture are saved.
 */
@Composable
internal fun IosGoogleLoginView(onAuthenticated: () -> Unit) {
    val scope = rememberCoroutineScope()
    val latestOnAuthenticated = rememberUpdatedState(onAuthenticated)
    val state = remember { object { var completing = false; var done = false } }
    val delegate = remember {
        LoginNavigationDelegate { webView ->
            val url = webView.URL?.absoluteString.orEmpty()
            if (state.done || state.completing || !url.contains("music.youtube.com")) return@LoginNavigationDelegate
            state.completing = true
            webView.evaluateJavaScript(
                "JSON.stringify([(window.yt&&yt.config_&&yt.config_.VISITOR_DATA)||'',(window.yt&&yt.config_&&yt.config_.DATASYNC_ID)||''])",
            ) { result, _ ->
                val ids = (result as? String).orEmpty()
                val visitorData = ids.substringAfter("[\"", "").substringBefore("\"")
                val dataSyncId = ids.substringAfter("\",\"", "").substringBefore("\"").substringBefore("||")
                webView.configuration.websiteDataStore.httpCookieStore.getAllCookies { cookies ->
                    val cookie = cookies.orEmpty()
                        .filterIsInstance<NSHTTPCookie>()
                        .filter { it.domain.contains("youtube.com") }
                        .distinctBy { it.name }
                        .joinToString("; ") { "${it.name}=${it.value}" }
                    if (!cookie.contains("SAPISID=")) {
                        state.completing = false
                        return@getAllCookies
                    }
                    scope.launch {
                        delay(300)
                        val profile = withContext(IoDispatcher) {
                            WindowsPreferences.saveGoogleSession(cookie, visitorData, dataSyncId)
                            val repository = WindowsMusicRepository()
                            try {
                                runCatching { repository.googleAccountProfile() }.getOrNull()
                            } finally {
                                repository.close()
                            }
                        }
                        if (profile != null) {
                            WindowsPreferences.saveGoogleAccountProfile(profile.name, profile.photoUrl, profile.email, profile.channelHandle)
                        }
                        state.done = true
                        latestOnAuthenticated.value()
                    }
                }
            }
        }
    }
    UIKitView(
        factory = {
            val configuration = WKWebViewConfiguration()
            configuration.websiteDataStore = WKWebsiteDataStore.defaultDataStore()
            val webView = WKWebView(frame = CGRectMake(0.0, 0.0, 0.0, 0.0), configuration = configuration)
            webView.customUserAgent = IOS_SAFARI_AGENT
            webView.navigationDelegate = delegate
            // A fresh sign-in every time (with Google's old cookies the page went
            // straight back into the previous account).
            WKWebsiteDataStore.defaultDataStore().removeDataOfTypes(
                WKWebsiteDataStore.allWebsiteDataTypes(),
                modifiedSince = NSDate.dateWithTimeIntervalSince1970(0.0),
            ) {
                NSURL.URLWithString(IOS_LOGIN_URL)?.let { webView.loadRequest(NSURLRequest.requestWithURL(it)) }
            }
            webView
        },
        modifier = Modifier.fillMaxSize(),
        onRelease = { it.navigationDelegate = null },
    )
}

/** The handler of the app's Back (the newest screen that asked for it). */
internal object IosBack {
    var handler: (() -> Boolean)? = null
}

/** iOS: Back is the swipe from the screen's left edge (see [iosEdgeSwipeBack]). */
@Composable
internal fun IosBackGesture(onBack: () -> Boolean) {
    val latest = rememberUpdatedState(onBack)
    DisposableEffect(Unit) {
        val handler: () -> Boolean = { latest.value() }
        IosBack.handler = handler
        onDispose { if (IosBack.handler === handler) IosBack.handler = null }
    }
}

/** A swipe to the right that starts at the left edge goes back, like iOS apps. */
fun Modifier.iosEdgeSwipeBack(): Modifier = pointerInput(Unit) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
        if (down.position.x > 22.dp.toPx()) return@awaitEachGesture
        // A page with a page under it: the page stack moves it with the finger itself.
        // A tab's own page (Home, Library, Listen Together, Profile): nothing to go back to.
        if (PageStack.claimsEdgeSwipe() || PageStack.blocksEdgeSwipe()) return@awaitEachGesture
        val trigger = 72.dp.toPx()
        while (true) {
            val event = awaitPointerEvent(PointerEventPass.Initial)
            val change = event.changes.firstOrNull { it.id == down.id } ?: break
            if (!change.pressed) break
            val dx = change.position.x - down.position.x
            val dy = abs(change.position.y - down.position.y)
            if (dy > trigger) break
            if (dx > trigger && dx > dy * 2f) {
                change.consume()
                IosBack.handler?.invoke()
                // The rest of this touch belongs to the swipe.
                while (true) {
                    val rest = awaitPointerEvent(PointerEventPass.Initial)
                    rest.changes.forEach { it.consume() }
                    if (rest.changes.none { it.pressed }) break
                }
                break
            }
        }
    }
}
