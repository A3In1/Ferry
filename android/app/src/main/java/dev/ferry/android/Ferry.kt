package dev.ferry.android

import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import android.text.format.Formatter
import android.util.Log
import android.widget.Toast
import dev.ferry.android.screen.Screen
import dev.ferry.core.FerryDelegate
import dev.ferry.core.FerryNode
import dev.ferry.core.FfiEvent
import dev.ferry.core.FfiFileEvent
import dev.ferry.core.FfiPeer
import dev.ferry.core.FfiSource
import dev.ferry.core.ScreenStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File

const val TAG = "Ferry"

data class Recent(val id: String, val text: String)

class PairPrompt(val peerName: String, val code: String) {
    val answer = CompletableDeferred<Boolean>()
}

class Source(val pfd: ParcelFileDescriptor, val name: String?)

/**
 * Process-wide state shared by [FerryService] (which owns the node) and the UI.
 * Work launched in [scope] outlives activities; the foreground service keeps the process alive.
 */
object Ferry {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val node = MutableStateFlow<FerryNode?>(null)
    val status = MutableStateFlow("Starting…")
    val usbListening = MutableStateFlow(false)
    val peers = MutableStateFlow<List<FfiPeer>>(emptyList())
    /** Paired peers whose presence tag we saw on the LAN. */
    val nearby = MutableStateFlow<Set<String>>(emptySet())
    /** Paired peer with a live Bluetooth bridge (see [Ble]), if any. */
    val ble = MutableStateFlow<String?>(null)
    val recent = MutableStateFlow<List<Recent>>(emptyList())
    val prompt = MutableStateFlow<PairPrompt?>(null)

    /** Started MainActivity instances; written on the main thread only. */
    @Volatile var uiVisible = 0

    /** Last node shutdown, so a quick restart doesn't race the old node for the data dir. */
    @Volatile var shutdownJob: Job? = null

    fun incomingDir(ctx: Context) = File(ctx.getExternalFilesDir(null) ?: ctx.filesDir, "incoming")

    fun refreshPeers() {
        peers.value = node.value?.peers() ?: emptyList()
    }

    fun put(id: String, text: String) = recent.update { list ->
        val i = list.indexOfFirst { it.id == id }
        if (i >= 0) list.toMutableList().also { it[i] = Recent(id, text) } else (listOf(Recent(id, text)) + list).take(5)
    }

    /** The running node, waiting briefly if the service is still starting it. */
    suspend fun awaitNode() = withTimeoutOrNull(30_000) { node.filterNotNull().first() }

    fun pair(ctx: Context, ticket: String) {
        val app = ctx.applicationContext
        toast(app, "Pairing…")
        scope.launch {
            val node = awaitNode() ?: return@launch toast(app, "Ferry isn't running")
            try {
                val peer = node.pairWithTicket(ticket)
                refreshPeers()
                toast(app, "Paired with ${peer.name}")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                toast(app, "Pairing failed: ${e.text()}")
            }
        }
    }

    fun forget(ctx: Context, peer: FfiPeer) {
        try {
            node.value?.forgetPeer(peer.id)
        } catch (e: Exception) {
            toast(ctx, "Couldn't forget ${peer.name}: ${e.text()}")
        }
        nearby.update { it - peer.id }
        refreshPeers()
    }

    /**
     * Opens the documents now, while the calling activity still holds the URI grant; an open
     * fd stays readable after the grant ends. Call off the main thread (providers may block).
     */
    fun open(ctx: Context, uris: List<Uri>): List<Source> = uris.mapNotNull { uri ->
        try {
            val pfd = ctx.contentResolver.openFileDescriptor(uri, "r") ?: return@mapNotNull null
            val name = ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
            Source(pfd, name ?: uri.lastPathSegment)
        } catch (e: Exception) {
            Log.w(TAG, "can't open $uri", e)
            null
        }
    }

    /** Sends in [scope] so it survives leaving the UI; closes the fds when done. */
    fun send(ctx: Context, peer: FfiPeer, files: List<Source>) {
        val app = ctx.applicationContext
        if (files.isEmpty()) return toast(app, "Couldn't open the selected files")
        if (bluetoothOnly(peer.id)) toast(app, "Connected over Bluetooth — large files are slow; join the same Wi-Fi")
        scope.launch {
            try {
                val node = awaitNode() ?: error("Ferry isn't running")
                // The core opens the path itself; /proc/self/fd/N reopens our fd.
                val r = node.sendNamed(peer.id, files.map { FfiSource("/proc/self/fd/${it.pfd.fd}", it.name) })
                val size = Formatter.formatShortFileSize(app, r.bytesSent.toLong())
                Notifs.result(app, "Sent ${r.filesSent} file(s) to ${peer.name}", "$size in ${"%.1f".format(r.elapsedMs.toLong() / 1000.0)} s")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Notifs.result(app, "Send to ${peer.name} failed", e.text())
            } finally {
                files.forEach { runCatching { it.pfd.close() } }
            }
        }
    }

    /**
     * A user-initiated event to every paired Mac. The caller is a visible activity, so it starts
     * the service; this waits for the node first. Toasts [done] once queued.
     */
    fun sendEvent(ctx: Context, event: FfiEvent, done: String) {
        val app = ctx.applicationContext
        FerryService.start(app)
        scope.launch {
            awaitNode() ?: return@launch toast(app, "Ferry isn't running")
            refreshPeers()
            if (peers.value.isEmpty()) return@launch toast(app, "No paired Mac yet")
            // The user is waiting: send directly (not via Outbox, which drops while a Mac is parked)
            // and report what really happened.
            val node = node.value ?: return@launch toast(app, "Ferry isn't running")
            val errors = peers.value.mapNotNull { p ->
                try {
                    node.sendEvent(p.id, event); null
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w("Ferry", "send to ${p.name} failed: ${e.text()}"); e.text()
                }
            }
            toast(app, if (errors.size < peers.value.size) done else "Couldn't reach your Mac: ${errors.first()}")
        }
    }

    /** Reached only through the Bluetooth bridge (no Wi-Fi presence). */
    fun bluetoothOnly(peerId: String) = ble.value == peerId && peerId !in nearby.value

    fun toast(ctx: Context, text: String) {
        scope.launch(Dispatchers.Main) { Toast.makeText(ctx.applicationContext, text, Toast.LENGTH_LONG).show() }
    }
}

/** Callbacks from Rust worker threads. */
class Bridge(private val ctx: Context) : FerryDelegate {
    override fun onFileEvent(event: FfiFileEvent) = Transfers.offer(event)

    /** Mac → phone events; each acts only if its grant is on. Hops to main (listener, ringer, clipboard live there). */
    override fun onEvent(peerId: String, event: FfiEvent) {
        Ferry.scope.launch(Dispatchers.Main) {
            try {
                handle(peerId, event)
            } catch (e: Exception) {
                Log.e(TAG, "event $event", e)
            }
        }
    }

    private fun handle(peerId: String, e: FfiEvent) {
        val from = Ferry.peers.value.firstOrNull { it.id == peerId }?.name ?: "your Mac"
        when (e) {
            is FfiEvent.NotificationAction -> if (Grant.NOTIFICATIONS in Grants) FerryListener.instance?.act(e)
            is FfiEvent.MediaCommand -> if (Grant.MEDIA in Grants) FerryListener.instance?.media(e.cmd)
            is FfiEvent.Clipboard -> if (Grant.CLIPBOARD in Grants) Clip.fromMac(ctx, e.text)
            is FfiEvent.Ring -> if (Grant.RING in Grants) { if (e.start) Ringer.start(ctx, from) else Ringer.stop(ctx) }
            is FfiEvent.Link -> if (Grant.LINKS in Grants && Links.isWebUrl(e.url)) Notifs.link(ctx, from, e.url)
            // Phone → Mac kinds; a Mac shouldn't send them.
            is FfiEvent.Notification, is FfiEvent.NotificationRemoved, is FfiEvent.NowPlaying, is FfiEvent.Battery -> {}
        }
    }

    override fun onScreenStream(peerId: String, stream: ScreenStream) = Screen.open(ctx, peerId, stream)

    override suspend fun confirmPairing(peerName: String, code: String): Boolean {
        val p = PairPrompt(peerName, code)
        // shortcut: one prompt at a time; a second concurrent pairing is refused.
        if (!Ferry.prompt.compareAndSet(null, p)) return false
        // No Ferry screen showing: the notification opens MainActivity, which shows the prompt.
        if (Ferry.uiVisible == 0) Notifs.pairPrompt(ctx, peerName)
        try {
            val ok = withTimeoutOrNull(120_000) { p.answer.await() } ?: false
            if (ok) {
                // shortcut: the core has no "paired" event, so re-read the list once pairing likely finished.
                Ferry.scope.launch {
                    delay(2_000)
                    Ferry.refreshPeers()
                }
            }
            return ok
        } finally {
            Ferry.prompt.compareAndSet(p, null)
            Notifs.cancel(ctx, Notifs.ID_PAIR)
        }
    }
}
