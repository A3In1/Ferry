package dev.ferry.android

import android.content.BroadcastReceiver
import android.content.ContentValues
import android.content.Context
import android.content.Intent
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.io.IOException

/** Cancel on a transfer's progress notification. */
class TransferCancel : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        intent.getStringExtra(Transfers.EXTRA_ID)?.let(Transfers::cancel)
    }
}

/**
 * File transfers in both directions, each with one progress notification (with Cancel) and a card
 * in the app. Core events are handled in order on one coroutine (Rust threads must not block on
 * the MediaStore copy); a send also reports its start and result from [Ferry.send].
 */
object Transfers {
    const val EXTRA_ID = "transfer"
    private const val NOTIFY_MS = 500L // Android drops notification updates faster than a few a second
    private const val CARD_MS = 200L
    private const val STALL_MS = 120_000L
    private const val LINGER_MS = 4_000L

    private val events = Channel<FfiFileEvent>(Channel.UNLIMITED)

    /** What the in-app card shows for one transfer. */
    data class Card(
        val id: String,
        val outgoing: Boolean,
        val title: String,
        val files: String?, // "File 2 of 3: name"
        val fraction: Float?, // null: indeterminate
        val detail: String,
        val running: Boolean,
    )

    val cards = MutableStateFlow<List<Card>>(emptyList())

    private class Live(val id: String, val outgoing: Boolean, val peer: String?, names: List<String>, total: Long = 0) {
        val p = TransferProgress(names, total)
        val notif = id.hashCode()
        var saved = 0
        var failed = 0
        var end: String? = null // final status once over
        var lastEvent = SystemClock.elapsedRealtime()
        var notified = 0L
        var carded = 0L
        val running get() = end == null
    }

    private val live = LinkedHashMap<String, Live>() // guarded by itself; newest last

    private fun find(id: String) = synchronized(live) { live[id] }

    private fun what(names: List<String>) = names.singleOrNull() ?: count(names.size, "file")

    private fun Live.title(): String {
        val w = what(p.names)
        return if (outgoing) "Sending $w" + (peer?.let { " to $it" } ?: "") else "Receiving $w" + (peer?.let { " from $it" } ?: "")
    }

    private fun Live.files(): String? =
        if (p.fileCount > 1) "File ${p.fileNumber} of ${p.fileCount}" + (p.currentName?.let { ": $it" } ?: "") else null

    private fun Live.detail(ctx: Context, now: Long): String =
        end ?: if (p.totalBytes == 0L) "Connecting…" else p.detail(now) { Formatter.formatShortFileSize(ctx, it) }

    /** Updates this transfer's notification and card, throttled unless [force]. */
    private fun Live.show(ctx: Context, force: Boolean = false) {
        val now = SystemClock.elapsedRealtime()
        if (force || now - carded >= CARD_MS) {
            carded = now
            publishCards(ctx)
        }
        if (running && (force || now - notified >= NOTIFY_MS)) {
            notified = now
            val pct = p.fraction?.let { (it * 100).toInt() } ?: -1
            val text = listOfNotNull(files(), detail(ctx, now).takeIf { it.isNotEmpty() }).joinToString("\n")
            Notifs.progress(ctx, notif, title(), pct, text, cancel = id)
        }
    }

    private fun publishCards(ctx: Context) {
        val now = SystemClock.elapsedRealtime()
        cards.value = synchronized(live) {
            live.values.reversed().map { Card(it.id, it.outgoing, it.title(), it.files(), it.p.fraction, it.detail(ctx, now), it.running) }
        }
    }

    private fun begin(ctx: Context, t: Live) {
        synchronized(live) { live[t.id] = t }
        t.show(ctx, force = true)
    }

    /** Over: the notification becomes [result] (or goes), the card shows [status] a few seconds, [line] stays in Recent. */
    private fun finish(ctx: Context, t: Live, status: String, line: String, result: Pair<String, String>?) {
        if (!t.running) return
        t.end = status
        Ferry.put(t.id, line)
        if (result != null) Notifs.result(ctx, result.first, result.second, t.notif) else Notifs.cancel(ctx, t.notif)
        publishCards(ctx)
        Ferry.scope.launch {
            delay(LINGER_MS)
            synchronized(live) { live.remove(t.id) }
            publishCards(ctx)
        }
    }

    /** Cancel button (notification or card). Both devices then report the transfer cancelled. */
    fun cancel(id: String) {
        val node = Ferry.node.value ?: return
        if (!node.cancelTransfer(id)) Log.w(TAG, "transfer ${id.take(8)} already over, nothing to cancel")
    }

    /** A send from [Ferry.send] starts; [id] is the transfer id it passes to the core. */
    fun sending(ctx: Context, id: String, mac: String, names: List<String>) = begin(ctx, Live(id, true, mac, names))

    fun sent(ctx: Context, id: String, title: String, text: String) {
        val t = find(id) ?: return
        finish(ctx, t, "Sent", title, title to text)
    }

    fun sendFailed(ctx: Context, id: String, title: String, text: String) {
        val t = find(id) ?: return
        finish(ctx, t, "Couldn't send", title, title to text)
    }

    fun sendCancelled(ctx: Context, id: String, byPeer: Boolean) {
        val t = find(id) ?: return
        val line = cancelledLine(t, byPeer)
        finish(ctx, t, if (byPeer) "Cancelled on ${t.peer ?: "the Mac"}" else "Cancelled", line, null)
    }

    private fun cancelledLine(t: Live, byPeer: Boolean): String {
        val w = what(t.p.names)
        val base = when {
            byPeer -> "$w: cancelled on ${t.peer ?: "the Mac"}"
            t.outgoing -> "Cancelled sending $w"
            else -> "Cancelled receiving $w"
        }
        return if (t.saved > 0 && !t.outgoing) "$base · ${t.saved} saved to Downloads/Ferry" else base
    }

    /** The service stopped: drop progress notifications no further event will update. */
    fun clear(ctx: Context) {
        val all = synchronized(live) { live.values.toList().also { live.clear() } }
        all.forEach { Notifs.cancel(ctx, it.notif) }
        cards.value = emptyList()
    }

    fun offer(e: FfiFileEvent) {
        events.trySend(e)
    }

    fun start(ctx: Context) {
        Ferry.scope.launch(Dispatchers.IO) {
            for (e in events) {
                try {
                    handle(ctx, e)
                } catch (ex: Exception) {
                    Log.e(TAG, "file event $e", ex)
                }
            }
        }
        // The core sends nothing more for a transfer whose connection dropped.
        Ferry.scope.launch(Dispatchers.IO) {
            while (true) {
                delay(10_000)
                val now = SystemClock.elapsedRealtime()
                val stalled = synchronized(live) { live.values.filter { !it.outgoing && it.running && now - it.lastEvent > STALL_MS } }
                stalled.forEach { events.trySend(FfiFileEvent.Failed(it.id, UInt.MAX_VALUE, STALLED)) }
            }
        }
    }

    private const val STALLED = "connection lost"

    private fun key(tid: String, index: UInt) = "$tid/$index"

    /** Insertion-ordered map that drops its oldest entry beyond [max]. Not thread-safe. */
    fun <V> lru(max: Int): MutableMap<String, V> = object : LinkedHashMap<String, V>() {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, V>?) = size > max
    }

    private fun name(t: Live, index: UInt): String = t.p.names.getOrNull(index.toInt()) ?: "file ${index + 1u}"

    /** Incoming: every item saved or failed. */
    private fun receivedAll(ctx: Context, t: Live) {
        if (t.saved + t.failed < t.p.fileCount) return
        val w = what(t.p.names)
        when {
            t.failed == 0 -> finish(ctx, t, "Received", "Received $w", null) // each file has its own "Received" notification
            t.saved > 0 -> finish(ctx, t, "${t.failed} couldn't be received", "Received ${t.saved} of ${t.p.fileCount} files", null)
            else -> finish(ctx, t, "Couldn't receive", "Couldn't receive $w", null)
        }
    }

    private fun handle(ctx: Context, e: FfiFileEvent) {
        when (e) {
            is FfiFileEvent.Sending -> {
                val t = find(e.transferId) ?: return // a send not started from Ferry.send
                t.p.names = e.names
                t.p.totalBytes = e.totalBytes.toLong()
                t.lastEvent = SystemClock.elapsedRealtime()
                t.show(ctx, force = true)
            }

            is FfiFileEvent.Offered -> {
                val mac = Ferry.peers.value.singleOrNull()?.name // the event doesn't say which Mac
                begin(ctx, Live(e.transferId, false, mac, e.names, e.totalBytes.toLong()))
            }

            is FfiFileEvent.Progress -> {
                val t = find(e.transferId)?.takeIf { it.running } ?: return
                val now = SystemClock.elapsedRealtime()
                t.lastEvent = now
                t.p.progress(e.index, e.done.toLong(), e.total.toLong(), now)
                t.show(ctx, force = e.done == e.total && t.p.fraction == 1f)
            }

            is FfiFileEvent.Saved -> {
                val t = find(e.transferId)?.takeIf { it.running } ?: return
                t.lastEvent = SystemClock.elapsedRealtime()
                t.p.finish(e.index)
                // Sender side: `path` is the name the receiver saved it under; the send's own result reports it.
                if (t.outgoing) return t.show(ctx)
                t.saved++
                saveToDownloads(ctx, key(e.transferId, e.index), File(e.path))
                t.show(ctx)
                receivedAll(ctx, t)
            }

            is FfiFileEvent.Failed -> {
                val t = find(e.transferId)?.takeIf { it.running } ?: return
                if (e.error == STALLED && e.index == UInt.MAX_VALUE) {
                    Log.w(TAG, "transfer ${e.transferId.take(8)} stalled, giving up")
                    val w = what(t.p.names)
                    return finish(ctx, t, "The connection was lost", "Couldn't receive $w: the connection was lost",
                        "Couldn't receive $w" to "The connection to your Mac was lost. Send it again to continue where it stopped.")
                }
                Log.w(TAG, "transfer ${e.transferId.take(8)}/${e.index} failed: ${e.error}")
                t.lastEvent = SystemClock.elapsedRealtime()
                t.p.finish(e.index)
                if (t.outgoing) return t.show(ctx) // the send's own result reports it
                t.failed++
                val n = name(t, e.index)
                val k = key(e.transferId, e.index)
                Notifs.result(ctx, "Couldn't receive $n", Errors.friendly(e.error), k.hashCode())
                t.show(ctx)
                receivedAll(ctx, t)
            }

            is FfiFileEvent.Cancelled -> {
                // A send's own result (FerryException.Cancelled) ends it.
                val t = find(e.transferId)?.takeIf { it.running && !it.outgoing } ?: return
                finish(ctx, t, if (e.byPeer) "Cancelled on ${t.peer ?: "the Mac"}" else "Cancelled", cancelledLine(t, e.byPeer), null)
            }
        }
    }

    /** A received file goes to public Downloads/Ferry, with its own "Received" notification. */
    private fun saveToDownloads(ctx: Context, key: String, file: File) {
        val inbox = Ferry.incomingDir(ctx).canonicalPath + File.separator
        if (!file.canonicalPath.startsWith(inbox)) {
            Log.w(TAG, "saved file outside the incoming dir, not moving: $file")
            return
        }
        try {
            val (uri, mime) = publishToDownloads(ctx, file)
            Notifs.received(ctx, key.hashCode(), file.name, uri, mime)
        } catch (ex: Exception) {
            // The file is still complete in app storage; nothing is lost.
            Log.e(TAG, "couldn't move ${file.name} to Downloads", ex)
            Notifs.result(ctx, "Received ${file.name}", "Couldn't save it to Downloads/Ferry, so it's in Ferry's own storage. ${ex.friendly()}", key.hashCode())
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
