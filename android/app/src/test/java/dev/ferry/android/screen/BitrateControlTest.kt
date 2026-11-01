package dev.ferry.android.screen

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BitrateControlTest {
    /** 60 fps for [ms] with each write blocking [writeMs]; the last change. */
    private fun BitrateControl.run(from: Long, ms: Long, writeMs: Long): BitrateControl.Change? {
        var last: BitrateControl.Change? = null
        var t = from
        while (t < from + ms) {
            t += 16
            sent(writeMs, t).takeIf { it.bitrate != null || it.catchUp }?.let { last = it }
        }
        return last
    }

    @Test
    fun dropsWhenWritesBlockAndRecoversSlowly() {
        val q = BitrateControl(8_000_000, 0)
        assertNull(q.run(0, 3_000, 1)) // a clear link stays at the ceiling
        assertEquals(8_000_000, q.bitrate)
        q.run(3_000, 1_100, 12) // blocked ~75% of the time
        assertEquals(5_600_000, q.bitrate)
        q.run(4_100, 2_100, 12)
        assertEquals(2_744_000, q.bitrate)
        q.run(6_200, 2_100, 1) // two calm windows: not yet
        assertEquals(2_744_000, q.bitrate)
        q.run(8_300, 3_100, 1)
        assertTrue(q.bitrate > 2_744_000)
        q.run(11_400, 60_000, 1)
        assertEquals(8_000_000, q.bitrate) // never above what the Mac asked for
    }

    @Test
    fun aStallCatchesUpOnceInAWhile() {
        val q = BitrateControl(8_000_000, 0)
        val c = q.sent(BitrateControl.STALL_MS, 100)
        assertEquals(BitrateControl.Change(4_000_000, catchUp = true), c)
        assertFalse(q.sent(BitrateControl.STALL_MS, 200).catchUp) // throttled
        assertTrue(q.sent(BitrateControl.STALL_MS, 100 + BitrateControl.CATCH_UP_EVERY_MS).catchUp)
    }

    @Test
    fun floorHolds() {
        val q = BitrateControl(2_000_000, 0)
        q.run(0, 20_000, 15)
        assertEquals(BitrateControl.MIN_BPS, q.bitrate)
        val tiny = BitrateControl(300_000, 0)
        tiny.run(0, 5_000, 15)
        assertEquals(300_000, tiny.bitrate)
    }
}
