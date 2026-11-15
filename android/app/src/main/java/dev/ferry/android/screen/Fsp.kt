package dev.ferry.android.screen

import java.nio.BufferUnderflowException
import java.nio.ByteBuffer

/** FSP1 (docs/screen-protocol.md): magic, then `u8 type`, `u32 len` (big-endian), payload. */
object Fsp {
    val MAGIC = "FSP1".toByteArray()
    const val MAX_LEN = 8L shl 20

    private const val INFO = 0x01
    private const val END = 0x03
    private const val SCROLL = 0x11
    private const val KEY = 0x12
    private const val TEXT = 0x13
    private const val CURSOR = 0x16
    private const val START = 0x20

    const val SCROLL_CONTINUOUS = 1
    const val SCROLL_END = 2

    const val CAP_CONTROL = 2

    const val END_NORMAL = 0
    const val END_STOPPED = 1
    const val END_LOCKED = 2
    const val END_NOT_ALLOWED = 3
    const val END_ERROR = 4

    fun info(width: Int, height: Int, caps: Int): ByteArray =
        message(INFO, ByteBuffer.allocate(5).putShort(width.toShort()).putShort(height.toShort()).put(caps.toByte()).array())

    fun end(reason: Int, detail: String = ""): ByteArray = message(END, byteArrayOf(reason.toByte()) + detail.toByteArray())

    private fun message(type: Int, payload: ByteArray): ByteArray =
        ByteBuffer.allocate(5 + payload.size).put(type.toByte()).putInt(payload.size).put(payload).array()

    /** A Mac → phone message; null = unknown type (skipped). Extra trailing bytes are ignored; a short payload is an error. */
    fun decode(type: Int, p: ByteArray): Msg? {
        val b = ByteBuffer.wrap(p)
        try {
            return when (type) {
                START -> Msg.Start
                SCROLL -> Msg.Scroll(b.float, b.float, b.float, b.float, b.get().u8())
                TEXT -> Msg.Text(String(p))
                KEY -> Msg.Key(b.int.toLong() and 0xffffffffL, b.get().u8())
                CURSOR -> Msg.Cursor(b.get().u8(), b.float, b.float)
                else -> null
            }
        } catch (_: BufferUnderflowException) {
            throw FspException("short payload for type $type")
        }
    }

    private fun Byte.u8() = toInt() and 0xff
}

class FspException(msg: String) : Exception(msg)

/** Mac → phone. */
sealed class Msg {
    data object Start : Msg()
    /** [flags]: [Fsp.SCROLL_CONTINUOUS] trackpad deltas (incl. momentum), [Fsp.SCROLL_END] lift the finger; 0 = a wheel step. */
    data class Scroll(val x: Float, val y: Float, val dx: Float, val dy: Float, val flags: Int) : Msg() {
        val continuous get() = flags and Fsp.SCROLL_CONTINUOUS != 0
        val end get() = flags and Fsp.SCROLL_END != 0
    }
    data class Text(val text: String) : Msg()
    /** [code]: an Android keycode ([Keys]); [meta]: [Keys.SHIFT], [Keys.ALT]. */
    data class Key(val code: Long, val meta: Int) : Msg()
    data class Cursor(val action: Int, val x: Float, val y: Float) : Msg()
}

/** Incremental parser: feed it whatever chunks the stream returns. Throws [FspException] on a protocol error. */
class FspParser {
    private var magic = 0
    private val head = ByteArray(5)
    private var headN = 0
    private var body: ByteArray? = null
    private var bodyN = 0

    fun feed(chunk: ByteArray): List<Msg> {
        val out = mutableListOf<Msg>()
        var i = 0
        while (i < chunk.size) {
            val b = body
            if (magic < 4) {
                if (chunk[i++] != Fsp.MAGIC[magic++]) throw FspException("bad magic")
                continue
            } else if (b == null) {
                head[headN++] = chunk[i++]
                if (headN < 5) continue
                val len = ByteBuffer.wrap(head, 1, 4).int.toLong() and 0xffffffffL
                if (len > Fsp.MAX_LEN) throw FspException("message too long: $len")
                body = ByteArray(len.toInt())
                bodyN = 0
            } else {
                val n = minOf(b.size - bodyN, chunk.size - i)
                System.arraycopy(chunk, i, b, bodyN, n)
                bodyN += n
                i += n
            }
            val done = body ?: continue
            if (bodyN == done.size) {
                Fsp.decode(head[0].toInt() and 0xff, done)?.let(out::add)
                body = null
                headN = 0
            }
        }
        return out
    }
}
