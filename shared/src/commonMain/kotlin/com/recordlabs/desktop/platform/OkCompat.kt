package com.recordlabs.desktop.platform

/*
 * The few OkHttp calls of the shared code (Request.Builder, newCall().execute().use { })
 * in the same shape, on top of [PlatformHttp] (OkHttp itself on the desktop and Android).
 */

class OkClient(val connectTimeoutMs: Long = 15_000L, val readTimeoutMs: Long = 30_000L) {
    fun newCall(request: OkRequest): OkCall = OkCall(this, request)
}

class OkCall(private val client: OkClient, private val request: OkRequest) {
    fun execute(): OkResponse = OkResponse(
        PlatformHttp.execute(
            HttpRequest(
                url = request.url,
                method = request.method,
                headers = request.headers,
                body = request.body?.bytes,
                contentType = request.body?.contentType,
                timeoutMs = client.connectTimeoutMs + client.readTimeoutMs,
            ),
        ),
    )
}

class OkRequestBody(val bytes: ByteArray, val contentType: String?)

fun String.toOkBody(contentType: String): OkRequestBody = OkRequestBody(encodeToByteArray(), contentType)

class OkRequest(val url: String, val method: String, val headers: Map<String, String>, val body: OkRequestBody?) {
    class Builder {
        private var url = ""
        private var method = "GET"
        private val headers = LinkedHashMap<String, String>()
        private var body: OkRequestBody? = null
        fun url(value: String) = apply { url = value }
        fun header(name: String, value: String) = apply { headers[name] = value }
        fun addHeader(name: String, value: String) = apply { headers[name] = value }
        fun get() = apply { method = "GET"; body = null }
        fun head() = apply { method = "HEAD"; body = null }
        fun post(value: OkRequestBody) = apply { method = "POST"; body = value }
        fun build() = OkRequest(url, method, headers.toMap(), body)
    }
}

class OkResponseBody(private val data: ByteArray) {
    fun string(): String = data.decodeToString()
    fun bytes(): ByteArray = data
    fun contentLength(): Long = data.size.toLong()
}

class OkResponse(private val response: HttpResponse) : AutoCloseable {
    val code: Int get() = response.code
    val isSuccessful: Boolean get() = response.isSuccessful
    val body: OkResponseBody? = OkResponseBody(response.body)
    fun header(name: String): String? = response.header(name)
    override fun close() = Unit
}

/** java.io.ByteArrayOutputStream: collects bytes. */
class ByteSink(initialSize: Int = 32) {
    private var buffer = ByteArray(initialSize.coerceAtLeast(16))
    private var count = 0
    fun write(bytes: ByteArray, offset: Int = 0, length: Int = bytes.size - offset) {
        if (count + length > buffer.size) buffer = buffer.copyOf(maxOf(buffer.size * 2, count + length))
        bytes.copyInto(buffer, count, offset, offset + length)
        count += length
    }
    fun size(): Int = count
    fun toByteArray(): ByteArray = buffer.copyOf(count)
}
