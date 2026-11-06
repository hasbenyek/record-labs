package com.recordlabs.desktop.data

import java.io.IOException

internal actual fun openWindowsDiscordPipe(path: String): DiscordIpcTransport? =
    runCatching { WindowsPipeTransport(path) }.getOrNull()

private class WindowsPipeTransport(path: String) : DiscordIpcTransport {
    private val kernel = com.sun.jna.platform.win32.Kernel32.INSTANCE
    private val handle: com.sun.jna.platform.win32.WinNT.HANDLE = kernel.CreateFile(
        path,
        com.sun.jna.platform.win32.WinNT.GENERIC_READ or com.sun.jna.platform.win32.WinNT.GENERIC_WRITE,
        0,
        null,
        com.sun.jna.platform.win32.WinNT.OPEN_EXISTING,
        0,
        null,
    )
    @Volatile private var closed = false

    init {
        if (handle == com.sun.jna.platform.win32.WinBase.INVALID_HANDLE_VALUE) throw IOException("No Discord pipe at $path")
    }

    override fun write(bytes: ByteArray) {
        var offset = 0
        while (offset < bytes.size) {
            val chunk = if (offset == 0) bytes else bytes.copyOfRange(offset, bytes.size)
            val written = com.sun.jna.ptr.IntByReference()
            if (!kernel.WriteFile(handle, chunk, chunk.size, written, null) || written.value <= 0) throw IOException("Discord pipe write failed")
            offset += written.value
        }
    }

    override fun available(): Int {
        if (closed) throw IOException("closed")
        val total = com.sun.jna.ptr.IntByReference()
        if (!kernel.PeekNamedPipe(handle, null, 0, null, total, null)) throw IOException("Discord pipe closed")
        return total.value
    }

    override fun read(count: Int): ByteArray {
        val result = ByteArray(count)
        var offset = 0
        while (offset < count) {
            val buffer = ByteArray(count - offset)
            val read = com.sun.jna.ptr.IntByReference()
            if (!kernel.ReadFile(handle, buffer, buffer.size, read, null) || read.value <= 0) throw IOException("Discord pipe read failed")
            System.arraycopy(buffer, 0, result, offset, read.value)
            offset += read.value
        }
        return result
    }

    override fun close() {
        if (closed) return
        closed = true
        runCatching { kernel.CloseHandle(handle) }
    }
}

