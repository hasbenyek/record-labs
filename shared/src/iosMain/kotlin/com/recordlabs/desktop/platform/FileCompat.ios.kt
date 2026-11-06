package com.recordlabs.desktop.platform

import okio.FileSystem
import okio.Path
import okio.Path.Companion.toPath
import okio.buffer
import okio.use

/** okio underneath (the app's own folders on iOS). */
actual class File private constructor(val okPath: Path, @Suppress("UNUSED_PARAMETER") wrap: Boolean) {
    actual constructor(path: String) : this(path.toPath(normalize = false), true)
    actual constructor(parent: File?, child: String) : this(if (parent == null) child.toPath() else parent.okPath / child, true)
    actual constructor(parent: String?, child: String) : this(if (parent == null) child.toPath() else parent.toPath() / child, true)

    private val fs get() = FileSystem.SYSTEM
    private fun meta() = runCatching { fs.metadataOrNull(okPath) }.getOrNull()

    actual val name: String get() = okPath.name
    actual val path: String get() = okPath.toString()
    actual val absolutePath: String get() = okPath.toString()
    actual val parent: String? get() = okPath.parent?.toString()
    actual val parentFile: File? get() = okPath.parent?.let { File(it, true) }
    actual val isFile: Boolean get() = meta()?.isRegularFile == true
    actual val isDirectory: Boolean get() = meta()?.isDirectory == true

    actual fun exists(): Boolean = meta() != null
    actual fun length(): Long = meta()?.size ?: 0L
    actual fun lastModified(): Long = meta()?.lastModifiedAtMillis ?: 0L
    actual fun setLastModified(time: Long): Boolean = false
    actual fun delete(): Boolean = runCatching { fs.delete(okPath, mustExist = true); true }.getOrDefault(false)
    actual fun mkdirs(): Boolean = runCatching {
        if (isDirectory) false else { fs.createDirectories(okPath); true }
    }.getOrDefault(false)
    actual fun createNewFile(): Boolean = runCatching {
        if (exists()) false else { fs.write(okPath, mustCreate = true) {}; true }
    }.getOrDefault(false)
    actual fun renameTo(dest: File): Boolean = runCatching { fs.atomicMove(okPath, dest.okPath); true }.getOrDefault(false)
    actual fun listFiles(): Array<File>? = runCatching { fs.list(okPath).map { File(it, true) }.toTypedArray() }.getOrNull()
    actual fun list(): Array<String>? = runCatching { fs.list(okPath).map { it.name }.toTypedArray() }.getOrNull()

    actual fun readBytes(): ByteArray = fs.read(okPath) { readByteArray() }
    actual fun writeBytes(bytes: ByteArray) { fs.write(okPath) { write(bytes) } }
    actual fun readText(): String = fs.read(okPath) { readUtf8() }
    actual fun writeText(text: String) { fs.write(okPath) { writeUtf8(text) } }
    actual fun appendText(text: String) {
        fs.appendingSink(okPath, mustExist = false).buffer().use { it.writeUtf8(text) }
    }

    override fun equals(other: Any?): Boolean = other is File && other.okPath == okPath
    override fun hashCode(): Int = okPath.hashCode()
    override fun toString(): String = path
}

actual fun atomicReplace(source: File, target: File) {
    FileSystem.SYSTEM.atomicMove(source.okPath, target.okPath)
}
