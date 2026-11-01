package dev.ferry.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TicketsTest {
    @Test
    fun extractsTickets() {
        val t = "ferry://pair?v=1&id=abc&s=00&n=Mac&a=192.168.1.2:4000"
        assertEquals(t, Tickets.extract(t))
        assertEquals(t, Tickets.extract("  $t\n"))
        assertNull(Tickets.extract(null))
        assertNull(Tickets.extract("ferry://pair?"))
        assertNull(Tickets.extract("https://example.com/?ferry://pair?v=1"))
        assertNull(Tickets.extract("ferry://other?v=1"))
    }

    @Test
    fun friendlyErrors() {
        assertEquals("Pairing was declined on your Mac.", Errors.friendly("reason=pairing declined on the other device"))
        assertTrue(Errors.friendly("reason=connect to the other device: timed out").startsWith("Couldn't reach your Mac"))
        assertTrue(Errors.friendly("pairing attempt with an invalid, expired or used ticket").startsWith("That QR code has expired"))
        assertEquals("Disk exploded", Errors.friendly("reason=disk exploded"))
        assertEquals("Something went wrong. Try again.", Errors.friendly("reason="))
    }

    @Test
    fun wording() {
        assertEquals("1 file", count(1, "file"))
        assertEquals("3 files", count(3, "file"))
        assertEquals("", joinAnd(emptyList()))
        assertEquals("Wi-Fi", joinAnd(listOf("Wi-Fi")))
        assertEquals("Wi-Fi and USB", joinAnd(listOf("Wi-Fi", "USB")))
        assertEquals("Wi-Fi, USB and Bluetooth", joinAnd(listOf("Wi-Fi", "USB", "Bluetooth")))
    }

    @Test
    fun formatsHostPort() {
        assertEquals("192.168.1.2:4000", Tickets.hostPort("192.168.1.2", 0, 4000))
        assertEquals("[2001:db8::1]:4000", Tickets.hostPort("2001:db8::1", 0, 4000))
        assertEquals("[fe80::1%3]:4000", Tickets.hostPort("fe80::1%wlan0", 3, 4000))
    }
}
