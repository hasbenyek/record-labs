package com.recordlabs.desktop.platform

import java.util.prefs.Preferences

/** java.util.prefs underneath: the same saved settings as before. */
actual class PrefsNode(val preferences: Preferences) {
    @Suppress("UNCHECKED_CAST")
    actual fun get(key: String, default: String): String = preferences.get(key, default) ?: default

    // Another JVM name: both get(String, String) overloads have the same JVM signature.
    @kotlin.jvm.JvmName("getNullable")
    actual fun get(key: String, default: String?): String? = preferences.get(key, default)
    actual fun put(key: String, value: String) = preferences.put(key, value)
    actual fun getBoolean(key: String, default: Boolean): Boolean = preferences.getBoolean(key, default)
    actual fun putBoolean(key: String, value: Boolean) = preferences.putBoolean(key, value)
    actual fun getInt(key: String, default: Int): Int = preferences.getInt(key, default)
    actual fun putInt(key: String, value: Int) = preferences.putInt(key, value)
    actual fun getLong(key: String, default: Long): Long = preferences.getLong(key, default)
    actual fun putLong(key: String, value: Long) = preferences.putLong(key, value)
    actual fun getFloat(key: String, default: Float): Float = preferences.getFloat(key, default)
    actual fun putFloat(key: String, value: Float) = preferences.putFloat(key, value)
    actual fun getDouble(key: String, default: Double): Double = preferences.getDouble(key, default)
    actual fun putDouble(key: String, value: Double) = preferences.putDouble(key, value)
    actual fun remove(key: String) = preferences.remove(key)
    actual fun keys(): Array<String> = runCatching { preferences.keys() }.getOrDefault(emptyArray())
    actual fun clear() { runCatching { preferences.clear() } }
    actual fun flush() { runCatching { preferences.flush() } }
    actual fun node(path: String): PrefsNode = PrefsNode(preferences.node(path))
    actual fun childrenNames(): Array<String> = runCatching { preferences.childrenNames() }.getOrDefault(emptyArray())
}

actual fun prefsNode(path: String): PrefsNode = PrefsNode(Preferences.userRoot().node(path))
