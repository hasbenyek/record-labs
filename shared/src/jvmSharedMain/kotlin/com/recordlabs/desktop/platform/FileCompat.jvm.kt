package com.recordlabs.desktop.platform

/** java.io.File underneath: the desktop and Android behave exactly as before. */
actual class File private constructor(val javaFile: java.io.File, @Suppress("UNUSED_PARAMETER") wrap: Boolean) {
    actual constructor(path: String) : this(java.io.File(path), true)
    actual constructor(parent: File?, child: String) : this(java.io.File(parent?.javaFile, child), true)
    actual constructor(parent: String?, child: String) : this(java.io.File(parent, child), true)

    actual val name: String get() = javaFile.name
    actual val path: String get() = javaFile.path
    actual val absolutePath: String get() = javaFile.absolutePath
    actual val parent: String? get() = javaFile.parent
    actual val parentFile: File? get() = javaFile.parentFile?.let(::wrap)
    actual val isFile: Boolean get() = javaFile.isFile
    actual val isDirectory: Boolean get() = javaFile.isDirectory

    actual fun exists(): Boolean = javaFile.exists()
    actual fun length(): Long = javaFile.length()
    actual fun lastModified(): Long = javaFile.lastModified()
    actual fun setLastModified(time: Long): Boolean = javaFile.setLastModified(time)
    actual fun delete(): Boolean = javaFile.delete()
    actual fun mkdirs(): Boolean = javaFile.mkdirs()
    actual fun createNewFile(): Boolean = javaFile.createNewFile()
    actual fun renameTo(dest: File): Boolean = javaFile.renameTo(dest.javaFile)
    actual fun listFiles(): Array<File>? = javaFile.listFiles()?.map(::wrap)?.toTypedArray()
    actual fun list(): Array<String>? = javaFile.list()

    actual fun readBytes(): ByteArray = javaFile.readBytes()
    actual fun writeBytes(bytes: ByteArray) = javaFile.writeBytes(bytes)
    actual fun readText(): String = javaFile.readText()
    actual fun writeText(text: String) = javaFile.writeText(text)
    actual fun appendText(text: String) = javaFile.appendText(text)

    override fun equals(other: Any?): Boolean = other is File && other.javaFile == javaFile
    override fun hashCode(): Int = javaFile.hashCode()
    override fun toString(): String = javaFile.toString()

    companion object {
        fun wrap(file: java.io.File): File = File(file, true)
    }
}

/** The common File for a java.io.File (desktop and Android code). */
fun java.io.File.common(): File = File.wrap(this)

actual fun atomicReplace(source: File, target: File) {
    java.nio.file.Files.move(
        source.javaFile.toPath(), target.javaFile.toPath(),
        java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE,
    )
}
