package dev.ferry.android

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.os.SystemClock
import android.provider.MediaStore
import android.text.format.Formatter
import android.util.Log
import android.webkit.MimeTypeMap
import dev.ferry.core.FfiFileEvent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import java.io.File
import java.io.IOException

/**
 * File events from the core, handled in order on one coroutine (Rust threads must not block
 * on the MediaStore copy). The core reports both directions; only transfers that started with
 * an `Offered` event are incoming.
 */
object Transfers {
    private val events = Channel<FfiFileEvent>(Channel.UNLIMITED)
    // The core sends no end event for a transfer whose connection dropped, so these only keep the latest few.
    private val incoming = lru<List<String>>(64) // transfer id -> offered names
    private val lastShown = lru<Long>(256) // transfer id/index -> last progress update
    /** Per-file progress notifications still up, so a stopped service doesn't leave them behind. */
    private val shown = HashSet<Int>()

    /**
     * A send from [Ferry.send]: one notification ([id]) from "Sending…" to the result. The core's
     * progress events carry only a transfer id, so a send claims the first unknown outgoing id
     * while it is the only unclaimed send; otherwise progress falls back to per-file notifications.
     */
    class Outgoing(val mac: String, val names: List<String>) {
        val id = System.identityHashCode(this) xor 0x5e4d
        var tid: String? = null
        var done = false
    }

    private val sends = ArrayList<Outgoing>() // guarded by itself; notifications for a send are posted under it

    fun sending(ctx: Context, o: Outgoing) = synchronized(sends) {
        sends += o
        Ferry.put("send-${o.id}", "Sending ${count(o.names.size, "file")} to ${o.mac}…")
        Notifs.progress(ctx, o.id, "Sending ${count(o.names.size, "file")} to ${o.mac}", -1, "Connecting…")
    }

    /** The send's result replaces its progress notification. */
    fun done(ctx: Context, o: Outgoing, title: String, text: String) = synchronized(sends) {
        o.done = true
        Ferry.put("send-${o.id}", title)
        Notifs.result(ctx, title, text, o.id)
    }

    /** Always called last: a cancelled send leaves no "Sending…" behind. */
    fun end(ctx: Context, o: Outgoing) = synchronized(sends) {
        if (!o.done) Notifs.cancel(ctx, o.id)
        o.done = true
        // A claimed send stays listed a while, so its late events still find it (and post nothing).
        if (o.tid == null) sends -= o
        while (sends.count { it.done } > 16) sends.remove(sends.first { it.done })
    }

    private fun outgoing(tid: String): Outgoing? = synchronized(sends) {
        sends.firstOrNull { it.tid == tid } ?: sends.singleOrNull { it.tid == null && !it.done }?.also { it.tid = tid }
    }

    /** The service stopped: drop progress notifications no further event will update. */
    fun clear(ctx: Context) = synchronized(shown) {
        shown.forEach { Notifs.cancel(ctx, it) }
        shown.clear()
    }

    private fun progress(ctx: Context, id: Int, title: String, pct: Int, text: String? = null) {
        synchronized(shown) { shown += id }
        Notifs.progress(ctx, id, title, pct, text)
    }

    private fun settled(id: Int) = synchronized(shown) { shown -= id }

    fun offer(e: FfiFileEvent) {
        events.trySend(e)
    }

    fun start(ctx: Context) = Ferry.scope.launch(Dispatchers.IO) {
        for (e in events) {
            try {
                handle(ctx, e)
            } catch (ex: Exception) {
                Log.e(TAG, "file event $e", ex)
            }
        }
    }

    private fun key(tid: String, index: UInt) = "$tid/$index"

    /** Insertion-ordered map that drops its oldest entry beyond [max]. Not thread-safe. */
    fun <V> lru(max: Int): MutableMap<String, V> = object : LinkedHashMap<String, V>() {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, V>?) = size > max
    }

    private fun name(tid: String, index: UInt): String =
        incoming[tid]?.getOrNull(index.toInt()) ?: "file ${index + 1u}"

    private fun handle(ctx: Context, e: FfiFileEvent) {
        when (e) {
            is FfiFileEvent.Offered -> {
                incoming[e.transferId] = e.names
                val size = Formatter.formatShortFileSize(ctx, e.totalBytes.toLong())
                Ferry.put(e.transferId, "Receiving ${count(e.names.size, "file")} ($size)")
            }

            is FfiFileEvent.Progress -> {
                val key = key(e.transferId, e.index)
                val now = SystemClock.elapsedRealtime()
                // Throttle: at most two updates a second per file, plus the final one.
                if (e.done < e.total && now - (lastShown[key] ?: 0) < 500) return
                lastShown[key] = now
                val pct = if (e.total > 0u) (e.done * 100u / e.total).toInt() else 100
                if (e.transferId in incoming) {
                    val label = "Receiving ${name(e.transferId, e.index)}"
                    Ferry.put(key, "$label · $pct%", pct / 100f)
                    return progress(ctx, key.hashCode(), label, pct)
                }
                val o = outgoing(e.transferId)
                if (o == null) {
                    val label = "Sending file ${e.index + 1u}"
                    Ferry.put(key, "$label · $pct%", pct / 100f)
                    return progress(ctx, key.hashCode(), label, pct)
                }
                val i = e.index.toInt()
                val label = "Sending ${o.names.getOrElse(i) { "file ${i + 1}" }}"
                Ferry.put(key, "$label · $pct%", pct / 100f)
                synchronized(sends) {
                    if (!o.done) Notifs.progress(ctx, o.id, "$label to ${o.mac}", pct, if (o.names.size > 1) "File ${i + 1} of ${o.names.size}" else null)
                }
            }

            is FfiFileEvent.Saved -> {
                val key = key(e.transferId, e.index)
                lastShown.remove(key)
                settled(key.hashCode())
                if (e.transferId !in incoming) {
                    // Sender side: `path` is the name the receiver saved it under. A tracked send keeps
                    // its one notification until its result.
                    if (outgoing(e.transferId) == null) Notifs.cancel(ctx, key.hashCode())
                    Ferry.put(key, "Sent ${File(e.path).name}")
                    return
                }
                val file = File(e.path)
                val inbox = Ferry.incomingDir(ctx).canonicalPath + File.separator
                if (!file.canonicalPath.startsWith(inbox)) {
                    Log.w(TAG, "saved file outside the incoming dir, not moving: ${e.path}")
                    Notifs.cancel(ctx, key.hashCode())
                    Ferry.put(key, "Saved ${file.name}")
                    return
                }
                try {
                    val (uri, mime) = publishToDownloads(ctx, file)
                    Ferry.put(key, "Saved ${file.name} to Downloads/Ferry")
                    Notifs.received(ctx, key.hashCode(), file.name, uri, mime) // replaces the progress notification
                } catch (ex: Exception) {
                    // The file is still complete in app storage; nothing is lost.
                    Log.e(TAG, "couldn't move ${file.name} to Downloads", ex)
                    Ferry.put(key, "Received ${file.name}, but couldn't save it to Downloads")
                    Notifs.result(ctx, "Received ${file.name}", "Couldn't save it to Downloads/Ferry, so it's in Ferry's own storage. ${ex.friendly()}", key.hashCode())
                }
            }

            is FfiFileEvent.Failed -> {
                val key = key(e.transferId, e.index)
                lastShown.remove(key)
                settled(key.hashCode())
                Log.w(TAG, "transfer ${e.transferId.take(8)}/${e.index} failed: ${e.error}")
                if (e.transferId in incoming) {
                    val n = name(e.transferId, e.index)
                    Ferry.put(key, "Couldn't receive $n")
                    Notifs.result(ctx, "Couldn't receive $n", Errors.friendly(e.error), key.hashCode())
                } else {
                    val o = outgoing(e.transferId)
                    Ferry.put(key, "Couldn't send ${o?.names?.getOrNull(e.index.toInt()) ?: "file ${e.index + 1u}"}")
                    if (o == null) Notifs.cancel(ctx, key.hashCode()) // the send's own result notification reports it
                }
            }
        }
    }

    /**
     * Moves a received file into public Downloads/Ferry through MediaStore (no storage permission
     * needed on API 29+). Returns the content URI and MIME type. Deletes the private copy only
     * after the public one is complete.
     */
    // shortcut: the copy doubles disk I/O and needs 2x free space for huge files; on API 30+
    // the core could write straight into a MediaStore file path instead.
    private fun publishToDownloads(ctx: Context, file: File): Pair<Uri, String> {
        val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(file.extension.lowercase())
            ?: "application/octet-stream"
        val cr = ctx.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, file.name)
            put(MediaStore.Downloads.MIME_TYPE, mime)
            put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/Ferry")
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val uri = cr.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: throw IOException("MediaStore refused the new download")
        try {
            val out = cr.openOutputStream(uri) ?: throw IOException("can't write $uri")
            out.use { o -> file.inputStream().use { it.copyTo(o, 1 shl 20) } }
            cr.update(uri, ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }, null, null)
        } catch (e: Exception) {
            cr.delete(uri, null, null)
            throw e
        }
        file.delete()
        return uri to mime
    }
}
