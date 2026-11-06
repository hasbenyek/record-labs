package com.recordlabs.desktop.data

import java.io.File

/**
 * Finds the helper programs that ship inside Record Labs (yt-dlp, Deno).
 *
 * Order: the installed app's resources folder, then the development checkout
 * (src/appResources/windows-x64 or linux-x64). On Linux a copy installed by
 * the system package manager (found on PATH) is the last fallback.
 *
 * Linux needs the executable bit, which Windows doesn't. The packages set it,
 * but when a bundled copy still can't be run (a read-only AppImage mount, a
 * root-owned /opt install that lost the bit) it is copied once into
 * ~/.cache/record-labs/bin and run from there.
 */
object BundledTools {
    fun find(baseName: String): File? {
        val name = Platform.executableName(baseName)
        val resourceDir = System.getProperty("compose.application.resources.dir")
        val nativeDir = Platform.nativeResourceDirName
        val candidates = buildList {
            if (!resourceDir.isNullOrBlank()) add(File(resourceDir, name))
            add(File(System.getProperty("user.dir"), "src/appResources/$nativeDir/$name"))
            add(File("src/appResources/$nativeDir/$name"))
            add(File("windows-recordlabs/src/appResources/$nativeDir/$name"))
        }
        candidates.map(File::getAbsoluteFile).firstOrNull { it.isFile }?.let { file ->
            return if (Platform.isWindows) file else runnable(file)
        }
        if (Platform.isWindows) return null
        return System.getenv("PATH").orEmpty().split(File.pathSeparator)
            .filter(String::isNotBlank)
            .map { File(it, name) }
            .firstOrNull { it.isFile && it.canExecute() }
    }

    /** A copy of [file] this user can execute (the file itself when it already is). */
    private fun runnable(file: File): File? {
        if (file.canExecute()) return file
        if (runCatching { file.setExecutable(true) }.getOrDefault(false) && file.canExecute()) return file
        return synchronized(this) {
            runCatching {
                val dir = File(Platform.cacheRoot, "bin").apply { mkdirs() }
                val copy = File(dir, file.name)
                // Re-copy when the app was updated (size or date differ).
                if (!copy.isFile || copy.length() != file.length() || copy.lastModified() < file.lastModified()) {
                    val partial = File(dir, file.name + ".part")
                    file.copyTo(partial, overwrite = true)
                    partial.setExecutable(true, true)
                    if (!partial.renameTo(copy)) {
                        copy.delete()
                        partial.renameTo(copy)
                    }
                    copy.setLastModified(file.lastModified())
                }
                copy.setExecutable(true, true)
                copy.takeIf { it.canExecute() }
            }.getOrNull()
        }
    }
}
