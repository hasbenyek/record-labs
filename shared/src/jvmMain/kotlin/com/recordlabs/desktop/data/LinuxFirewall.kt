package com.recordlabs.desktop.data

import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Whether a Linux firewall may be blocking Record Labs Connect (UDP 45922 for
 * finding devices, TCP 45923 for the link between them).
 *
 * Ubuntu/Pop!_OS ship ufw switched off, Fedora's desktop zone already allows
 * these ports, but openSUSE (and any system where the user turned a firewall
 * on) blocks them, and Connect then silently finds nothing. The packages
 * install firewall definitions; opening them needs the user's password, so the
 * app only shows the command.
 */
object LinuxFirewall {
    data class Status(
        /** "firewalld" or "ufw". */
        val tool: String,
        /** True: Connect's ports are open. False: blocked. Null: can't tell without admin rights. */
        val connectAllowed: Boolean?,
        /** The command that opens the ports (asks for the password). */
        val fixCommand: String,
    )

    /** The active firewall and what it does with Connect's ports; null when none is active (or on Windows). */
    fun check(): Status? {
        if (Platform.isWindows) return null
        firewalld()?.let { return it }
        return ufw()
    }

    private fun firewalld(): Status? {
        val state = run("firewall-cmd", "--state") ?: return null
        if (state.trim() != "running") return null
        val fix = "sudo firewall-cmd --permanent --add-port=45922/udp --add-port=45923/tcp && sudo firewall-cmd --reload"
        val services = run("firewall-cmd", "--list-services")
        val ports = run("firewall-cmd", "--list-ports")
        if (services == null && ports == null) return Status("firewalld", null, fix)
        val allowed = services.orEmpty().split(Regex("\\s+")).contains("record-labs-connect") ||
            (portOpen(ports.orEmpty(), 45922, "udp") && portOpen(ports.orEmpty(), 45923, "tcp"))
        return Status("firewalld", allowed, fix)
    }

    private fun ufw(): Status? {
        // "ufw status" needs root; the config file says whether it is switched on.
        val config = runCatching { File("/etc/ufw/ufw.conf").readText() }.getOrNull() ?: return null
        val enabled = config.lineSequence().any { it.trim().replace(" ", "").equals("ENABLED=yes", ignoreCase = true) }
        if (!enabled) return null
        return Status("ufw", null, "sudo ufw allow 45922/udp && sudo ufw allow 45923/tcp")
    }

    /** "45922/udp 1025-65535/tcp ..." covers [port] for [protocol]? */
    internal fun portOpen(list: String, port: Int, protocol: String): Boolean =
        list.split(Regex("\\s+")).filter(String::isNotBlank).any { entry ->
            val (range, proto) = entry.split('/').let { it[0] to it.getOrElse(1) { "" } }
            if (!proto.equals(protocol, ignoreCase = true)) return@any false
            val bounds = range.split('-').mapNotNull(String::toIntOrNull)
            when (bounds.size) {
                1 -> bounds[0] == port
                2 -> port in bounds[0]..bounds[1]
                else -> false
            }
        }

    private fun run(vararg command: String): String? = runCatching {
        val process = ProcessBuilder(*command).redirectErrorStream(true).start()
        if (!process.waitFor(4, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            return null
        }
        if (process.exitValue() != 0) return null
        process.inputStream.bufferedReader().readText()
    }.getOrNull()
}
