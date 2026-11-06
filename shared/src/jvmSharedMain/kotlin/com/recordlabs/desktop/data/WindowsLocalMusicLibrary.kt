package com.recordlabs.desktop.data

import java.io.File
import java.io.RandomAccessFile
import java.nio.charset.Charset
import java.security.MessageDigest

/** Lightweight desktop local-music index with embedded MP3/FLAC tag support. */
object WindowsLocalMusicLibrary {
    private val supported = setOf("mp3", "flac", "m4a", "aac", "wav", "ogg", "opus", "wma", "aiff", "aif", "alac", "ape", "wv")
    private val foldersFile = File(WindowsStorage.directory, "local-music-folders.txt")
    /** Default folders (Music, OneDrive\\Music) the user removed in Settings > Storage. */
    private val excludedFile = File(WindowsStorage.directory, "local-music-excluded.txt")

    private fun excluded(): Set<String> =
        runCatching { excludedFile.readLines().map { it.trim().lowercase() }.filter(String::isNotBlank).toSet() }.getOrDefault(emptySet())

    fun folders(): List<File> {
        val saved = runCatching { foldersFile.readLines().map(::File) }.getOrDefault(emptyList())
        val home = File(System.getProperty("user.home"))
        val defaults = listOf(File(home, "Music"), File(home, "OneDrive/Music"))
        val hidden = excluded()
        return (saved + defaults).map(File::getAbsoluteFile).filter(File::isDirectory).distinctBy { it.path.lowercase() }
            .filterNot { it.path.lowercase() in hidden }
    }

    fun addFolder(folder: File) {
        if (!folder.isDirectory) return
        val path = folder.absoluteFile.path.lowercase()
        val hidden = excluded()
        if (path in hidden) runCatching { excludedFile.writeText((hidden - path).joinToString("\n")) }
        val updated = (folders() + folder.absoluteFile).distinctBy { it.path.lowercase() }
        foldersFile.parentFile?.mkdirs()
        foldersFile.writeText(updated.joinToString("\n", transform = File::getAbsolutePath))
    }

    /** Settings > Storage > Local music folders: stop scanning [folder] (its files are not touched). */
    fun removeFolder(folder: File) {
        val path = folder.absoluteFile.path.lowercase()
        val saved = runCatching { foldersFile.readLines().map(::File) }.getOrDefault(emptyList())
        runCatching {
            foldersFile.parentFile?.mkdirs()
            foldersFile.writeText(saved.filterNot { it.absoluteFile.path.lowercase() == path }.joinToString("\n", transform = File::getAbsolutePath))
            excludedFile.writeText((excluded() + path).joinToString("\n"))
        }
    }

    fun scan(): List<Track> = folders().asSequence()
        .flatMap { root -> root.walkTopDown().onEnter { it.name !in setOf(".git", "node_modules") }.asSequence() }
        .filter { it.isFile && it.extension.lowercase() in supported }
        .mapNotNull(::readTrack)
        .distinctBy(Track::id)
        .sortedWith(compareBy<Track> { it.artist.lowercase() }.thenBy { it.album.lowercase() }.thenBy { it.title.lowercase() })
        .toList()

    private fun readTrack(file: File): Track? = runCatching {
        val tags = when (file.extension.lowercase()) {
            "mp3" -> readId3(file)
            "flac" -> readFlacComments(file)
            else -> emptyMap()
        }
        val stem = file.nameWithoutExtension
        val split = stem.split(" - ", limit = 2)
        val fallbackArtist = split.getOrNull(0).takeIf { split.size == 2 }.orEmpty()
        val fallbackTitle = split.getOrNull(1) ?: stem
        val title = tags["TITLE"].orEmpty().ifBlank { fallbackTitle }
        val artist = tags["ARTIST"].orEmpty().ifBlank { fallbackArtist.ifBlank { "Local Music" } }
        val album = tags["ALBUM"].orEmpty().ifBlank { file.parentFile?.name.orEmpty() }
        val artworkUrl = cachedEmbeddedArtwork(file)
        Track(
            id = "local:${sha256(file.absolutePath.lowercase())}",
            title = title,
            artist = artist,
            album = album,
            artworkUrl = artworkUrl,
            isOfficial = true,
            localPath = file.absolutePath,
        )
    }.getOrNull()

    private fun readId3(file: File): Map<String, String> {
        RandomAccessFile(file, "r").use { input ->
            if (input.length() < 10) return emptyMap()
            val header = ByteArray(10).also(input::readFully)
            if (String(header, 0, 3, Charsets.ISO_8859_1) != "ID3") return readId3v1(input)
            val version = header[3].toInt() and 0xff
            val tagSize = synchsafe(header, 6)
            val end = minOf(input.length(), 10L + tagSize)
            val values = linkedMapOf<String, String>()
            while (input.filePointer + 10 <= end) {
                val frameHeader = ByteArray(10).also(input::readFully)
                val frameId = String(frameHeader, 0, 4, Charsets.ISO_8859_1)
                if (frameId.all { it == '\u0000' }) break
                val frameSize = if (version >= 4) synchsafe(frameHeader, 4) else
                    ((frameHeader[4].toInt() and 0xff) shl 24) or
                        ((frameHeader[5].toInt() and 0xff) shl 16) or
                        ((frameHeader[6].toInt() and 0xff) shl 8) or
                        (frameHeader[7].toInt() and 0xff)
                if (frameSize <= 0 || input.filePointer + frameSize > end) break
                val data = ByteArray(frameSize).also(input::readFully)
                val key = when (frameId) { "TIT2" -> "TITLE"; "TPE1" -> "ARTIST"; "TALB" -> "ALBUM"; else -> null }
                if (key != null) decodeId3Text(data)?.let { values[key] = it }
            }
            return values.ifEmpty { readId3v1(input) }
        }
    }

    private fun readId3v1(input: RandomAccessFile): Map<String, String> {
        if (input.length() < 128) return emptyMap()
        input.seek(input.length() - 128)
        val block = ByteArray(128).also(input::readFully)
        if (String(block, 0, 3, Charsets.ISO_8859_1) != "TAG") return emptyMap()
        fun field(offset: Int, length: Int) = String(block, offset, length, Charsets.ISO_8859_1).trim('\u0000', ' ')
        return mapOf("TITLE" to field(3, 30), "ARTIST" to field(33, 30), "ALBUM" to field(63, 30)).filterValues(String::isNotBlank)
    }

    private fun decodeId3Text(data: ByteArray): String? {
        if (data.size < 2) return null
        val charset = when (data[0].toInt() and 0xff) {
            1 -> Charsets.UTF_16
            2 -> Charset.forName("UTF-16BE")
            3 -> Charsets.UTF_8
            else -> Charsets.ISO_8859_1
        }
        return String(data, 1, data.size - 1, charset).trim('\u0000', ' ').takeIf(String::isNotBlank)
    }

    private fun readFlacComments(file: File): Map<String, String> {
        RandomAccessFile(file, "r").use { input ->
            if (input.length() < 8 || ByteArray(4).also(input::readFully).let { String(it, Charsets.US_ASCII) } != "fLaC") return emptyMap()
            var last = false
            while (!last && input.filePointer + 4 <= input.length()) {
                val typeByte = input.readUnsignedByte()
                last = typeByte and 0x80 != 0
                val type = typeByte and 0x7f
                val length = (input.readUnsignedByte() shl 16) or (input.readUnsignedByte() shl 8) or input.readUnsignedByte()
                if (length < 0 || input.filePointer + length > input.length()) break
                if (type != 4) { input.seek(input.filePointer + length); continue }
                val block = ByteArray(length).also(input::readFully)
                var offset = 0
                fun intLe(): Int {
                    if (offset + 4 > block.size) return 0
                    return (block[offset++].toInt() and 0xff) or ((block[offset++].toInt() and 0xff) shl 8) or
                        ((block[offset++].toInt() and 0xff) shl 16) or ((block[offset++].toInt() and 0xff) shl 24)
                }
                val vendor = intLe(); offset = (offset + vendor).coerceAtMost(block.size)
                val count = intLe().coerceIn(0, 10_000)
                val values = linkedMapOf<String, String>()
                repeat(count) {
                    val size = intLe()
                    if (size <= 0 || offset + size > block.size) return@repeat
                    val entry = String(block, offset, size, Charsets.UTF_8); offset += size
                    val key = entry.substringBefore('=').uppercase()
                    if (key in setOf("TITLE", "ARTIST", "ALBUM")) values.putIfAbsent(key, entry.substringAfter('=').trim())
                }
                return values
            }
        }
        return emptyMap()
    }

    fun parseLocalArtworkFile(url: String): File? {
        if (url.startsWith("file:", ignoreCase = true)) {
            val fromUri = runCatching { File(java.net.URI(url)) }.getOrNull()
            if (fromUri != null && fromUri.isFile) return fromUri

            val rawPath = url.substringAfter("file:").trimStart('/')
            val decoded = runCatching { java.net.URLDecoder.decode(rawPath, "UTF-8") }.getOrDefault(rawPath)
            val file = File(decoded)
            if (file.isFile) return file
            val rawFile = File(rawPath)
            if (rawFile.isFile) return rawFile
        }
        val direct = runCatching { File(url) }.getOrNull()
        if (direct != null && direct.isFile) return direct
        return null
    }

    fun resolveArtworkModel(track: Track): Any? {
        // 1. Offline cache for downloaded tracks
        val offlineFile = WindowsOfflineArtworkCache.file(track.id).takeIf { it.isFile && it.length() > 1_024L }
        if (offlineFile != null) return offlineFile

        // 2. Track's artworkUrl (could be file: URI or remote http URL)
        val url = track.artworkUrl
        if (!url.isNullOrBlank()) {
            val localFile = parseLocalArtworkFile(url)
            if (localFile != null) return localFile
            if (!url.startsWith("file:", ignoreCase = true)) {
                return url.replace("w120-h120", "w1200-h1200").replace("w60-h60", "w1200-h1200")
            }
        }

        // 3. Fallback for local files: if artworkUrl is missing or invalid, resolve on the fly from localPath
        track.localPath?.let { path ->
            val audioFile = File(path)
            if (audioFile.isFile) {
                val cachedUrl = cachedEmbeddedArtwork(audioFile)
                if (!cachedUrl.isNullOrBlank()) {
                    val file = parseLocalArtworkFile(cachedUrl)
                    if (file != null) return file
                }
            }
        }

        return null
    }

    private data class EmbeddedArtwork(val bytes: ByteArray, val extension: String)

    fun cachedEmbeddedArtwork(file: File): String? {
        val directory = File(WindowsStorage.directory, "local-artwork").apply { mkdirs() }
        val cacheName = sha256("${file.absolutePath.lowercase()}|${file.length()}|${file.lastModified()}")
        listOf("jpg", "png", "webp").asSequence()
            .map { extension -> File(directory, "$cacheName.$extension") }
            .firstOrNull { it.isFile && it.length() > 1_024L }
            ?.let { return it.toURI().toASCIIString() }

        val artwork = when (file.extension.lowercase()) {
            "mp3" -> readId3Artwork(file)
            "flac" -> readFlacArtwork(file)
            "m4a", "mp4", "aac", "alac" -> readM4aArtwork(file)
            else -> null
        }

        if (artwork != null && artwork.bytes.size > 1_024) {
            val destination = File(directory, "$cacheName.${artwork.extension}")
            val partial = File(directory, "$cacheName.${artwork.extension}.part")
            val saved = runCatching {
                partial.writeBytes(artwork.bytes)
                if (destination.exists()) destination.delete()
                if (!partial.renameTo(destination)) {
                    partial.copyTo(destination, overwrite = true)
                    partial.delete()
                }
                destination.toURI().toASCIIString()
            }.onFailure { partial.delete() }.getOrNull()
            if (saved != null) return saved
        }

        // Folder artwork fallback: cover.jpg, folder.jpg, front.jpg, [filename].jpg
        val parent = file.parentFile
        if (parent != null && parent.isDirectory) {
            val folderCover = listOf(
                "cover.jpg", "cover.png", "cover.jpeg",
                "folder.jpg", "folder.png", "folder.jpeg",
                "front.jpg", "front.png", "front.jpeg",
                "album.jpg", "album.png", "album.jpeg",
                "${file.nameWithoutExtension}.jpg",
                "${file.nameWithoutExtension}.png",
                "${file.nameWithoutExtension}.jpeg",
            ).map { File(parent, it) }.firstOrNull { it.isFile && it.length() > 1_024L }

            if (folderCover != null) {
                return folderCover.toURI().toASCIIString()
            }
        }

        return null
    }

    private fun readId3Artwork(file: File): EmbeddedArtwork? = RandomAccessFile(file, "r").use { input ->
        if (input.length() < 10) return@use null
        val header = ByteArray(10).also(input::readFully)
        if (String(header, 0, 3, Charsets.ISO_8859_1) != "ID3") return@use null
        val version = header[3].toInt() and 0xff
        val end = minOf(input.length(), 10L + synchsafe(header, 6))

        if (version == 2) {
            while (input.filePointer + 6 <= end) {
                val frameHeader = ByteArray(6).also(input::readFully)
                val frameId = String(frameHeader, 0, 3, Charsets.ISO_8859_1)
                if (frameId.all { it == '\u0000' }) break
                val frameSize = ((frameHeader[3].toInt() and 0xff) shl 16) or
                    ((frameHeader[4].toInt() and 0xff) shl 8) or
                    (frameHeader[5].toInt() and 0xff)
                if (frameSize <= 0 || input.filePointer + frameSize > end) break
                if (frameId == "PIC") {
                    val data = ByteArray(frameSize).also(input::readFully)
                    return@use imagePayload(data)
                }
                input.seek(input.filePointer + frameSize)
            }
        } else {
            while (input.filePointer + 10 <= end) {
                val frameHeader = ByteArray(10).also(input::readFully)
                val frameId = String(frameHeader, 0, 4, Charsets.ISO_8859_1)
                if (frameId.all { it == '\u0000' }) break
                val frameSize = if (version >= 4) synchsafe(frameHeader, 4) else
                    ((frameHeader[4].toInt() and 0xff) shl 24) or
                        ((frameHeader[5].toInt() and 0xff) shl 16) or
                        ((frameHeader[6].toInt() and 0xff) shl 8) or
                        (frameHeader[7].toInt() and 0xff)
                if (frameSize <= 0 || input.filePointer + frameSize > end) break
                if (frameId == "APIC") {
                    val data = ByteArray(frameSize).also(input::readFully)
                    return@use imagePayload(data)
                }
                input.seek(input.filePointer + frameSize)
            }
        }
        null
    }

    private fun readM4aArtwork(file: File): EmbeddedArtwork? = runCatching {
        RandomAccessFile(file, "r").use { input ->
            val length = input.length()
            if (length < 8) return@use null
            val limit = minOf(length, 25L * 1024 * 1024)
            val buf = ByteArray(64 * 1024)
            var pos = 0L
            while (pos < limit) {
                input.seek(pos)
                val read = input.read(buf)
                if (read <= 8) break
                for (i in 0 until read - 8) {
                    if (buf[i] == 'c'.code.toByte() && buf[i + 1] == 'o'.code.toByte() &&
                        buf[i + 2] == 'v'.code.toByte() && buf[i + 3] == 'r'.code.toByte()
                    ) {
                        val covrOffset = pos + i - 4
                        if (covrOffset >= 0 && covrOffset + 16 < length) {
                            input.seek(covrOffset)
                            val covrSize = input.readInt().toLong() and 0xFFFFFFFFL
                            val covrTag = ByteArray(4).also(input::readFully)
                            if (String(covrTag, Charsets.US_ASCII) == "covr" && covrSize in 16..(12 * 1024 * 1024)) {
                                val dataSize = input.readInt().toLong() and 0xFFFFFFFFL
                                val dataTag = ByteArray(4).also(input::readFully)
                                if (String(dataTag, Charsets.US_ASCII) == "data" && dataSize in 16..covrSize) {
                                    val flags = input.readInt()
                                    input.readInt()
                                    val payloadSize = (dataSize - 16).toInt()
                                    if (payloadSize > 0) {
                                        val payload = ByteArray(payloadSize).also(input::readFully)
                                        imagePayload(payload)?.let { return@use it }
                                        val ext = if (flags == 14) "png" else "jpg"
                                        return@use EmbeddedArtwork(payload, ext)
                                    }
                                }
                            }
                        }
                    }
                }
                pos += read - 8
            }
            null
        }
    }.getOrNull()

    private fun readFlacArtwork(file: File): EmbeddedArtwork? = RandomAccessFile(file, "r").use { input ->
        if (input.length() < 8 || ByteArray(4).also(input::readFully).let { String(it, Charsets.US_ASCII) } != "fLaC") return@use null
        var last = false
        while (!last && input.filePointer + 4 <= input.length()) {
            val typeByte = input.readUnsignedByte()
            last = typeByte and 0x80 != 0
            val type = typeByte and 0x7f
            val length = (input.readUnsignedByte() shl 16) or (input.readUnsignedByte() shl 8) or input.readUnsignedByte()
            if (length <= 0 || input.filePointer + length > input.length()) break
            if (type != 6) {
                input.seek(input.filePointer + length)
                continue
            }
            val block = ByteArray(length).also(input::readFully)
            var offset = 0
            fun intBe(): Int {
                if (offset + 4 > block.size) return -1
                return ((block[offset++].toInt() and 0xff) shl 24) or
                    ((block[offset++].toInt() and 0xff) shl 16) or
                    ((block[offset++].toInt() and 0xff) shl 8) or
                    (block[offset++].toInt() and 0xff)
            }
            intBe() // picture type
            val mimeLength = intBe()
            if (mimeLength !in 0..block.size || offset + mimeLength > block.size) return@use null
            offset += mimeLength
            val descriptionLength = intBe()
            if (descriptionLength !in 0..block.size || offset + descriptionLength > block.size) return@use null
            offset += descriptionLength
            repeat(4) { intBe() } // width, height, depth, palette size
            val dataLength = intBe()
            if (dataLength <= 0 || offset + dataLength > block.size) return@use null
            return@use imagePayload(block.copyOfRange(offset, offset + dataLength))
        }
        null
    }

    private fun imagePayload(data: ByteArray): EmbeddedArtwork? {
        fun find(signature: ByteArray): Int = data.indices.firstOrNull { start ->
            start + signature.size <= data.size && signature.indices.all { index -> data[start + index] == signature[index] }
        } ?: -1
        val png = find(byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a))
        if (png >= 0) return EmbeddedArtwork(data.copyOfRange(png, data.size), "png")
        val jpeg = find(byteArrayOf(0xff.toByte(), 0xd8.toByte(), 0xff.toByte()))
        if (jpeg >= 0) return EmbeddedArtwork(data.copyOfRange(jpeg, data.size), "jpg")
        val webp = find("RIFF".toByteArray(Charsets.US_ASCII))
        if (webp >= 0 && webp + 12 <= data.size && String(data, webp + 8, 4, Charsets.US_ASCII) == "WEBP") {
            return EmbeddedArtwork(data.copyOfRange(webp, data.size), "webp")
        }
        return null
    }

    private fun synchsafe(bytes: ByteArray, offset: Int): Int =
        ((bytes[offset].toInt() and 0x7f) shl 21) or ((bytes[offset + 1].toInt() and 0x7f) shl 14) or
            ((bytes[offset + 2].toInt() and 0x7f) shl 7) or (bytes[offset + 3].toInt() and 0x7f)

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray()).take(12).joinToString("") { "%02x".format(it) }
}
