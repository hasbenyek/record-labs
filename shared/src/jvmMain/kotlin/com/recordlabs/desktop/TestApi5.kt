package com.recordlabs.desktop

import io.ktor.client.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import kotlinx.coroutines.runBlocking

fun main() = runBlocking {
    val client = HttpClient()
    
    val queries = listOf(
        Pair("She's My Collar", "Gorillaz"),
        Pair("She's My Collar (feat. Kali Uchis)", "Gorillaz"),
        Pair("Trance", "Metro Boomin"),
        Pair("Ride", "twenty one pilots")
    )
    
    for (q in queries) {
        try {
            val res = client.get("https://artwork.boidu.dev/") {
                parameter("s", q.first)
                parameter("a", q.second)
            }.bodyAsText()
            println("Query [ - ]: " + res)
        } catch (e: Exception) {
            println("Query [ - ] failed: " + e.message)
        }
    }
    client.close()
}
