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

class FieldTest {
    private fun f(s: String): Field { // "|" marks the caret; "[...]" a selection with the caret at "]"
        val a = s.indexOf('[')
        if (a < 0) return s.indexOf('|').let { Field(s.replace("|", ""), it, it) }
        val e = s.indexOf(']') - 1
        return Field(s.replace("[", "").replace("]", ""), a, e)
    }

    @Test
    fun typesAtTheCaretOrOverTheSelection() {
        assertEquals(f("heXY|llo"), f("he|llo").type("XY"))
        assertEquals(f("hX|o"), f("h[ell]o").type("X"))
        assertEquals(f("hX|o"), Field("hello", 4, 1).type("X")) // backwards selection
        assertEquals(f("😀a|"), f("😀|").type("a"))
    }

    @Test
    fun deleteKeys() {
        assertEquals(f("hllo").let { Field(it.text, 1, 1) }, f("he|llo").key(Keys.DEL, 0))
        assertEquals(f("h|o"), f("h[ell]o").key(Keys.DEL, 0))
        assertEquals(f("|hello"), f("|hello").key(Keys.DEL, 0))
        assertEquals(f("a|"), f("a👍🏽|").key(Keys.DEL, 0)) // the whole emoji, skin tone included
        assertEquals(f("he|lo"), f("he|llo").key(Keys.FORWARD_DEL, 0))
        assertEquals(f("hello |"), f("hello world|").key(Keys.DEL, Keys.ALT))
        assertEquals(f("hello |"), f("hello |world").key(Keys.FORWARD_DEL, Keys.ALT))
    }

    @Test
    fun caretMoves() {
        assertEquals(f("h|ello"), f("he|llo").key(Keys.LEFT, 0))
        assertEquals(f("h|ello"), f("h[ell]o").key(Keys.LEFT, 0)) // collapses to the start
        assertEquals(f("hell|o"), f("h[ell]o").key(Keys.RIGHT, 0))
        assertEquals(Field("hello", 2, 3), f("he|llo").key(Keys.RIGHT, Keys.SHIFT))
        assertEquals(f("hello |world"), f("hello wor|ld").key(Keys.LEFT, Keys.ALT))
        assertEquals(f("ab\n|cd\nef"), f("ab\ncd|\nef").key(Keys.HOME, 0))
        assertEquals(f("ab\ncd|\nef"), f("ab\n|cd\nef").key(Keys.END, 0))
        assertEquals(f("|ab"), f("|ab").key(Keys.LEFT, 0))
        assertEquals(null, f("a|b").key(Keys.UP, 0))
        assertEquals(null, f("a|b").key(Keys.ENTER, 0))
    }

    @Test
    fun noSelectionMeansTheEnd() {
        assertEquals(f("abc|"), Field.of("abc", -1, -1))
        assertEquals(f("abX|"), Field.of("abc", -1, -1).key(Keys.DEL, 0)!!.type("X"))
        assertEquals(Field("abc", 3, 3), Field.of("abc", 9, 9))
    }
}
