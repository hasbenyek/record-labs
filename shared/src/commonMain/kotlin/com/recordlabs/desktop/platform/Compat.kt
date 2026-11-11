package com.recordlabs.desktop.platform

/*
 * What the shared app used from the Java library, for every platform (desktop and
 * Android keep the Java classes underneath, so they behave exactly as before; iOS has
 * its own). Code moved to commonMain uses these instead of java.* directly.
 */

/** System.currentTimeMillis(). */
expect fun currentTimeMillis(): Long

/** System.nanoTime(): only for measuring time between two moments. */
expect fun nanoTime(): Long

/** synchronized(lock) { ... } */
expect inline fun <T> locked(lock: Any, block: () -> T): T

/** String.format (%d, %02d, %x, %02x, %s, %.1f ...): "%d:%02d".fmt(m, s). */
expect fun String.fmt(vararg args: Any?): String

/** A map safe to use from several threads (ConcurrentHashMap on the JVM). */
expect fun <K : Any, V : Any> concurrentMapOf(): MutableMap<K, V>

/** Hex digest ("md5", "sha-1", "sha-256") of [bytes]. */
expect fun digestHex(algorithm: String, bytes: ByteArray): String

/** Raw digest bytes. */
expect fun digest(algorithm: String, bytes: ByteArray): ByteArray

/** HMAC-SHA1 / HMAC-SHA256 ("HmacSHA1", "HmacSHA256"). */
expect fun hmac(algorithm: String, key: ByteArray, data: ByteArray): ByteArray

/** Prints to the error stream (System.err.println). */
expect fun logError(message: String)

/** Unicode NFD / NFC (java.text.Normalizer). */
expect fun normalizeNfd(text: String): String
expect fun normalizeNfc(text: String): String

/** The device's language, like "en" or "id" (Locale.getDefault().language). */
expect fun deviceLanguage(): String

fun String.sha256Hex(): String = digestHex("sha-256", encodeToByteArray())
fun String.md5Hex(): String = digestHex("md5", encodeToByteArray())

/** URLEncoder.encode(value, UTF-8) (form encoding: space -> +). */
fun urlEncode(value: String): String {
    val bytes = value.encodeToByteArray()
    val out = StringBuilder(bytes.size * 3)
    for (b in bytes) {
        val c = b.toInt() and 0xFF
        when {
            c in 'a'.code..'z'.code || c in 'A'.code..'Z'.code || c in '0'.code..'9'.code ||
                c == '.'.code || c == '-'.code || c == '*'.code || c == '_'.code -> out.append(c.toChar())
            c == ' '.code -> out.append('+')
            else -> {
                out.append('%')
                out.append(HEX[c shr 4])
                out.append(HEX[c and 0x0F])
            }
        }
    }
    return out.toString()
}

/** URLDecoder.decode(value, UTF-8). */
fun urlDecode(value: String): String {
    val out = ArrayList<Byte>(value.length)
    var i = 0
    while (i < value.length) {
        val c = value[i]
        when {
            c == '+' -> { out.add(' '.code.toByte()); i++ }
            c == '%' && i + 2 < value.length -> {
                val hex = value.substring(i + 1, i + 3).toIntOrNull(16)
                if (hex != null) { out.add(hex.toByte()); i += 3 } else { out.addAll(c.toString().encodeToByteArray().toList()); i++ }
            }
            else -> { out.addAll(c.toString().encodeToByteArray().toList()); i++ }
        }
    }
    return out.toByteArray().decodeToString()
}

private const val HEX = "0123456789ABCDEF"

fun ByteArray.toHex(): String {
    val out = StringBuilder(size * 2)
    for (b in this) {
        val v = b.toInt() and 0xFF
        out.append("0123456789abcdef"[v shr 4]).append("0123456789abcdef"[v and 0x0F])
    }
    return out.toString()
}

/** System.getProperty(name) (iOS: "recordlabs.app-version" is the app's version). */
expect fun systemProperty(name: String): String?

/** Memory the app may use, in bytes (Runtime.maxMemory on the JVM). */
expect fun maxMemoryBytes(): Long

/** Number of processor cores. */
expect fun processorCount(): Int

/** Unicode NFKD (java.text.Normalizer.Form.NFKD). */
expect fun normalizeNfkd(text: String): String

/** A list for listeners: reads never block, every change copies (CopyOnWriteArrayList). */
class CopyOnWriteList<T> : Iterable<T> {
    @kotlin.concurrent.Volatile
    private var items: List<T> = emptyList()
    private val lock = Any()
    fun add(item: T): Boolean = locked(lock) { items = items + item; true }
    fun remove(item: T): Boolean = locked(lock) { val before = items.size; items = items - item; items.size != before }
    fun clear() = locked(lock) { items = emptyList() }
    val size: Int get() = items.size
    fun isEmpty(): Boolean = items.isEmpty()
    fun toList(): List<T> = items
    override fun iterator(): Iterator<T> = items.iterator()
}

/** A map that keeps at most [maxSize] entries, dropping the least recently used (LinkedHashMap access order). */
class LruMap<K, V>(private val maxSize: Int) : MutableMap<K, V> {
    private val map = LinkedHashMap<K, V>()
    override val size: Int get() = map.size
    override fun isEmpty(): Boolean = map.isEmpty()
    override fun containsKey(key: K): Boolean = map.containsKey(key)
    override fun containsValue(value: V): Boolean = map.containsValue(value)
    override fun get(key: K): V? {
        if (!map.containsKey(key)) return null
        @Suppress("UNCHECKED_CAST")
        val value = map.remove(key) as V
        map[key] = value
        return value
    }
    override val keys: MutableSet<K> get() = map.keys
    override val values: MutableCollection<V> get() = map.values
    override val entries: MutableSet<MutableMap.MutableEntry<K, V>> get() = map.entries
    override fun put(key: K, value: V): V? {
        val old = map.remove(key)
        map[key] = value
        while (map.size > maxSize) map.remove(map.keys.first())
        return old
    }
    override fun remove(key: K): V? = map.remove(key)
    override fun putAll(from: Map<out K, V>) = from.forEach { (k, v) -> put(k, v) }
    override fun clear() = map.clear()
}

/** Map.remove(key, value): removes only while [key] still maps to [value]. */
fun <K, V> MutableMap<K, V>.removeExact(key: K, value: V): Boolean =
    if (containsKey(key) && this[key] == value) { remove(key); true } else false

/** IoDispatcher (blocking work: files, network). */
expect val IoDispatcher: kotlinx.coroutines.CoroutineDispatcher

/** @Synchronized on the desktop and Android (no effect on iOS, whose code locks with [locked]). */
@OptIn(ExperimentalMultiplatform::class)
@OptionalExpectation
@Target(AnnotationTarget.FUNCTION, AnnotationTarget.PROPERTY_GETTER, AnnotationTarget.PROPERTY_SETTER)
expect annotation class JvmSync()

/** One background thread of low priority, as a coroutine dispatcher (Executors.newSingleThreadExecutor). */
expect fun lowPriorityWorker(name: String): kotlinx.coroutines.CoroutineDispatcher

/** keys.removeIf { } */
fun <K, V> MutableMap<K, V>.removeKeysIf(predicate: (K) -> Boolean) {
    keys.filter(predicate).forEach { remove(it) }
}

/** Map.putIfAbsent (the JVM's own one is used there). */
fun <K, V> MutableMap<K, V>.putIfAbsent(key: K, value: V): V? = get(key) ?: run { put(key, value); null }

/** Map.remove(key, value) (the JVM's own one is used there). */
fun <K, V> MutableMap<K, V>.remove(key: K, value: V): Boolean = removeExact(key, value)

/** Map.replace(key, oldValue, newValue) (the JVM's own one is used there). */
fun <K, V> MutableMap<K, V>.replace(key: K, oldValue: V, newValue: V): Boolean =
    if (containsKey(key) && this[key] == oldValue) { put(key, newValue); true } else false

/** java.net.URI(base).resolve(relative).toString() */
expect fun resolveUrl(base: String, relative: String): String

/** The host of a URL ("" when it has none). */
expect fun urlHost(url: String): String

/** System.getenv(name) (null on iOS). */
expect fun systemEnv(name: String): String?

/** A set every access of which holds a lock (Collections.synchronizedSet). */
class LockedSet<T> : MutableSet<T> {
    private val set = LinkedHashSet<T>()
    override val size: Int get() = locked(this) { set.size }
    override fun isEmpty(): Boolean = locked(this) { set.isEmpty() }
    override fun contains(element: T): Boolean = locked(this) { set.contains(element) }
    override fun containsAll(elements: Collection<T>): Boolean = locked(this) { set.containsAll(elements) }
    override fun iterator(): MutableIterator<T> = locked(this) { LinkedHashSet(set) }.iterator()
    override fun add(element: T): Boolean = locked(this) { set.add(element) }
    override fun addAll(elements: Collection<T>): Boolean = locked(this) { set.addAll(elements) }
    override fun remove(element: T): Boolean = locked(this) { set.remove(element) }
    override fun removeAll(elements: Collection<T>): Boolean = locked(this) { set.removeAll(elements.toSet()) }
    override fun retainAll(elements: Collection<T>): Boolean = locked(this) { set.retainAll(elements.toSet()) }
    override fun clear() = locked(this) { set.clear() }
    fun removeIf(predicate: (T) -> Boolean): Boolean = locked(this) { set.removeAll(set.filter(predicate).toSet()) }
}

/** java.util.Random's own sequence (same numbers on every platform for a seed). */
class JavaRandom(seed: Long) {
    private var state = (seed xor 0x5DEECE66DL) and ((1L shl 48) - 1)
    private fun next(bits: Int): Int {
        state = (state * 0x5DEECE66DL + 0xBL) and ((1L shl 48) - 1)
        return (state ushr (48 - bits)).toInt()
    }
    fun nextFloat(): Float = next(24) / (1 shl 24).toFloat()
    fun nextInt(): Int = next(32)
}

/** AtomicInteger (incrementAndGet, get). */
class AtomicIntCompat(initial: Int = 0) {
    @kotlin.concurrent.Volatile private var value = initial
    fun get(): Int = value
    fun set(newValue: Int) { value = newValue }
    fun incrementAndGet(): Int = locked(this) { ++value }
    fun getAndIncrement(): Int = locked(this) { value++ }
}

/** Math.pow */
fun mathPow(a: Double, b: Double): Double = kotlin.math.exp(b * kotlin.math.ln(a)).let { if (a == 0.0) 0.0 else it }

/** String.codePointAt(index) */
fun String.codePointAtCompat(index: Int): Int {
    val high = this[index]
    if (high.isHighSurrogate() && index + 1 < length && this[index + 1].isLowSurrogate()) {
        return ((high.code - 0xD800) shl 10) + (this[index + 1].code - 0xDC00) + 0x10000
    }
    return high.code
}

/** Start offsets of user-perceived characters after the first (java.text.BreakIterator character breaks). */
expect fun characterBreaks(text: String): List<Int>

/** Milliseconds of the moment [months] months and [weeks] weeks ago (local calendar). */
expect fun timeAgoMillis(months: Int = 0, weeks: Int = 0): Long

/** The English name of a country code ("ID" -> "Indonesia"). */
expect fun countryName(code: String): String

/** Opens a folder in the file manager (desktop). */
expect fun openFolder(folder: File)

/** Unicode NFKC. */
fun normalizeNfkc(text: String): String = normalizeNfc(normalizeNfkd(text))

/** The device's region, like "US" or "ID" (Locale.getDefault().country). */
expect fun deviceCountry(): String
