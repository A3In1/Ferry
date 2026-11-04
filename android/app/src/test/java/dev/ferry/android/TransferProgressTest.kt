package dev.ferry.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TransferProgressTest {
    @Test
    fun sumsFilesInFlightAndCountsFiles() {
        val p = TransferProgress(listOf("a", "b", "c"), 300)
        assertEquals(0f, p.fraction)
        assertEquals(1, p.fileNumber)
        p.progress(0u, 50, 100, 0)
        p.progress(1u, 30, 100, 0)
        assertEquals(80L, p.bytesDone)
        assertEquals("b", p.currentName)
        p.progress(0u, 100, 100, 0)
        assertEquals(2, p.fileNumber)
        p.finish(1u)
        p.finish(2u)
        assertEquals(3, p.fileNumber) // never "4 of 3"
    }

    @Test
    fun unknownSizeIsIndeterminate() {
        assertNull(TransferProgress(listOf("a")).fraction)
        assertEquals("", TransferProgress(listOf("a")).detail(0) { "$it" })
    }

    @Test
    fun speedIgnoresResumedHeadStart() {
        val p = TransferProgress(listOf("big"), 100_000_000)
        p.progress(0u, 60_000_000, 100_000_000, 0) // resumed at 60 MB
        assertNull(p.speed(0))
        p.progress(0u, 70_000_000, 100_000_000, 1_000)
        assertEquals(10_000_000.0, p.speed(1_000)!!, 0.1)
        assertEquals(3.0, p.secondsLeft(1_000)!!, 0.01)
        assertEquals("70 of 100 · 10.0 MB/s · 3 s left", p.detail(1_000) { "${it / 1_000_000}" })
        assertNull(p.speed(10_000)) // stalled
    }

    @Test
    fun timeLeftWording() {
        assertEquals("1 s left", TransferProgress.timeLeft(0.2))
        assertEquals("2 min left", TransferProgress.timeLeft(61.0))
        assertEquals("about 2 h left", TransferProgress.timeLeft(7200.0))
    }

    @Test
    fun idsMatchTheCore() {
        val id = TransferProgress.newId()
        assertEquals(32, id.length)
        assertTrue(id.all { it in "0123456789abcdef" })
    }
}
