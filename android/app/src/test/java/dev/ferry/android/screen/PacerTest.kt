package dev.ferry.android.screen

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PacerTest {
    /** A Pacer fed 60 fps; one [windows] call = whole 1 s windows (63 frames 16 ms apart close exactly one). */
    private class Sim(ceiling: Int) {
        val p = Pacer(ceiling, 0)
        var t = 0L

        /** Every frame acked after [rttMs] (null = a Mac that doesn't ACK), each write taking [writeMs]. */
        fun windows(n: Int, rttMs: Long?, writeMs: Long = 1) {
            repeat(n * 63) {
                t += 16
                rttMs?.let(p::rtt)
                p.sent(writeMs, t)
            }
        }
    }

    @Test
    fun queueingDelayCutsWithHysteresisAndRecoversSlowly() {
        val s = Sim(8_000_000)
        s.windows(3, 10) // best rtt 10 ms
        assertEquals(8_000_000, s.p.bitrate)
        s.windows(1, 110) // one hot window: not yet
        assertEquals(8_000_000, s.p.bitrate)
        s.windows(1, 10) // calm again: the hot streak resets
        s.windows(1, 110)
        assertEquals(8_000_000, s.p.bitrate)
        s.windows(1, 110) // second hot window in a row
        assertEquals(6_000_000, s.p.bitrate)
        s.windows(1, 300) // very hot: cut at once
        assertEquals(4_500_000, s.p.bitrate)
        s.windows(4, 10) // calm, but within HOLD_MS of the cut
        assertEquals(4_500_000, s.p.bitrate)
        s.windows(1, 10)
        assertTrue(s.p.bitrate > 4_500_000)
        s.windows(120, 10)
        assertEquals(8_000_000, s.p.bitrate) // never above what the Mac asked for
    }

    @Test
    fun aSlowButSteadyLinkIsNotCongestion() {
        val s = Sim(8_000_000)
        s.windows(10, 70) // 70 ms every time (far away or relayed): no queueing above best
        assertEquals(8_000_000, s.p.bitrate)
    }

    @Test
    fun behindDropsUntilDrainedThenResumesFromAKeyframe() {
        val p = Pacer(8_000_000, 0)
        assertTrue(p.admit(key = false, config = false, backlogMs = 20, queued = 0, now = 100).send)
        val behind = p.admit(key = false, config = false, backlogMs = Pacer.LAG_MS, queued = 0, now = 116)
        assertFalse(behind.send)
        assertEquals(4_800_000, behind.bitrate)
        assertTrue(p.skipping)
        // Still draining: drop, and no keyframe yet (it would only queue behind the stale frames).
        val draining = p.admit(key = false, config = false, backlogMs = 200, queued = 0, now = 132)
        assertFalse(draining.send || draining.askKeyframe)
        assertFalse(p.admit(key = true, config = false, backlogMs = 200, queued = 0, now = 140).send)
        assertTrue(p.admit(key = false, config = true, backlogMs = 200, queued = 0, now = 141).send) // SPS/PPS always go
        val drained = p.admit(key = false, config = false, backlogMs = 10, queued = 0, now = 148)
        assertTrue(drained.askKeyframe && !drained.send)
        assertFalse(p.admit(key = false, config = false, backlogMs = 0, queued = 0, now = 164).askKeyframe) // asked once
        assertTrue(p.admit(key = false, config = false, backlogMs = 0, queued = 0, now = 148 + Pacer.ASK_EVERY_MS).askKeyframe)
        assertTrue(p.admit(key = true, config = false, backlogMs = 0, queued = 0, now = 1_200).send)
        assertFalse(p.skipping)
        // Behind again soon after: drop, but the bitrate isn't cut twice in one window.
        assertNull(p.admit(key = false, config = false, backlogMs = 500, queued = 0, now = 1_100).bitrate)
    }

    @Test
    fun localPileUpIsBehindToo() {
        val p = Pacer(8_000_000, 0)
        assertFalse(p.admit(key = false, config = false, backlogMs = null, queued = Pacer.QUEUE_MAX, now = 0).send)
        // A Mac that doesn't ACK: "drained" is unknown, so the keyframe is asked for at once.
        assertTrue(p.admit(key = false, config = false, backlogMs = null, queued = 0, now = 16).askKeyframe)
    }

    @Test
    fun withoutAcksWriteTimingDrives() {
        val s = Sim(8_000_000)
        s.windows(1, null, writeMs = 12) // one blocked window (~75%): hysteresis holds
        assertEquals(8_000_000, s.p.bitrate)
        s.windows(1, null, writeMs = 12)
        assertEquals(6_000_000, s.p.bitrate)
        assertNull(s.p.sent(Pacer.STALL_MS, s.t + 100)) // a stall right after a cut: behind, no second cut
        assertTrue(s.p.skipping)
        assertEquals(3_600_000, s.p.sent(Pacer.STALL_MS, s.t + 1_100))
    }

    @Test
    fun floorHolds() {
        val s = Sim(2_000_000)
        s.windows(1, 10)
        s.windows(20, 500)
        assertEquals(Pacer.MIN_BPS, s.p.bitrate)
        val tiny = Sim(300_000)
        tiny.windows(1, 10)
        tiny.windows(10, 500)
        assertEquals(300_000, tiny.p.bitrate)
    }

    @Test
    fun encodeSizesAreAlignedAndStepDown() {
        assertEquals(listOf(864 to 1920, 720 to 1600, 576 to 1280, 448 to 1024, 320 to 720), encodeSizes(1080, 2400, 1920))
        assertEquals(1072 to 2400, encodeSizes(1080, 2400, 0).first()) // native, width aligned down to 16
        assertEquals(listOf(1280 to 720, 1024 to 576, 720 to 400), encodeSizes(1280, 720, 4000))
    }
}
