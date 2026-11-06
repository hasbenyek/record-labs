@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.recordlabs.desktop.data

import kotlinx.cinterop.CPointer
import kotlinx.cinterop.CPointerVar
import kotlinx.cinterop.IntVar
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.pointed
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.sizeOf
import kotlinx.cinterop.toKString
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value
import platform.posix.AF_INET
import platform.posix.EINPROGRESS
import platform.posix.EINTR
import platform.posix.F_GETFL
import platform.posix.F_SETFL
import platform.posix.O_NONBLOCK
import platform.posix.POLLIN
import platform.posix.POLLOUT
import platform.posix.SHUT_RDWR
import platform.posix.SOCK_STREAM
import platform.posix.SOL_SOCKET
import platform.posix.SO_ERROR
import platform.posix.SO_NOSIGPIPE
import platform.posix.SO_REUSEADDR
import platform.posix.errno
import platform.posix.fcntl
import platform.darwin.freeifaddrs
import platform.darwin.getifaddrs
import platform.posix.getsockopt
import platform.darwin.ifaddrs
import platform.posix.poll
import platform.posix.pollfd
import platform.posix.recv
import platform.posix.send
import platform.posix.setsockopt
import platform.posix.shutdown
import platform.posix.sockaddr_in
import platform.posix.socket
import platform.posix.socklen_tVar

/**
 * Plain TCP for Record Labs Connect on the iPhone (BSD sockets): the line-based link
 * the desktop and Android open between devices on the same Wi-Fi (port 45923).
 *
 * An iPhone app may not send or receive the multicast and broadcast datagrams the
 * other devices use to find each other (that needs an entitlement Apple gives on
 * request only), so the iPhone finds them the way they find each other when those
 * datagrams are blocked: by trying the Wi-Fi's addresses on the TCP port, and by
 * listening on it.
 */
internal object IosTcp {
    // net/if.h
    private const val IFF_UP = 0x1
    private const val IFF_LOOPBACK = 0x8
    private const val IFF_RUNNING = 0x40

    /** A local IPv4 network: this device's address on it, as "a.b.c.d". */
    data class LocalAddress(val interfaceName: String, val address: String)

    /**
     * The Wi-Fi (and Ethernet) addresses of this device: not mobile data (pdp_ip),
     * VPN tunnels (utun, ipsec), AirDrop (awdl, llw) or the loopback.
     */
    fun localIpv4(): List<LocalAddress> = runCatching {
        val found = mutableListOf<LocalAddress>()
        memScoped {
            val list = alloc<CPointerVar<ifaddrs>>()
            if (getifaddrs(list.ptr) != 0) return@memScoped
            var entry: CPointer<ifaddrs>? = list.value
            while (entry != null) {
                val item = entry.pointed
                val name = item.ifa_name?.toKString().orEmpty()
                val flags = item.ifa_flags.toInt()
                val address = item.ifa_addr
                val lan = name.startsWith("en") || name.startsWith("bridge")
                if (address != null && lan &&
                    flags and IFF_UP != 0 && flags and IFF_RUNNING != 0 && flags and IFF_LOOPBACK == 0 &&
                    address.pointed.sa_family.toInt() == AF_INET
                ) {
                    val sin = address.reinterpret<sockaddr_in>().pointed
                    val text = ipv4Text(sin.sin_addr.s_addr)
                    if (!text.startsWith("169.254.") && !text.startsWith("127.")) found += LocalAddress(name, text)
                }
                entry = item.ifa_next
            }
            freeifaddrs(list.value)
        }
        found.distinctBy { it.address }
    }.getOrDefault(emptyList())

    /** "a.b.c.d" of an address in network order (in_addr.s_addr, as stored on a little-endian iPhone). */
    private fun ipv4Text(networkOrder: UInt): String {
        val v = networkOrder
        return "${v and 0xffu}.${(v shr 8) and 0xffu}.${(v shr 16) and 0xffu}.${(v shr 24) and 0xffu}"
    }

    private fun ipv4Value(text: String): UInt? {
        val parts = text.split('.').map { it.toIntOrNull() ?: return null }
        if (parts.size != 4 || parts.any { it !in 0..255 }) return null
        return parts[0].toUInt() or (parts[1].toUInt() shl 8) or (parts[2].toUInt() shl 16) or (parts[3].toUInt() shl 24)
    }

    private fun portValue(port: Int): UShort = (((port and 0xff) shl 8) or ((port shr 8) and 0xff)).toUShort()

    private fun noSigPipe(fd: Int) = memScoped {
        val one = alloc<IntVar>().apply { value = 1 }
        setsockopt(fd, SOL_SOCKET, SO_NOSIGPIPE, one.ptr, sizeOf<IntVar>().convert())
    }

    /** Connects to [host]:[port] within [timeoutMs]; null when nothing answers. */
    fun connect(host: String, port: Int, timeoutMs: Int): Link? {
        val target = ipv4Value(host) ?: return null
        val fd = socket(AF_INET, SOCK_STREAM, 0)
        if (fd < 0) return null
        var ok = false
        try {
            noSigPipe(fd)
            val flags = fcntl(fd, F_GETFL, 0)
            fcntl(fd, F_SETFL, flags or O_NONBLOCK)
            val started = memScoped {
                val addr = alloc<sockaddr_in>()
                addr.sin_len = sizeOf<sockaddr_in>().convert()
                addr.sin_family = AF_INET.convert()
                addr.sin_port = portValue(port)
                addr.sin_addr.s_addr = target
                platform.posix.connect(fd, addr.ptr.reinterpret(), sizeOf<sockaddr_in>().convert())
            }
            if (started != 0 && errno != EINPROGRESS) return null
            if (started != 0) {
                val ready = memScoped {
                    val pfd = alloc<pollfd>()
                    pfd.fd = fd
                    pfd.events = POLLOUT.convert()
                    poll(pfd.ptr, 1u, timeoutMs)
                }
                if (ready <= 0) return null
                val error = memScoped {
                    val value = alloc<IntVar>()
                    val length = alloc<socklen_tVar>().apply { this.value = sizeOf<IntVar>().convert() }
                    getsockopt(fd, SOL_SOCKET, SO_ERROR, value.ptr, length.ptr)
                    value.value
                }
                if (error != 0) return null
            }
            // Blocking again: the link reads on its own thread.
            fcntl(fd, F_SETFL, flags and O_NONBLOCK.inv())
            ok = true
            return Link(fd, host)
        } finally {
            if (!ok) platform.posix.close(fd)
        }
    }

    /** Listens on [port] (all addresses); null when the port cannot be used. */
    fun listen(port: Int): Server? {
        val fd = socket(AF_INET, SOCK_STREAM, 0)
        if (fd < 0) return null
        memScoped {
            val one = alloc<IntVar>().apply { value = 1 }
            setsockopt(fd, SOL_SOCKET, SO_REUSEADDR, one.ptr, sizeOf<IntVar>().convert())
        }
        val bound = memScoped {
            val addr = alloc<sockaddr_in>()
            addr.sin_len = sizeOf<sockaddr_in>().convert()
            addr.sin_family = AF_INET.convert()
            addr.sin_port = portValue(port)
            addr.sin_addr.s_addr = 0u
            platform.posix.bind(fd, addr.ptr.reinterpret(), sizeOf<sockaddr_in>().convert())
        }
        if (bound != 0 || platform.posix.listen(fd, 16) != 0) {
            platform.posix.close(fd)
            return null
        }
        return Server(fd)
    }

    class Server internal constructor(private val fd: Int) {
        @kotlin.concurrent.Volatile var isClosed = false
            private set

        /** Waits up to [timeoutMs] for a device to connect; null when none did (or closed). */
        fun accept(timeoutMs: Int): Link? {
            if (isClosed) return null
            val ready = memScoped {
                val pfd = alloc<pollfd>()
                pfd.fd = fd
                pfd.events = POLLIN.convert()
                poll(pfd.ptr, 1u, timeoutMs)
            }
            if (ready <= 0 || isClosed) return null
            return memScoped {
                val addr = alloc<sockaddr_in>()
                val length = alloc<socklen_tVar>().apply { value = sizeOf<sockaddr_in>().convert() }
                val client = platform.posix.accept(fd, addr.ptr.reinterpret(), length.ptr)
                if (client < 0) null
                else {
                    noSigPipe(client)
                    Link(client, ipv4Text(addr.sin_addr.s_addr))
                }
            }
        }

        fun close() {
            if (isClosed) return
            isClosed = true
            platform.posix.close(fd)
        }
    }

    /** One connection: lines of UTF-8 text each way (the Connect messages). */
    class Link internal constructor(private val fd: Int, val remoteHost: String) {
        @kotlin.concurrent.Volatile var isClosed = false
            private set
        private val buffer = ByteArray(16 * 1024)
        private var pending = ByteArray(0)

        /** The next line (without the line break); null when the connection ended. */
        fun readLine(): String? {
            while (true) {
                val newline = pending.indexOf('\n'.code.toByte())
                if (newline >= 0) {
                    val line = pending.copyOfRange(0, newline)
                    pending = pending.copyOfRange(newline + 1, pending.size)
                    val end = if (line.isNotEmpty() && line.last() == '\r'.code.toByte()) line.size - 1 else line.size
                    return line.decodeToString(0, end)
                }
                // A single message is never this large; a peer sending endless data is dropped.
                if (pending.size > 4 * 1024 * 1024) return null
                if (isClosed) return null
                val read = buffer.usePinned { recv(fd, it.addressOf(0), buffer.size.convert(), 0) }
                if (read < 0 && errno == EINTR) continue
                if (read <= 0) return null
                pending += buffer.copyOfRange(0, read.toInt())
            }
        }

        /** Writes [line] and a line break; false when the connection is gone. */
        fun writeLine(line: String): Boolean {
            if (isClosed) return false
            val bytes = (line + "\n").encodeToByteArray()
            var at = 0
            while (at < bytes.size) {
                val sent = bytes.usePinned { send(fd, it.addressOf(at), (bytes.size - at).convert(), 0) }
                if (sent < 0 && errno == EINTR) continue
                if (sent <= 0) return false
                at += sent.toInt()
            }
            return true
        }

        fun close() {
            if (isClosed) return
            isClosed = true
            // Wakes a reader waiting in recv (close alone does not on Darwin).
            shutdown(fd, SHUT_RDWR)
            platform.posix.close(fd)
        }
    }
}
