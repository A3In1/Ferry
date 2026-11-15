package dev.ferry.android.screen

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer

class FspTest {
    private fun msg(type: Int, payload: ByteBuffer.() -> Unit, size: Int): ByteArray =
        ByteBuffer.allocate(5 + size).put(type.toByte()).putInt(size).apply(payload).array()

    /** The Mac's bytes for one of each kind (mac/Tests/FerryLogicTests/FspTests.swift pins the same layout). */
    private val fromMac = Fsp.MAGIC +
        byteArrayOf(0x20, 0, 0, 0, 0) +
        msg(0x11, { putFloat(0.5f).putFloat(0.25f).putFloat(-1.5f).putFloat(3f).put(3) }, 17) +
        msg(0x13, { put("héllo 👋".toByteArray()) }, "héllo 👋".toByteArray().size) +
        msg(0x12, { putInt(21).put(1) }, 5) +
        msg(0x16, { put(3).putFloat(0f).putFloat(0.75f) }, 9)

    private val parsed = listOf(
        Msg.Start,
        Msg.Scroll(0.5f, 0.25f, -1.5f, 3f, Fsp.SCROLL_CONTINUOUS or Fsp.SCROLL_END),
        Msg.Text("héllo 👋"),
        Msg.Key(Keys.LEFT, Keys.SHIFT),
        Msg.Cursor(3, 0f, 0.75f),
    )

    @Test
    fun everyKindFromTheMac() {
        assertEquals(parsed, FspParser().feed(fromMac))
    }

    @Test
    fun splitAcrossChunks() {
        for (size in listOf(1, 2, 3, 7, 13)) {
            val p = FspParser()
            assertEquals(parsed, fromMac.toList().chunked(size).flatMap { p.feed(it.toByteArray()) })
        }
    }

    @Test
    fun wireLayoutToTheMac() {
        assertArrayEquals(byteArrayOf(0x01, 0, 0, 0, 5, 0x04, 0x38, 0x09, 0x60, 2), Fsp.info(1080, 2400, Fsp.CAP_CONTROL))
        assertArrayEquals(byteArrayOf(0x03, 0, 0, 0, 3, 2, 0x6f, 0x6b), Fsp.end(Fsp.END_LOCKED, "ok"))
        assertArrayEquals(byteArrayOf(0x03, 0, 0, 0, 1, 0), Fsp.end(Fsp.END_NORMAL))
    }

    @Test
    fun unknownTypesSkipped() {
        val unknown = byteArrayOf(0x7f, 0, 0, 0, 3, 1, 2, 3) + byteArrayOf(0x30, 0, 0, 0, 0)
        assertEquals(listOf(Msg.Start), FspParser().feed(Fsp.MAGIC + unknown + byteArrayOf(0x20, 0, 0, 0, 0)))
    }

    @Test
    fun oversizeRejected() {
        val ok = FspParser().feed(Fsp.MAGIC + byteArrayOf(0x7f, 0x00, 0x80.toByte(), 0, 0)) // exactly 8 MiB: waits for the body
        assertTrue(ok.isEmpty())
        assertThrows { FspParser().feed(Fsp.MAGIC + byteArrayOf(0x13, 0x00, 0x80.toByte(), 0, 1)) }
        assertThrows { FspParser().feed(Fsp.MAGIC + byteArrayOf(0x13, 0xff.toByte(), 0xff.toByte(), 0xff.toByte(), 0xff.toByte())) }
    }

    @Test
    fun badMagicAndShortPayloadRejected() {
        assertThrows { FspParser().feed("FSP2".toByteArray()) }
        assertThrows { FspParser().feed(Fsp.MAGIC + byteArrayOf(0x16, 0, 0, 0, 2, 0, 0)) }
    }

    private fun assertThrows(block: () -> Unit) {
        try {
            block()
        } catch (_: FspException) {
            return
        }
        throw AssertionError("expected FspException")
    }
}
