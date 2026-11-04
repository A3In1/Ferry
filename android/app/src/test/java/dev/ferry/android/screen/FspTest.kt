package dev.ferry.android.screen

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FspTest {
    private val all = listOf(
        Msg.Start(1280, 8_000_000, 1),
        Msg.Start(0, 0xffffffffL, 0),
        Msg.Touch(2, 0.25f, 1f),
        Msg.Scroll(0.5f, 0.5f, -1.5f, 3f),
        Msg.Scroll(0.1f, 0.9f, 0f, -0.25f, Fsp.SCROLL_CONTINUOUS or Fsp.SCROLL_END),
        Msg.Text("héllo 👋"),
        Msg.Key(67),
        Msg.Nav(4),
        Msg.Keyframe,
        Msg.Cursor(3, 0f, 0.75f),
        Msg.Info(1080, 2400, Fsp.CAP_VIDEO or Fsp.CAP_CONTROL),
        Msg.Info(65535, 1, 0),
        Msg.Frame(123_456_789_000L, 3, byteArrayOf(0, 0, 0, 1, 0x67, 0x42)),
        Msg.End(Fsp.END_NOT_ALLOWED, "turn on Ferry control"),
        Msg.End(Fsp.END_NORMAL),
        Msg.Quality(2_500_000),
        Msg.Key(Keys.LEFT, Keys.SHIFT),
        Msg.Ack(123_456_789_000L),
        Msg.Stats(2000, 120, 118, 2, 8_000_000, 7_500_000, 62, 3, 124, 41),
        Msg.Stats(2000, 0, 0, 0, 1, 0, Msg.Stats.UNKNOWN, Msg.Stats.UNKNOWN, Msg.Stats.UNKNOWN, Msg.Stats.UNKNOWN),
    )

    private fun stream(msgs: List<Msg>) = msgs.fold(Fsp.MAGIC) { acc, m -> acc + Fsp.encode(m) }

    @Test
    fun roundTripEveryType() {
        val parsed = FspParser().feed(stream(all))
        assertEquals(all.size, parsed.size)
        all.zip(parsed).forEach { (a, b) -> assertArrayEquals(Fsp.encode(a), Fsp.encode(b)) }
        assertEquals(all.filter { it !is Msg.Frame }, parsed.filter { it !is Msg.Frame })
        val f = parsed.filterIsInstance<Msg.Frame>().single()
        assertEquals(123_456_789_000L, f.ptsUs)
        assertEquals(3, f.flags)
        assertArrayEquals(byteArrayOf(0, 0, 0, 1, 0x67, 0x42), f.data)
    }

    @Test
    fun wireLayout() {
        assertArrayEquals(byteArrayOf(0x14, 0, 0, 0, 1, 2), Fsp.encode(Msg.Nav(2)))
        assertArrayEquals(byteArrayOf(0x01, 0, 0, 0, 5, 0x04, 0x38, 0x09, 0x60, 2), Fsp.encode(Msg.Info(1080, 2400, 2)))
    }

    @Test
    fun ackAndStatsLayout() {
        assertArrayEquals(byteArrayOf(0x17, 0, 0, 0, 8, 0, 0, 0, 0, 0, 0, 1, 2), Fsp.encode(Msg.Ack(0x0102)))
        val s = Fsp.encode(Msg.Stats(2000, 1, 2, 3, 4, 5, 6, 7, 8, 9))
        assertArrayEquals(byteArrayOf(0x05, 0, 0, 0, 24, 0x07, 0xd0.toByte(), 0, 1, 0, 2, 0, 3), s.copyOf(13))
        assertEquals(29, s.size)
    }

    @Test
    fun scrollWithoutFlagsFromAnOlderMac() {
        val old = byteArrayOf(0x11, 0, 0, 0, 16) + ByteArray(16)
        assertEquals(listOf(Msg.Scroll(0f, 0f, 0f, 0f, 0)), FspParser().feed(Fsp.MAGIC + old))
    }

    @Test
    fun splitAcrossChunks() {
        val bytes = stream(all)
        for (size in listOf(1, 2, 3, 7, 13)) {
            val p = FspParser()
            val parsed = bytes.toList().chunked(size).flatMap { p.feed(it.toByteArray()) }
            assertEquals(all.size, parsed.size)
            all.zip(parsed).forEach { (a, b) -> assertArrayEquals(Fsp.encode(a), Fsp.encode(b)) }
        }
    }

    @Test
    fun unknownTypesSkipped() {
        val unknown = byteArrayOf(0x7f, 0, 0, 0, 3, 1, 2, 3) + byteArrayOf(0x30, 0, 0, 0, 0)
        val parsed = FspParser().feed(Fsp.MAGIC + unknown + Fsp.encode(Msg.Nav(1)))
        assertEquals(listOf(Msg.Nav(1)), parsed)
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
        assertThrows { FspParser().feed(Fsp.MAGIC + byteArrayOf(0x10, 0, 0, 0, 2, 0, 0)) }
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
