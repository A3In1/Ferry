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
    private val incoming = HashMap<String, List<String>>() // transfer id -> offered names
    private val lastShown = HashMap<String, Long>()

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

    private fun name(tid: String, index: UInt): String =
        incoming[tid]?.getOrNull(index.toInt()) ?: "file ${index + 1u}"

    private fun handle(ctx: Context, e: FfiFileEvent) {
        when (e) {
            is FfiFileEvent.Offered -> {
                incoming[e.transferId] = e.names
                val size = Formatter.formatShortFileSize(ctx, e.totalBytes.toLong())
                Ferry.put(e.transferId, "Incoming ${e.names.size} file(s), $size")
            }

            is FfiFileEvent.Progress -> {
                val key = key(e.transferId, e.index)
                val now = SystemClock.elapsedRealtime()
                // Throttle: at most two updates a second per file, plus the final one.
                if (e.done < e.total && now - (lastShown[key] ?: 0) < 500) return
                lastShown[key] = now
                val pct = if (e.total > 0u) (e.done * 100u / e.total).toInt() else 100
                val label = if (e.transferId in incoming) "Receiving ${name(e.transferId, e.index)}" else "Sending file ${e.index + 1u}"
                Ferry.put(key, "$label — $pct%")
                Notifs.progress(ctx, key.hashCode(), label, pct)
            }

            is FfiFileEvent.Saved -> {
                val key = key(e.transferId, e.index)
                lastShown.remove(key)
                if (e.transferId !in incoming) {
                    // Sender side: `path` is the name the receiver saved it under.
                    Notifs.cancel(ctx, key.hashCode())
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
                    Ferry.put(key, "Saved ${file.name} (couldn't move it to Downloads)")
                    Notifs.result(ctx, "Received ${file.name}", "Couldn't move it to Downloads: ${ex.text()}", key.hashCode())
                }
            }

            is FfiFileEvent.Failed -> {
                val key = key(e.transferId, e.index)
                lastShown.remove(key)
                val n = name(e.transferId, e.index)
                Ferry.put(key, "$n failed: ${e.error}")
                if (e.transferId in incoming) {
                    Notifs.result(ctx, "Couldn't receive $n", e.error, key.hashCode())
                } else {
                    Notifs.cancel(ctx, key.hashCode()) // the send's own result notification reports it
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
