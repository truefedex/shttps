package com.phlox.simpleserver.screens.home

import java.net.InetAddress
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Pins which addresses keep an interface in the "Show address for" picker. The interesting cases
 * are all macOS ones: awdl0/llw0 and the system utunN tunnels are up on every Mac with nothing but
 * an fe80:: address, while a VPN's utunN carries a routable one and must survive the same filter.
 */
class DialableAddressTest {
    @Test
    fun `link-local addresses are not dialable`() {
        //awdl0 / llw0 / a system utun on macOS
        assertFalse(isDialableAddress(InetAddress.getByName("fe80::f400:41ff:fef1:3294")))
        //an IPv4 NIC that never got a lease
        assertFalse(isDialableAddress(InetAddress.getByName("169.254.13.7")))
    }

    @Test
    fun `routable and loopback addresses are dialable`() {
        assertTrue(isDialableAddress(InetAddress.getByName("192.168.1.102")))
        //loopback stays offered: serving only to this machine is a valid choice
        assertTrue(isDialableAddress(InetAddress.getByName("127.0.0.1")))
        assertTrue(isDialableAddress(InetAddress.getByName("::1")))
        //a VPN tunnel: Tailscale's CGNAT range and a global IPv6 address
        assertTrue(isDialableAddress(InetAddress.getByName("100.101.102.103")))
        assertTrue(isDialableAddress(InetAddress.getByName("2001:db8::1")))
        //a unique local IPv6 address, which Java does not classify but which routes on the LAN
        assertTrue(isDialableAddress(InetAddress.getByName("fd00::1")))
    }
}

/**
 * Pins which of an interface's addresses ends up on the status screen. The case that matters is an
 * interface like macOS's en0, which carries a working IPv4 address and an fe80:: one: asking it for
 * IPv6 used to produce `http://[fe80::1c18:...]:8080/` - the scope id stripped off to make it fit
 * in a URL, so unusable by anything that received it.
 */
class SelectDisplayAddressTest {
    private fun addr(vararg host: String) = host.map { InetAddress.getByName(it) }

    @Test
    fun `picks the address of the requested version`() {
        val en0 = addr("fe80::1c18:7934:a351:8e95", "192.168.1.102", "2001:db8::5")
        assertEquals(InetAddress.getByName("192.168.1.102"), selectDisplayAddress(en0, 4))
        assertEquals(InetAddress.getByName("2001:db8::5"), selectDisplayAddress(en0, 6))
    }

    @Test
    fun `an interface with only a link-local address of that version has none to show`() {
        //en0 on a machine whose network is IPv4-only, which is the common case
        val en0 = addr("fe80::1c18:7934:a351:8e95", "192.168.1.102")
        assertEquals(InetAddress.getByName("192.168.1.102"), selectDisplayAddress(en0, 4))
        assertNull(selectDisplayAddress(en0, 6))
    }

    @Test
    fun `loopback is shown for both versions`() {
        val lo0 = addr("fe80::1", "::1", "127.0.0.1")
        assertEquals(InetAddress.getByName("127.0.0.1"), selectDisplayAddress(lo0, 4))
        assertEquals(InetAddress.getByName("::1"), selectDisplayAddress(lo0, 6))
    }

    @Test
    fun `no addresses at all`() {
        assertNull(selectDisplayAddress(emptyList(), 4))
        assertNull(selectDisplayAddress(emptyList(), 6))
    }
}
