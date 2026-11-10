package com.recordlabs.desktop.platform

import platform.Foundation.NSUserDefaults

/** NSUserDefaults, one key per setting: "<node path>/<key>". */
actual class PrefsNode(private val path: String) {
    private val defaults get() = NSUserDefaults.standardUserDefaults
    private fun k(key: String) = "$path/$key"
    private fun has(key: String) = defaults.objectForKey(k(key)) != null

    @Suppress("UNCHECKED_CAST")
    actual fun get(key: String, default: String): String = defaults.stringForKey(k(key)) ?: default

    actual fun get(key: String, default: String?): String? = defaults.stringForKey(k(key)) ?: default
    actual fun put(key: String, value: String) = defaults.setObject(value, k(key))
    actual fun getBoolean(key: String, default: Boolean): Boolean = if (has(key)) defaults.boolForKey(k(key)) else default
    actual fun putBoolean(key: String, value: Boolean) = defaults.setBool(value, k(key))
    actual fun getInt(key: String, default: Int): Int = if (has(key)) defaults.integerForKey(k(key)).toInt() else default
    actual fun putInt(key: String, value: Int) = defaults.setInteger(value.toLong(), k(key))
    actual fun getLong(key: String, default: Long): Long = if (has(key)) defaults.integerForKey(k(key)) else default
    actual fun putLong(key: String, value: Long) = defaults.setInteger(value, k(key))
    actual fun getFloat(key: String, default: Float): Float = if (has(key)) defaults.floatForKey(k(key)) else default
    actual fun putFloat(key: String, value: Float) = defaults.setFloat(value, k(key))
    actual fun getDouble(key: String, default: Double): Double = if (has(key)) defaults.doubleForKey(k(key)) else default
    actual fun putDouble(key: String, value: Double) = defaults.setDouble(value, k(key))
    actual fun remove(key: String) = defaults.removeObjectForKey(k(key))
    actual fun keys(): Array<String> {
        val prefix = "$path/"
        return defaults.dictionaryRepresentation().keys
            .mapNotNull { it as? String }
            .filter { it.startsWith(prefix) && !it.substring(prefix.length).contains('/') }
            .map { it.substring(prefix.length) }
            .toTypedArray()
    }
    actual fun clear() = keys().forEach(::remove)
    actual fun flush() { defaults.synchronize() }
    actual fun node(path: String): PrefsNode = PrefsNode(this.path.trimEnd('/') + "/" + path.trim('/'))
    actual fun childrenNames(): Array<String> {
        val prefix = "$path/"
        return defaults.dictionaryRepresentation().keys
            .mapNotNull { it as? String }
            .filter { it.startsWith(prefix) && it.substring(prefix.length).contains('/') }
            .map { it.substring(prefix.length).substringBefore('/') }
            .distinct()
            .toTypedArray()
    }
}

actual fun prefsNode(path: String): PrefsNode = PrefsNode("rl/" + path.trim('/'))
