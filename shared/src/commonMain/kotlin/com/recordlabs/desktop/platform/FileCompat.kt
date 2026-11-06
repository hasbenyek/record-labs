package com.recordlabs.desktop.platform

/**
 * A file or folder path, with the java.io.File members the shared app uses. On the
 * desktop and Android it wraps java.io.File (same behaviour as before); on iOS it is
 * backed by okio.
 */
expect class File {
    constructor(path: String)
    constructor(parent: File?, child: String)
    constructor(parent: String?, child: String)

    val name: String
    val path: String
    val absolutePath: String
    val parent: String?
    val parentFile: File?
    val isFile: Boolean
    val isDirectory: Boolean

    fun exists(): Boolean
    fun length(): Long
    fun lastModified(): Long
    fun setLastModified(time: Long): Boolean
    fun delete(): Boolean
    fun mkdirs(): Boolean
    fun createNewFile(): Boolean
    fun renameTo(dest: File): Boolean
    fun listFiles(): Array<File>?
    fun list(): Array<String>?

    fun readBytes(): ByteArray
    fun writeBytes(bytes: ByteArray)
    fun readText(): String
    fun writeText(text: String)
    fun appendText(text: String)
}

val File.extension: String get() = name.substringAfterLast('.', "")
val File.nameWithoutExtension: String get() = name.substringBeforeLast(".")

fun File.listFiles(filter: (File) -> Boolean): Array<File>? = listFiles()?.filter(filter)?.toTypedArray()
fun File.readLines(): List<String> = readText().lines().let { if (it.isNotEmpty() && it.last().isEmpty()) it.dropLast(1) else it }
fun File.resolve(child: String): File = File(this, child)

fun File.deleteRecursively(): Boolean {
    if (isDirectory) listFiles()?.forEach { it.deleteRecursively() }
    return delete() || !exists()
}

fun File.walkTopDown(): Sequence<File> = sequence {
    yield(this@walkTopDown)
    if (isDirectory) listFiles()?.forEach { yieldAll(it.walkTopDown()) }
}

fun File.copyTo(target: File, overwrite: Boolean = false): File {
    if (target.exists() && !overwrite) throw IllegalStateException("The destination file already exists: ${target.path}")
    target.parentFile?.mkdirs()
    target.writeBytes(readBytes())
    return target
}

/** Moves [source] over [target] in one step (Files.move ATOMIC_MOVE + REPLACE_EXISTING). */
expect fun atomicReplace(source: File, target: File)

fun File.appendBytes(bytes: ByteArray) {
    val existing = if (exists()) readBytes() else ByteArray(0)
    writeBytes(existing + bytes)
}
