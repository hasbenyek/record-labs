package com.recordlabs.desktop.platform

import java.security.MessageDigest
import kotlinx.coroutines.asCoroutineDispatcher

actual fun currentTimeMillis(): Long = System.currentTimeMillis()

actual fun nanoTime(): Long = System.nanoTime()

actual inline fun <T> locked(lock: Any, block: () -> T): T = synchronized(lock, block)

actual fun String.fmt(vararg args: Any?): String = this.format(*args)

actual fun <K : Any, V : Any> concurrentMapOf(): MutableMap<K, V> = java.util.concurrent.ConcurrentHashMap()

actual fun digest(algorithm: String, bytes: ByteArray): ByteArray =
    MessageDigest.getInstance(algorithm.uppercase()).digest(bytes)

actual fun digestHex(algorithm: String, bytes: ByteArray): String = digest(algorithm, bytes).toHex()

actual fun hmac(algorithm: String, key: ByteArray, data: ByteArray): ByteArray {
    val mac = javax.crypto.Mac.getInstance(algorithm)
    mac.init(javax.crypto.spec.SecretKeySpec(key, algorithm))
    return mac.doFinal(data)
}

actual fun logError(message: String) = System.err.println(message)

actual fun normalizeNfd(text: String): String = java.text.Normalizer.normalize(text, java.text.Normalizer.Form.NFD)
actual fun normalizeNfc(text: String): String = java.text.Normalizer.normalize(text, java.text.Normalizer.Form.NFC)

actual fun deviceLanguage(): String = java.util.Locale.getDefault().language

actual fun systemProperty(name: String): String? = System.getProperty(name)

actual fun maxMemoryBytes(): Long = Runtime.getRuntime().maxMemory()

actual fun processorCount(): Int = Runtime.getRuntime().availableProcessors()

actual fun normalizeNfkd(text: String): String = java.text.Normalizer.normalize(text, java.text.Normalizer.Form.NFKD)

actual val IoDispatcher: kotlinx.coroutines.CoroutineDispatcher get() = kotlinx.coroutines.Dispatchers.IO

actual typealias JvmSync = kotlin.jvm.Synchronized

actual fun lowPriorityWorker(name: String): kotlinx.coroutines.CoroutineDispatcher {
    val executor = java.util.concurrent.Executors.newSingleThreadExecutor { task ->
        Thread(task, name).apply { isDaemon = true; priority = Thread.MIN_PRIORITY }
    }
    return executor.asCoroutineDispatcher()
}

actual fun resolveUrl(base: String, relative: String): String = java.net.URI(base).resolve(relative).toString()

actual fun urlHost(url: String): String = runCatching { java.net.URI(url).host }.getOrNull().orEmpty()

actual fun systemEnv(name: String): String? = System.getenv(name)

actual fun characterBreaks(text: String): List<Int> {
    val breaks = java.text.BreakIterator.getCharacterInstance()
    breaks.setText(text)
    val out = ArrayList<Int>()
    var next = breaks.next()
    while (next != java.text.BreakIterator.DONE && next < text.length) {
        out += next
        next = breaks.next()
    }
    return out
}

actual fun timeAgoMillis(months: Int, weeks: Int): Long =
    java.time.ZonedDateTime.now().minusMonths(months.toLong()).minusWeeks(weeks.toLong()).toInstant().toEpochMilli()

actual fun countryName(code: String): String =
    java.util.Locale.Builder().setRegion(code).build().getDisplayCountry(java.util.Locale.ENGLISH)

actual fun openFolder(folder: File) { com.recordlabs.desktop.data.Platform.openFolder(folder.javaFile) }

actual fun deviceCountry(): String = java.util.Locale.getDefault().country
