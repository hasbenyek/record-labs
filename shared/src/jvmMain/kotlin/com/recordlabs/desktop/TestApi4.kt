package com.recordlabs.desktop

import com.recordlabs.desktop.data.Track
import io.ktor.client.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import kotlinx.coroutines.runBlocking

fun main() = runBlocking {
    val client = HttpClient()
    val response1 = client.get("https://artwork.boidu.dev/") {
        parameter("s", "10:36")
        parameter("a", "beabadoobee")
    }.bodyAsText()
    println("10:36: " + response1)

    val response2 = client.get("https://artwork.boidu.dev/") {
        parameter("s", "Ride")
        parameter("a", "twenty one pilots")
    }.bodyAsText()
    println("Ride: " + response2)
    client.close()
}
