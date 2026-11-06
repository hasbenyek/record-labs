package com.recordlabs.desktop.platform

import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

actual object PlatformHttp {
    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    actual fun execute(request: HttpRequest): HttpResponse {
        val call = client.newBuilder()
            .callTimeout(request.timeoutMs, TimeUnit.MILLISECONDS)
            .followRedirects(request.followRedirects)
            .followSslRedirects(request.followRedirects)
            .build()
        val body = request.body?.toRequestBody(request.contentType?.toMediaTypeOrNull())
        val builder = Request.Builder().url(request.url)
        request.headers.forEach { (name, value) -> builder.header(name, value) }
        when {
            body != null -> builder.method(request.method, body)
            request.method in setOf("POST", "PUT", "PATCH") -> builder.method(request.method, ByteArray(0).toRequestBody(null))
            else -> builder.method(request.method, null)
        }
        try {
            call.newCall(builder.build()).execute().use { response ->
                val headers = response.headers.toMultimap().mapKeys { it.key.lowercase() }
                return HttpResponse(response.code, headers, response.body?.bytes() ?: ByteArray(0), response.request.url.toString())
            }
        } catch (error: java.io.IOException) {
            throw HttpException(error.message ?: "Network error", error)
        }
    }
}

actual fun ktorEngine(): io.ktor.client.engine.HttpClientEngineFactory<*> = io.ktor.client.engine.cio.CIO
