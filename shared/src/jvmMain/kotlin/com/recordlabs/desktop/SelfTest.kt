package com.recordlabs.desktop

import com.recordlabs.desktop.data.Track
import com.recordlabs.desktop.data.WindowsMediaPlayer
import com.sun.net.httpserver.HttpServer
import java.io.ByteArrayOutputStream
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.sin

/**
 * `record-labs --self-test-audio`: plays a generated 3-second tone through the
 * real playback path (WindowsMediaPlayer -> helper/VLC, over local HTTP like
 * the audio proxy) without opening a window, prints PASS/FAIL and returns the
 * exit code. Used to check installed packages on each Linux distribution.
 */
internal fun runAudioSelfTest(): Int {
    // The stream fallbacks (yt-dlp) and signature decoding (Deno) must start.
    val toolsOk = listOf("yt-dlp", "deno").all { tool ->
        val file = com.recordlabs.desktop.data.BundledTools.find(tool)
        val version = file?.let { runToolVersion(it) }
        println(if (version != null) "SELF-TEST tool $tool: $version (${file?.absolutePath})" else "SELF-TEST FAIL: $tool not runnable (${file?.absolutePath ?: "missing"})")
        version != null
    }
    if (!toolsOk) return 1
    println("SELF-TEST font: ${AppFonts.description}; lyrics: ${AppFonts.lyricsDescription}")
    // Floating lyrics need see-through windows (a compositing desktop).
    runCatching {
        if (!java.awt.GraphicsEnvironment.isHeadless()) {
            val device = java.awt.GraphicsEnvironment.getLocalGraphicsEnvironment().defaultScreenDevice
            val translucent = device.isWindowTranslucencySupported(java.awt.GraphicsDevice.WindowTranslucency.PERPIXEL_TRANSLUCENT)
            println("SELF-TEST transparent windows: ${if (translucent) "supported" else "NOT supported (floating lyrics get a solid background)"}")
        }
    }
    val wav = selfTestToneWav(seconds = 3)
    val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
        createContext("/tone.wav") { exchange ->
            exchange.responseHeaders.add("Content-Type", "audio/wav")
            exchange.sendResponseHeaders(200, wav.size.toLong())
            exchange.responseBody.use { it.write(wav) }
        }
        start()
    }
    val player = WindowsMediaPlayer()
    val ended = java.util.concurrent.atomic.AtomicBoolean(false)
    player.onPlaybackEnded = { ended.set(true) }
    return try {
        player.play(Track(id = "self-test", title = "Self test", artist = "Record Labs", durationMs = 3000), "http://127.0.0.1:${server.address.port}/tone.wav")
        val started = waitUntil(15_000) { player.isReady && player.positionMs > 200 }
        val finished = started && waitUntil(10_000) { ended.get() }
        val engine = System.getProperty("jna.library.path").orEmpty().ifBlank { "system" }
        if (finished && player.lastError == null) {
            println("SELF-TEST PASS: audio played to the end (VLC path: $engine)")
            0
        } else {
            println("SELF-TEST FAIL: started=$started ended=$finished state=${player.state.value} (VLC path: $engine)")
            1
        }
    } catch (error: Throwable) {
        println("SELF-TEST FAIL: ${error.stackTraceToString()}")
        1
    } finally {
        runCatching { player.release() }
        server.stop(0)
    }
}

private fun runToolVersion(file: java.io.File): String? = runCatching {
    val process = ProcessBuilder(file.absolutePath, "--version").redirectErrorStream(true).start()
    if (!process.waitFor(30, java.util.concurrent.TimeUnit.SECONDS)) {
        process.destroyForcibly()
        return@runCatching null
    }
    process.inputStream.bufferedReader().readText().lineSequence().firstOrNull()?.trim()
        ?.takeIf { process.exitValue() == 0 && it.isNotBlank() }
}.getOrNull()

private fun waitUntil(timeoutMs: Long, condition: () -> Boolean): Boolean {
    val deadline = System.currentTimeMillis() + timeoutMs
    while (System.currentTimeMillis() < deadline) {
        if (condition()) return true
        Thread.sleep(50)
    }
    return condition()
}

private fun selfTestToneWav(seconds: Int, sampleRate: Int = 44100): ByteArray {
    val samples = seconds * sampleRate
    val data = ByteBuffer.allocate(samples * 2).order(ByteOrder.LITTLE_ENDIAN)
    repeat(samples) { i -> data.putShort((sin(2 * PI * 440 * i / sampleRate) * 6000).toInt().toShort()) }
    val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).apply {
        put("RIFF".toByteArray()); putInt(36 + samples * 2); put("WAVE".toByteArray())
        put("fmt ".toByteArray()); putInt(16); putShort(1); putShort(1); putInt(sampleRate); putInt(sampleRate * 2)
        putShort(2); putShort(16); put("data".toByteArray()); putInt(samples * 2)
    }
    return ByteArrayOutputStream().apply { write(header.array()); write(data.array()) }.toByteArray()
}

/**
 * `record-labs --self-test-login`: lists the browsers Google login can use on
 * Linux (default first). With -Drecordlabs.loginUrl=<url> it also runs the
 * whole login flow against that page and checks a SAPISID session was saved.
 */
internal fun runLoginSelfTest(): Int {
    val browsers = LinuxLoginBrowser.all()
    if (browsers.isEmpty()) println("LOGIN-TEST no usable browser found")
    browsers.forEach { println("LOGIN-TEST browser: ${it.name} engine=${it.engine} sandbox=${it.sandbox} default=${it.isDefault} command=${it.command}") }
    println("LOGIN-TEST would open: ${LinuxLoginBrowser.find()?.name ?: "none"}")
    if (System.getProperty("recordlabs.loginUrl").isNullOrBlank()) return if (browsers.isEmpty()) 1 else 0
    com.recordlabs.desktop.data.WindowsPreferences.clearGoogleSession()
    val result = kotlinx.coroutines.runBlocking { BrowserGoogleLogin.login { println("LOGIN-TEST progress: $it") } }
    val session = com.recordlabs.desktop.data.WindowsPreferences.googleSession()
    return if (result.isSuccess && session != null) {
        println("LOGIN-TEST PASS: session saved (${session.cookie.split(';').size} cookies)")
        com.recordlabs.desktop.data.WindowsPreferences.clearGoogleSession()
        0
    } else {
        println("LOGIN-TEST FAIL: ${result.exceptionOrNull()?.message}")
        1
    }
}

/**
 * `record-labs --self-test-connect`: checks what Record Labs Connect needs on
 * this computer and listens 20 s for other Record Labs devices on the network
 * (open Record Labs on the phone/other PC meanwhile). Prints the network
 * interfaces, the firewall state with the command to open Connect's ports,
 * and every device heard (name, platform, address). Needs no Google login.
 */
internal fun runConnectSelfTest(): Int {
    println("CONNECT-TEST device name: ${com.recordlabs.desktop.data.Platform.deviceName}")
    val interfaces = runCatching {
        java.net.NetworkInterface.getNetworkInterfaces().toList().filter { it.isUp && !it.isLoopback }
            .flatMap { network -> network.inetAddresses.toList().filterIsInstance<java.net.Inet4Address>().map { "${network.name} ${it.hostAddress}" } }
    }.getOrDefault(emptyList())
    if (interfaces.isEmpty()) println("CONNECT-TEST FAIL: no IPv4 network (connect to Wi-Fi or Ethernet)")
    interfaces.forEach { println("CONNECT-TEST network: $it") }
    val firewall = com.recordlabs.desktop.data.LinuxFirewall.check()
    when {
        com.recordlabs.desktop.data.Platform.isWindows -> println("CONNECT-TEST firewall: Windows (allowed by the installer)")
        firewall == null -> println("CONNECT-TEST firewall: none active")
        firewall.connectAllowed == true -> println("CONNECT-TEST firewall: ${firewall.tool}, Connect ports open")
        else -> {
            println("CONNECT-TEST firewall: ${firewall.tool} ${if (firewall.connectAllowed == false) "BLOCKS Connect" else "is on (cannot see its rules without admin rights)"}")
            println("CONNECT-TEST open the ports with: ${firewall.fixCommand}")
        }
    }
    val heard = linkedMapOf<String, String>()
    val socket = runCatching {
        java.net.MulticastSocket(null as java.net.SocketAddress?).apply {
            reuseAddress = true
            bind(java.net.InetSocketAddress(45_922))
            soTimeout = 500
            runCatching { joinGroup(java.net.InetSocketAddress(java.net.InetAddress.getByName("239.255.42.99"), 45_922), null) }
        }
    }.getOrElse {
        println("CONNECT-TEST FAIL: cannot listen on UDP 45922 (${it.message})")
        return 1
    }
    println("CONNECT-TEST listening 20 s for Record Labs devices (open Record Labs on your other devices now)...")
    val buffer = ByteArray(64 * 1024)
    val deadline = System.currentTimeMillis() + 20_000L
    socket.use {
        while (System.currentTimeMillis() < deadline) {
            val packet = java.net.DatagramPacket(buffer, buffer.size)
            try { it.receive(packet) } catch (_: java.net.SocketTimeoutException) { continue }
            val json = runCatching {
                kotlinx.serialization.json.Json.parseToJsonElement(String(packet.data, packet.offset, packet.length, Charsets.UTF_8)) as kotlinx.serialization.json.JsonObject
            }.getOrNull() ?: continue
            fun field(name: String) = (json[name] as? kotlinx.serialization.json.JsonPrimitive)?.content.orEmpty()
            val device = field("device").ifBlank { continue }
            if (device == com.recordlabs.desktop.data.WindowsPreferences.connectDeviceId()) continue
            val label = "${field("name").ifBlank { "(no name yet)" }} | ${field("os").ifBlank { field("platform") }.ifBlank { "android?" }} | ${packet.address.hostAddress}"
            if (heard.put(device, label) == null) println("CONNECT-TEST heard: $label")
        }
    }
    return if (heard.isEmpty()) {
        println("CONNECT-TEST nothing heard. Check: same Wi-Fi (not a guest network), Record Labs open on the other device, the firewall above, and that the router does not isolate devices (\"AP/client isolation\").")
        1
    } else {
        println("CONNECT-TEST PASS: ${heard.size} device(s) heard. If they don't show in the app, check that both use the same Google account.")
        0
    }
}
