package com.recordlabs.desktop.data

import kotlinx.coroutines.runBlocking
import org.junit.Test
import okhttp3.OkHttpClient
import okhttp3.Request

class Http403Test {
    @Test
    fun testYouTubeStreamUrl403() = runBlocking {
        val repo = WindowsMusicRepository()
        val results = repo.search("Shape of You", filter = "Songs")
        val track = results.tracks.firstOrNull() ?: error("No track found")
        println("Track: ${track.title} by ${track.artist} (id: ${track.id})")

        val proxyUrl = repo.streamUrl(track) ?: error("streamUrl returned null")
        val remoteUrl = WindowsAudioProxy.getRemoteUrl(proxyUrl)
        println("Remote URL: $remoteUrl")

        val client = OkHttpClient()

        // Test 1: Request with WindowsAudioProxy User-Agent
        val req1 = Request.Builder()
            .url(remoteUrl)
            .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/131 Safari/537.36")
            .build()
        client.newCall(req1).execute().use { resp ->
            println("Test 1 (Chrome UA) status: ${resp.code}, type=${resp.header("Content-Type")}, range=${resp.header("Content-Range")}, length=${resp.header("Content-Length")}")
        }

        // Test 2: Request without User-Agent
        val req2 = Request.Builder()
            .url(remoteUrl)
            .build()
        client.newCall(req2).execute().use { resp ->
            println("Test 2 (No UA) status: ${resp.code}")
        }

        // Test 3: Request with Android VR / Remix UA
        val req3 = Request.Builder()
            .url(remoteUrl)
            .header("User-Agent", "com.google.android.apps.youtube.music/6.41.52 (Linux; U; Android 12)")
            .build()
        client.newCall(req3).execute().use { resp ->
            println("Test 3 (Android Music UA) status: ${resp.code}")
        }
    }
}
