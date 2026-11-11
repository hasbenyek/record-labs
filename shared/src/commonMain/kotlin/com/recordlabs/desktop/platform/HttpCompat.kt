package com.recordlabs.desktop.platform

/** One HTTP exchange, made in the calling thread (call it from IoDispatcher). */
class HttpRequest(
    val url: String,
    val method: String = "GET",
    val headers: Map<String, String> = emptyMap(),
    val body: ByteArray? = null,
    val contentType: String? = null,
    val timeoutMs: Long = 20_000L,
    /** False: a 3xx answer is returned instead of followed. */
    val followRedirects: Boolean = true,
)

class HttpResponse(
    val code: Int,
    /** Header names in lower case. */
    val headers: Map<String, List<String>>,
    val body: ByteArray,
    /** The address answered (after redirects). */
    val url: String,
) {
    val isSuccessful: Boolean get() = code in 200..299
    fun text(): String = body.decodeToString()
    fun header(name: String): String? = headers[name.lowercase()]?.firstOrNull()
}

class HttpException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** Blocking HTTP (OkHttp on the desktop and Android, NSURLSession on iOS). */
expect object PlatformHttp {
    fun execute(request: HttpRequest): HttpResponse
}

fun httpGet(url: String, headers: Map<String, String> = emptyMap(), timeoutMs: Long = 20_000L): HttpResponse =
    PlatformHttp.execute(HttpRequest(url, "GET", headers, timeoutMs = timeoutMs))

fun httpPost(url: String, body: String, contentType: String, headers: Map<String, String> = emptyMap(), timeoutMs: Long = 20_000L): HttpResponse =
    PlatformHttp.execute(HttpRequest(url, "POST", headers, body.encodeToByteArray(), contentType, timeoutMs))

/** The Ktor engine of the platform (CIO on the desktop and Android, Darwin on iOS). */
expect fun ktorEngine(): io.ktor.client.engine.HttpClientEngineFactory<*>
