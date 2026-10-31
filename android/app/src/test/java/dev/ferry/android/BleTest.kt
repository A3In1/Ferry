package dev.ferry.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

class BleTest {
    @Test
    fun prefixAndMask() {
        assertEquals("f377e501-0000-0000-0000-000000000000", blePrefixUuid("f377e501").toString())
        assertEquals("ffffffff-0000-0000-0000-000000000000", BLE_MASK.toString())
        // A rotating presence UUID matches the filter under the mask; the fixed GATT service UUID shares the prefix.
        val presence = UUID.fromString("f377e501-1a2b-3c4d-5e6f-708192a3b4c5")
        assertEquals(blePrefixUuid("f377e501").mostSignificantBits, presence.mostSignificantBits and BLE_MASK.mostSignificantBits)
        assertEquals(0L, presence.leastSignificantBits and BLE_MASK.leastSignificantBits)
        assertTrue(BLE_SERVICE.toString().startsWith("f377e501"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun prefixMustBeFourBytes() {
        blePrefixUuid("f377e5")
    }

    @Test
    fun psmLittleEndian() {
        assertEquals(0x0080, parsePsm(byteArrayOf(0x80.toByte(), 0x00)))
        assertEquals(0x00c1, parsePsm(byteArrayOf(0xc1.toByte(), 0x00)))
        assertEquals(0x1234, parsePsm(byteArrayOf(0x34, 0x12)))
        assertEquals(0xffff, parsePsm(byteArrayOf(0xff.toByte(), 0xff.toByte())))
        assertNull(parsePsm(null))
        assertNull(parsePsm(byteArrayOf()))
        assertNull(parsePsm(byteArrayOf(0x80.toByte())))
        assertNull(parsePsm(byteArrayOf(1, 2, 3)))
        assertNull(parsePsm(byteArrayOf(0, 0)))
    }

    @Test
    fun cooldown() {
        val c = Cooldown()
        assertTrue(c.take("mac", 1_000, 60_000))
        assertFalse(c.take("mac", 30_000, 60_000)) // within the window
        assertTrue(c.take("other", 30_000, 60_000)) // per key
        assertTrue(c.take("mac", 61_000, 60_000)) // window over
        c.hold("mac", 61_000, 300_000) // check-in worked: longer quiet time
        assertFalse(c.take("mac", 200_000, 60_000))
        assertTrue(c.take("mac", 361_000, 60_000))
    }
}
