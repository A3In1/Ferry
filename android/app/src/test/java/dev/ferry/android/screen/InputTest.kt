package dev.ferry.android.screen

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ScrollDragTest {
    private fun drag() = ScrollDrag(1000f, 2000f, 10f)

    @Test
    fun trackpadScrollIsOneContinuedStroke() {
        val d = drag()
        d.add(500f, 1000f, 0f, 30f, continuous = true, end = false, now = 0)
        d.add(500f, 1000f, 0f, 20f, continuous = true, end = false, now = 5)
        val a = d.step(16)!!
        assertEquals(ScrollDrag.Step(500f, 1000f, 500f, 1050f, ScrollDrag.FRAME_MS, first = true, more = true), a)
        assertNull(d.step(32)) // nothing new: the finger rests
        d.add(900f, 100f, 4f, -10f, continuous = true, end = false, now = 40) // the pointer only matters for a new stroke
        val b = d.step(48)!!
        assertEquals(ScrollDrag.Step(500f, 1050f, 504f, 1040f, 32, first = false, more = true), b)
    }

    @Test
    fun endWaitsForMomentumThenLiftsStill() {
        val d = drag()
        d.add(500f, 1000f, 0f, 30f, continuous = true, end = false, now = 0)
        d.step(16)
        d.add(500f, 1000f, 0f, 0f, continuous = true, end = true, now = 20) // finger lifted
        assertNull(d.step(32)) // grace: momentum may follow
        d.add(500f, 1000f, 0f, 10f, continuous = true, end = false, now = 40) // it did
        assertTrue(d.step(48)!!.more)
        d.add(500f, 1000f, 0f, 1f, continuous = true, end = true, now = 100) // momentum over
        assertTrue(d.step(112)!!.more)
        assertNull(d.step(128))
        val lift = d.step(100 + ScrollDrag.GRACE_MS)!!
        assertEquals(ScrollDrag.Step(500f, 1041f, 500f, 1041f, ScrollDrag.HOLD_MS, first = false, more = false), lift)
        assertFalse(d.busy)
    }

    @Test
    fun wheelLiftsAfterAPause() {
        val d = drag()
        d.add(500f, 1000f, 0f, -40f, continuous = false, end = false, now = 0)
        assertEquals(960f, d.step(16)!!.toY)
        assertNull(d.step(100))
        assertFalse(d.step(ScrollDrag.WHEEL_IDLE_MS)!!.more)
    }

    @Test
    fun regripsAtTheEdgeAndCarriesTheRest() {
        val d = drag()
        d.add(500f, 1900f, 0f, 150f, continuous = true, end = false, now = 0)
        assertEquals(1990f, d.step(16)!!.toY) // 60 px left over past the margin
        val lift = d.step(32)!!
        assertFalse(lift.more)
        assertNull(d.step(40)) // the hold is still playing
        val again = d.step(32 + ScrollDrag.HOLD_MS + ScrollDrag.FRAME_MS)!!
        assertEquals(ScrollDrag.Step(500f, 1900f, 500f, 1960f, ScrollDrag.FRAME_MS, first = true, more = true), again)
    }

    @Test
    fun nothingToDoAtAnEdgeIsDropped() {
        val d = drag()
        d.add(1000f, 1000f, 50f, 0f, continuous = false, end = false, now = 0) // at the right edge, pushing right
        assertNull(d.step(16))
        assertFalse(d.busy)
    }

    @Test
    fun cancelReportsTheFinger() {
        val d = drag()
        assertNull(d.cancel())
        d.add(500f, 1000f, 10f, 0f, continuous = true, end = false, now = 0)
        d.step(16)
        assertEquals(510f to 1000f, d.cancel())
        assertFalse(d.busy)
    }
}
