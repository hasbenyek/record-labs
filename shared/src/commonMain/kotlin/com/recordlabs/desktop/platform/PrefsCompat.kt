package com.recordlabs.desktop.platform

/**
 * Saved settings (java.util.prefs.Preferences on the desktop and Android, kept in the
 * same nodes as before; NSUserDefaults on iOS).
 */
expect class PrefsNode {
    fun <T : String?> get(key: String, default: T): T
    fun put(key: String, value: String)
    fun getBoolean(key: String, default: Boolean): Boolean
    fun putBoolean(key: String, value: Boolean)
    fun getInt(key: String, default: Int): Int
    fun putInt(key: String, value: Int)
    fun getLong(key: String, default: Long): Long
    fun putLong(key: String, value: Long)
    fun getFloat(key: String, default: Float): Float
    fun putFloat(key: String, value: Float)
    fun getDouble(key: String, default: Double): Double
    fun putDouble(key: String, value: Double)
    fun remove(key: String)
    fun keys(): Array<String>
    fun clear()
    fun flush()
    fun node(path: String): PrefsNode
    fun childrenNames(): Array<String>
}

/** Preferences.userRoot().node(path) */
expect fun prefsNode(path: String): PrefsNode
