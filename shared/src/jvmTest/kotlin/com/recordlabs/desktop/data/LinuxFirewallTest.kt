package com.recordlabs.desktop.data

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LinuxFirewallTest {
    @Test
    fun `firewalld port lists and ranges cover Connect's ports`() {
        assertTrue(LinuxFirewall.portOpen("45922/udp 45923/tcp", 45922, "udp"))
        assertTrue(LinuxFirewall.portOpen("1025-65535/udp 1025-65535/tcp", 45923, "tcp"))
        assertFalse(LinuxFirewall.portOpen("45922/tcp", 45922, "udp"))
        assertFalse(LinuxFirewall.portOpen("", 45922, "udp"))
        assertFalse(LinuxFirewall.portOpen("8000-9000/udp", 45922, "udp"))
    }
}
