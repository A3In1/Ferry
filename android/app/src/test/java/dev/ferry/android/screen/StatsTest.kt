package dev.ferry.android.screen

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StatsTest {
    @Test
    fun ackPopsEverythingUpToTheAckedFrame() {
        val f = InFlight()
        f.sent(100, 1000, 0)
        f.sent(200, 2000, 16)
        f.sent(300, 3000, 32)
        assertFalse(f.acking)
        assertEquals(6000, f.bytes)
        assertEquals(40, f.oldestAge(40))
        assertEquals(34L, f.acked(200, 50)) // 100 and 200 arrived
        assertTrue(f.acking)
        assertEquals(1, f.count)
        assertEquals(3000, f.bytes)
        assertNull(f.acked(200, 60)) // duplicate
        assertNull(f.acked(999, 60)) // unknown: nothing popped
        assertEquals(1, f.count)
        assertEquals(28L, f.acked(300, 60))
        assertEquals(0, f.oldestAge(70))
        assertEquals(0, f.bytes)
    }

    @Test
    fun ringDropsOldestWhenTheMacNeverAcks() {
        val f = InFlight(cap = 4)
        for (i in 0 until 10) f.sent(i.toLong(), 10, i.toLong())
        assertEquals(4, f.count)
        assertEquals(40, f.bytes)
        assertEquals(4, f.oldestAge(10)) // frame 6 is the oldest left
    }

    @Test
    fun windowReport() {
        val s = SendStats(0)
        repeat(120) {
            s.encoded(5_000)
            s.sent(10_000, 1)
        }
        s.encoded(5_000_000) // a clock that doesn't match: counted, not averaged
        s.dropped()
        s.rtt(10)
        s.rtt(30)
        assertNull(s.report(1_999, 8_000_000))
        val r = s.report(2_000, 8_000_000)!!
        assertEquals(121, r.encoded)
        assertEquals(120, r.sent)
        assertEquals(1, r.dropped)
        assertEquals(4_800_000, r.outBps) // 1.2 MB in 2 s
        assertEquals(50, r.encodeTenthMs)
        assertEquals(10, r.writeTenthMs)
        assertEquals(200, r.rttTenthMs)
        assertEquals(100, r.rttMinTenthMs)
        val empty = s.report(4_000, 8_000_000)!!
        assertEquals(0, empty.sent)
        assertEquals(Msg.Stats.UNKNOWN, empty.rttTenthMs)
        assertTrue(empty.line(0).startsWith("stats: encoded 0 fps"))
    }
}
