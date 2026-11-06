package com.recordlabs.desktop

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast

/** The Android application, set by the app before the UI starts. */
object AndroidAppContext {
    @Volatile lateinit var context: Context
        private set

    fun init(context: Context) {
        this.context = context.applicationContext
    }
}

actual fun copyToClipboard(text: String) {
    runCatching {
        val context = AndroidAppContext.context
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("Record Labs", text))
    }
}

actual fun pickMusicFolder(title: String, onPicked: (java.io.File) -> Unit) {
    runCatching {
        Toast.makeText(AndroidAppContext.context, "Local music folders are not available on Android yet", Toast.LENGTH_SHORT).show()
    }
}

/** Backups go to Android/data/com.recordlabs.android/files/backups (reachable from a computer over USB). */
private fun backupFolder(): java.io.File =
    (AndroidAppContext.context.getExternalFilesDir("backups") ?: java.io.File(AndroidAppContext.context.filesDir, "backups")).apply { mkdirs() }

actual fun pickBackupSaveFile(defaultName: String, onPicked: (java.io.File) -> Unit) {
    runCatching { onPicked(java.io.File(backupFolder(), defaultName)) }
}

actual fun pickBackupOpenFile(onPicked: (java.io.File) -> Unit) {
    val newest = runCatching {
        backupFolder().listFiles { file -> file.isFile && file.extension.equals("zip", true) }?.maxByOrNull { it.lastModified() }
    }.getOrNull()
    if (newest != null) onPicked(newest)
    else runCatching {
        Toast.makeText(AndroidAppContext.context, "No backup found in ${backupFolder().absolutePath}", Toast.LENGTH_LONG).show()
    }
}
