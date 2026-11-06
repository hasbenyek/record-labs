@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlinx.cinterop.BetaInteropApi::class)

package com.recordlabs.desktop.platform

import kotlinx.cinterop.addressOf
import kotlinx.cinterop.useContents
import kotlinx.cinterop.convert
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.IO
import platform.CoreCrypto.CCHmac
import platform.CoreCrypto.CC_MD5
import platform.CoreCrypto.CC_MD5_DIGEST_LENGTH
import platform.CoreCrypto.CC_SHA1
import platform.CoreCrypto.CC_SHA1_DIGEST_LENGTH
import platform.CoreCrypto.CC_SHA256
import platform.CoreCrypto.CC_SHA256_DIGEST_LENGTH
import platform.CoreCrypto.kCCHmacAlgSHA1
import platform.CoreCrypto.kCCHmacAlgSHA256
import platform.Foundation.NSDate
import platform.Foundation.NSLocale
import platform.Foundation.NSProcessInfo
import platform.Foundation.NSRecursiveLock
import platform.Foundation.NSString
import platform.Foundation.currentLocale
import platform.Foundation.decomposedStringWithCanonicalMapping
import platform.Foundation.decomposedStringWithCompatibilityMapping
import platform.Foundation.languageCode
import platform.Foundation.precomposedStringWithCanonicalMapping
import platform.Foundation.rangeOfComposedCharacterSequenceAtIndex
import platform.Foundation.countryCode
import platform.Foundation.timeIntervalSince1970
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.round

actual fun currentTimeMillis(): Long = (NSDate().timeIntervalSince1970 * 1000.0).toLong()

actual fun nanoTime(): Long = (NSProcessInfo.processInfo.systemUptime * 1_000_000_000.0).toLong()

/** One app-wide re-entrant lock: simple and safe (the locked parts are short). */
@PublishedApi
internal val globalLock = NSRecursiveLock()

actual inline fun <T> locked(lock: Any, block: () -> T): T {
    globalLock.lock()
    try {
        return block()
    } finally {
        globalLock.unlock()
    }
}

actual fun <K : Any, V : Any> concurrentMapOf(): MutableMap<K, V> = LockedMap()

/** A map whose every access holds the app lock (enough for the app's caches). */
private class LockedMap<K : Any, V : Any> : MutableMap<K, V> {
    private val map = LinkedHashMap<K, V>()
    override val size: Int get() = locked(this) { map.size }
    override fun isEmpty(): Boolean = locked(this) { map.isEmpty() }
    override fun containsKey(key: K): Boolean = locked(this) { map.containsKey(key) }
    override fun containsValue(value: V): Boolean = locked(this) { map.containsValue(value) }
    override fun get(key: K): V? = locked(this) { map[key] }
    override val keys: MutableSet<K> get() = locked(this) { LinkedHashSet(map.keys) }
    override val values: MutableCollection<V> get() = locked(this) { ArrayList(map.values) }
    override val entries: MutableSet<MutableMap.MutableEntry<K, V>> get() = locked(this) { LinkedHashMap(map).entries }
    override fun put(key: K, value: V): V? = locked(this) { map.put(key, value) }
    override fun remove(key: K): V? = locked(this) { map.remove(key) }
    override fun putAll(from: Map<out K, V>) = locked(this) { map.putAll(from) }
    override fun clear() = locked(this) { map.clear() }
}

actual fun digest(algorithm: String, bytes: ByteArray): ByteArray {
    val name = algorithm.lowercase().replace("-", "")
    val length = when (name) {
        "md5" -> CC_MD5_DIGEST_LENGTH
        "sha1" -> CC_SHA1_DIGEST_LENGTH
        "sha256" -> CC_SHA256_DIGEST_LENGTH
        else -> throw IllegalArgumentException("Unsupported digest $algorithm")
    }
    val out = UByteArray(length)
    val input = if (bytes.isEmpty()) ByteArray(1) else bytes
    input.usePinned { data ->
        out.usePinned { result ->
            when (name) {
                "md5" -> CC_MD5(data.addressOf(0), bytes.size.convert(), result.addressOf(0))
                "sha1" -> CC_SHA1(data.addressOf(0), bytes.size.convert(), result.addressOf(0))
                else -> CC_SHA256(data.addressOf(0), bytes.size.convert(), result.addressOf(0))
            }
        }
    }
    return out.toByteArray()
}

actual fun digestHex(algorithm: String, bytes: ByteArray): String = digest(algorithm, bytes).toHex()

actual fun hmac(algorithm: String, key: ByteArray, data: ByteArray): ByteArray {
    val sha256 = algorithm.lowercase().contains("256")
    val out = UByteArray(if (sha256) CC_SHA256_DIGEST_LENGTH else CC_SHA1_DIGEST_LENGTH)
    val k = if (key.isEmpty()) ByteArray(1) else key
    val d = if (data.isEmpty()) ByteArray(1) else data
    k.usePinned { kp ->
        d.usePinned { dp ->
            out.usePinned { op ->
                CCHmac(
                    (if (sha256) kCCHmacAlgSHA256 else kCCHmacAlgSHA1).convert(),
                    kp.addressOf(0), key.size.convert(),
                    dp.addressOf(0), data.size.convert(),
                    op.addressOf(0),
                )
            }
        }
    }
    return out.toByteArray()
}

actual fun logError(message: String) = println(message)

@Suppress("CAST_NEVER_SUCCEEDS")
actual fun normalizeNfd(text: String): String = (text as NSString).decomposedStringWithCanonicalMapping

@Suppress("CAST_NEVER_SUCCEEDS")
actual fun normalizeNfc(text: String): String = (text as NSString).precomposedStringWithCanonicalMapping

actual fun deviceLanguage(): String = NSLocale.currentLocale.languageCode

/** The patterns the app uses: %s %d %x %X %f %% with flags 0 - + , width and precision. */
actual fun String.fmt(vararg args: Any?): String {
    val out = StringBuilder()
    var argIndex = 0
    var i = 0
    while (i < length) {
        val c = this[i]
        if (c != '%') { out.append(c); i++; continue }
        i++
        if (i >= length) break
        if (this[i] == '%') { out.append('%'); i++; continue }
        if (this[i] == 'n') { out.append('\n'); i++; continue }
        var zero = false
        var left = false
        var plus = false
        while (i < length && this[i] in "0-+ #,") {
            when (this[i]) { '0' -> zero = true; '-' -> left = true; '+' -> plus = true }
            i++
        }
        var width = 0
        while (i < length && this[i].isDigit()) { width = width * 10 + (this[i] - '0'); i++ }
        var precision = -1
        if (i < length && this[i] == '.') {
            i++
            precision = 0
            while (i < length && this[i].isDigit()) { precision = precision * 10 + (this[i] - '0'); i++ }
        }
        if (i >= length) break
        val conversion = this[i]
        i++
        val arg = args.getOrNull(argIndex++)
        var text = when (conversion) {
            'd' -> (arg as? Number)?.toLong()?.toString() ?: arg.toString()
            'x' -> (arg as? Number)?.let { hexOf(it) } ?: arg.toString()
            'X' -> (arg as? Number)?.let { hexOf(it).uppercase() } ?: arg.toString()
            'f' -> fixed((arg as? Number)?.toDouble() ?: 0.0, if (precision < 0) 6 else precision)
            's' -> arg.toString().let { if (precision >= 0 && it.length > precision) it.take(precision) else it }
            'c' -> arg.toString()
            else -> arg.toString()
        }
        if (plus && conversion in "df" && !text.startsWith("-")) text = "+$text"
        if (text.length < width) {
            text = when {
                left -> text.padEnd(width)
                zero && conversion in "dxXf" -> {
                    val negative = text.startsWith("-") || text.startsWith("+")
                    if (negative) text[0] + text.substring(1).padStart(width - 1, '0') else text.padStart(width, '0')
                }
                else -> text.padStart(width)
            }
        }
        out.append(text)
    }
    return out.toString()
}

private fun hexOf(value: Number): String = when (value) {
    is Byte -> (value.toInt() and 0xFF).toString(16)
    is Short -> (value.toInt() and 0xFFFF).toString(16)
    is Int -> value.toUInt().toString(16)
    else -> value.toLong().toULong().toString(16)
}

private fun fixed(value: Double, digits: Int): String {
    if (value.isNaN()) return "NaN"
    if (value.isInfinite()) return if (value > 0) "Infinity" else "-Infinity"
    val factor = 10.0.pow(digits)
    val scaled = round(abs(value) * factor).toLong()
    val whole = scaled / factor.toLong().coerceAtLeast(1)
    val fraction = if (digits == 0) "" else "." + (scaled % factor.toLong()).toString().padStart(digits, '0')
    val sign = if (value < 0 && scaled != 0L) "-" else ""
    return sign + whole + fraction
}

actual fun systemProperty(name: String): String? = when (name) {
    "recordlabs.app-version" -> platform.Foundation.NSBundle.mainBundle.infoDictionary?.get("CFBundleShortVersionString") as? String
    else -> null
}

actual fun maxMemoryBytes(): Long = platform.Foundation.NSProcessInfo.processInfo.physicalMemory.toLong()

actual fun processorCount(): Int = platform.Foundation.NSProcessInfo.processInfo.activeProcessorCount.toInt()

@Suppress("CAST_NEVER_SUCCEEDS")
actual fun normalizeNfkd(text: String): String = (text as NSString).decomposedStringWithCompatibilityMapping

actual val IoDispatcher: kotlinx.coroutines.CoroutineDispatcher get() = kotlinx.coroutines.Dispatchers.IO

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class, kotlinx.coroutines.DelicateCoroutinesApi::class)
actual fun lowPriorityWorker(name: String): kotlinx.coroutines.CoroutineDispatcher =
    kotlinx.coroutines.newSingleThreadContext(name)

actual fun resolveUrl(base: String, relative: String): String {
    val baseUrl = platform.Foundation.NSURL.URLWithString(base) ?: return relative
    return platform.Foundation.NSURL.URLWithString(relative, baseUrl)?.absoluteString ?: relative
}

actual fun urlHost(url: String): String = platform.Foundation.NSURL.URLWithString(url)?.host.orEmpty()

actual fun systemEnv(name: String): String? = null

@Suppress("CAST_NEVER_SUCCEEDS")
actual fun characterBreaks(text: String): List<Int> {
    val native = text as NSString
    val out = ArrayList<Int>()
    var index = 0
    while (index < text.length) {
        val range = native.rangeOfComposedCharacterSequenceAtIndex(index.toULong())
        index = range.useContents { (location + length).toInt() }
        if (index < text.length) out += index
    }
    return out
}

actual fun timeAgoMillis(months: Int, weeks: Int): Long {
    val calendar = platform.Foundation.NSCalendar.currentCalendar
    val components = platform.Foundation.NSDateComponents().apply {
        setMonth(-months.toLong())
        setWeekOfYear(-weeks.toLong())
    }
    val date = calendar.dateByAddingComponents(components, platform.Foundation.NSDate(), 0u) ?: platform.Foundation.NSDate()
    return (date.timeIntervalSince1970 * 1000.0).toLong()
}

actual fun countryName(code: String): String =
    platform.Foundation.NSLocale(localeIdentifier = "en_US").displayNameForKey(platform.Foundation.NSLocaleCountryCode, value = code) ?: code

actual fun openFolder(folder: File) = Unit

actual fun deviceCountry(): String = platform.Foundation.NSLocale.currentLocale.countryCode.orEmpty()
