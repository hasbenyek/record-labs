@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlinx.cinterop.BetaInteropApi::class)

package com.recordlabs.desktop.platform

import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.usePinned
import platform.Foundation.NSData
import platform.Foundation.NSError
import platform.Foundation.NSHTTPURLResponse
import platform.Foundation.NSMutableURLRequest
import platform.Foundation.NSURL
import platform.Foundation.NSURLRequest
import platform.Foundation.NSURLRequestUseProtocolCachePolicy
import platform.Foundation.NSURLResponse
import platform.Foundation.NSURLSession
import platform.Foundation.NSURLSessionConfiguration
import platform.Foundation.NSURLSessionTask
import platform.Foundation.NSURLSessionTaskDelegateProtocol
import platform.Foundation.dataTaskWithRequest
import platform.Foundation.dataWithBytes
import platform.Foundation.setHTTPBody
import platform.Foundation.setHTTPMethod
import platform.Foundation.setHTTPShouldHandleCookies
import platform.Foundation.setValue
import platform.darwin.DISPATCH_TIME_FOREVER
import platform.darwin.NSObject
import platform.darwin.dispatch_semaphore_create
import platform.darwin.dispatch_semaphore_signal
import platform.darwin.dispatch_semaphore_wait
import platform.posix.memcpy

fun ByteArray.toNSData(): NSData =
    if (isEmpty()) NSData() else usePinned { NSData.dataWithBytes(it.addressOf(0), size.convert()) }

fun NSData.toByteArray(): ByteArray {
    val size = length.toInt()
    val out = ByteArray(size)
    if (size > 0) out.usePinned { memcpy(it.addressOf(0), bytes, length) }
    return out
}

/** Refuses redirects (HttpRequest.followRedirects = false). */
private class NoRedirects : NSObject(), NSURLSessionTaskDelegateProtocol {
    override fun URLSession(
        session: NSURLSession,
        task: NSURLSessionTask,
        willPerformHTTPRedirection: NSHTTPURLResponse,
        newRequest: NSURLRequest,
        completionHandler: (NSURLRequest?) -> Unit,
    ) = completionHandler(null)
}

/**
 * No cookie handling by iOS: the app sends the account's cookies itself. With
 * iOS's own cookie store on, the cookies YouTube sets in its answers (YSC,
 * VISITOR_INFO1_LIVE) were sent instead of the app's Cookie header, so every
 * request after the first one went out signed out (Home without the account's picks).
 */
internal fun NSURLSessionConfiguration.withoutCookies(): NSURLSessionConfiguration = apply {
    HTTPCookieStorage = null
    HTTPShouldSetCookies = false
}

actual object PlatformHttp {
    private val session by lazy { NSURLSession.sessionWithConfiguration(NSURLSessionConfiguration.defaultSessionConfiguration.withoutCookies()) }
    private val noRedirectSession by lazy {
        NSURLSession.sessionWithConfiguration(NSURLSessionConfiguration.defaultSessionConfiguration.withoutCookies(), NoRedirects(), null)
    }

    actual fun execute(request: HttpRequest): HttpResponse {
        val url = NSURL.URLWithString(request.url) ?: throw HttpException("Bad address: ${request.url}")
        val native = NSMutableURLRequest.requestWithURL(url, NSURLRequestUseProtocolCachePolicy, request.timeoutMs / 1000.0)
        native.setHTTPMethod(request.method)
        native.setHTTPShouldHandleCookies(false)
        request.headers.forEach { (name, value) -> native.setValue(value, forHTTPHeaderField = name) }
        request.contentType?.let { native.setValue(it, forHTTPHeaderField = "Content-Type") }
        request.body?.let { native.setHTTPBody(it.toNSData()) }

        val done = dispatch_semaphore_create(0)
        var data: NSData? = null
        var response: NSURLResponse? = null
        var error: NSError? = null
        val task = (if (request.followRedirects) session else noRedirectSession).dataTaskWithRequest(native) { d, r, e ->
            data = d
            response = r
            error = e
            dispatch_semaphore_signal(done)
        }
        task.resume()
        dispatch_semaphore_wait(done, DISPATCH_TIME_FOREVER)

        error?.let { throw HttpException(it.localizedDescription) }
        val http = response as? NSHTTPURLResponse ?: throw HttpException("No answer from ${request.url}")
        val headers = HashMap<String, List<String>>()
        http.allHeaderFields.forEach { (key, value) ->
            val name = key?.toString()?.lowercase() ?: return@forEach
            headers[name] = listOf(value?.toString().orEmpty())
        }
        return HttpResponse(
            code = http.statusCode.toInt(),
            headers = headers,
            body = data?.toByteArray() ?: ByteArray(0),
            url = http.URL?.absoluteString ?: request.url,
        )
    }
}

/** Darwin without iOS's cookie handling (see [withoutCookies]). */
private object CookielessDarwin : io.ktor.client.engine.HttpClientEngineFactory<io.ktor.client.engine.darwin.DarwinClientEngineConfig> {
    override fun create(block: io.ktor.client.engine.darwin.DarwinClientEngineConfig.() -> Unit): io.ktor.client.engine.HttpClientEngine =
        io.ktor.client.engine.darwin.Darwin.create {
            block()
            configureSession { withoutCookies() }
            configureRequest { setHTTPShouldHandleCookies(false) }
        }
}

actual fun ktorEngine(): io.ktor.client.engine.HttpClientEngineFactory<*> = CookielessDarwin
