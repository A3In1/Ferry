package dev.ferry.android.screen

import java.nio.BufferUnderflowException
import java.nio.ByteBuffer

/** FSP1 (docs/screen-protocol.md): magic, then `u8 type`, `u32 len` (big-endian), payload. */
object Fsp {
    val MAGIC = "FSP1".toByteArray()
    const val MAX_LEN = 8L shl 20

    const val INFO = 0x01
    const val FRAME = 0x02
    const val END = 0x03
    const val TOUCH = 0x10
    const val SCROLL = 0x11
    const val KEY = 0x12
    const val TEXT = 0x13
    const val NAV = 0x14
    const val KEYFRAME = 0x15
    const val CURSOR = 0x16
    const val START = 0x20

    const val CAP_VIDEO = 1
    const val CAP_CONTROL = 2

    const val END_NORMAL = 0
    const val END_STOPPED = 1
    const val END_LOCKED = 2
    const val END_NOT_ALLOWED = 3
    const val END_ERROR = 4

    fun encode(m: Msg): ByteArray {
        val p = when (m) {
            is Msg.Start -> buf(7).putShort(m.maxSize.toShort()).putInt(m.bitrate.toInt()).put(m.flags.toByte())
            is Msg.Touch -> buf(9).put(m.action.toByte()).putFloat(m.x).putFloat(m.y)
            is Msg.Scroll -> buf(16).putFloat(m.x).putFloat(m.y).putFloat(m.dx).putFloat(m.dy)
            is Msg.Text -> m.text.toByteArray().let { buf(it.size).put(it) }
            is Msg.Key -> buf(4).putInt(m.code.toInt())
            is Msg.Nav -> buf(1).put(m.action.toByte())
            Msg.Keyframe -> buf(0)
            is Msg.Cursor -> buf(9).put(m.action.toByte()).putFloat(m.x).putFloat(m.y)
            is Msg.Info -> buf(5).putShort(m.width.toShort()).putShort(m.height.toShort()).put(m.caps.toByte())
            is Msg.Frame -> buf(9 + m.data.size).putLong(m.ptsUs).put(m.flags.toByte()).put(m.data)
            is Msg.End -> m.detail.toByteArray().let { buf(1 + it.size).put(m.reason.toByte()).put(it) }
        }
        return p.put(0, m.type.toByte()).putInt(1, p.capacity() - 5).array()
    }

    /** A whole message with room for [n] payload bytes, written past the 5-byte header (filled in last), so a FRAME is copied once. */
    private fun buf(n: Int): ByteBuffer = ByteBuffer.allocate(5 + n).put(0.toByte()).putInt(0)

    /** null = unknown type (skipped). Extra trailing bytes are ignored; a short payload is an error. */
    fun decode(type: Int, p: ByteArray): Msg? {
        val b = ByteBuffer.wrap(p)
        try {
            return when (type) {
                START -> Msg.Start(b.short.u16(), b.int.u32(), b.get().u8())
                TOUCH -> Msg.Touch(b.get().u8(), b.float, b.float)
                SCROLL -> Msg.Scroll(b.float, b.float, b.float, b.float)
                TEXT -> Msg.Text(String(p))
                KEY -> Msg.Key(b.int.u32())
                NAV -> Msg.Nav(b.get().u8())
                KEYFRAME -> Msg.Keyframe
                CURSOR -> Msg.Cursor(b.get().u8(), b.float, b.float)
                INFO -> Msg.Info(b.short.u16(), b.short.u16(), b.get().u8())
                FRAME -> Msg.Frame(b.long, b.get().u8(), p.copyOfRange(9, p.size))
                END -> Msg.End(b.get().u8(), String(p, 1, p.size - 1))
                else -> null
            }
        } catch (_: BufferUnderflowException) {
            throw FspException("short payload for type $type")
        }
    }

    private fun Byte.u8() = toInt() and 0xff
    private fun Short.u16() = toInt() and 0xffff
    private fun Int.u32() = toLong() and 0xffffffffL
}

class FspException(msg: String) : Exception(msg)

sealed class Msg(val type: Int) {
    data class Start(val maxSize: Int, val bitrate: Long, val flags: Int) : Msg(Fsp.START) {
        val video get() = flags and 1 != 0
    }
    data class Touch(val action: Int, val x: Float, val y: Float) : Msg(Fsp.TOUCH)
    data class Scroll(val x: Float, val y: Float, val dx: Float, val dy: Float) : Msg(Fsp.SCROLL)
    data class Text(val text: String) : Msg(Fsp.TEXT)
    data class Key(val code: Long) : Msg(Fsp.KEY)
    data class Nav(val action: Int) : Msg(Fsp.NAV)
    data object Keyframe : Msg(Fsp.KEYFRAME)
    data class Cursor(val action: Int, val x: Float, val y: Float) : Msg(Fsp.CURSOR)
    data class Info(val width: Int, val height: Int, val caps: Int) : Msg(Fsp.INFO)
    class Frame(val ptsUs: Long, val flags: Int, val data: ByteArray) : Msg(Fsp.FRAME)
    data class End(val reason: Int, val detail: String = "") : Msg(Fsp.END)
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
