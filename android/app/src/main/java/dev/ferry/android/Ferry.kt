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
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File

const val TAG = "Ferry"

/** A line in the app's recent activity; [progress] 0..1 while a transfer runs. */
data class Recent(val id: String, val text: String, val progress: Float? = null)

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
    /** Paired peers with a live USB cable link (polled by [FerryService]). */
    val usb = MutableStateFlow<Set<String>>(emptySet())
    /** A pairing started here is in progress. */
    val pairing = MutableStateFlow(false)
    val recent = MutableStateFlow<List<Recent>>(emptyList())
    val prompt = MutableStateFlow<PairPrompt?>(null)

    /** Started MainActivity instances; written on the main thread only. */
    @Volatile var uiVisible = 0

    /** Last node shutdown, so a quick restart doesn't race the old node for the data dir. */
    @Volatile var shutdownJob: Job? = null

    fun incomingDir(ctx: Context) = File(ctx.getExternalFilesDir(null) ?: ctx.filesDir, "incoming")

    fun peerName(peerId: String) = peers.value.firstOrNull { it.id == peerId }?.name ?: "your Mac"

    fun refreshPeers() {
        peers.value = node.value?.peers() ?: emptyList()
    }

    fun put(id: String, text: String, progress: Float? = null) = recent.update { list ->
        val r = Recent(id, text, progress)
        val i = list.indexOfFirst { it.id == id }
        if (i >= 0) list.toMutableList().also { it[i] = r } else (listOf(r) + list).take(5)
    }

    /** How [peerId] is reachable right now, best first: "Wi-Fi", "USB", "Bluetooth". */
    fun links(peerId: String, nearby: Set<String>, usb: Set<String>, ble: String?) = buildList {
        if (peerId in nearby) add("Wi-Fi")
        if (peerId in usb) add("USB")
        if (peerId == ble) add("Bluetooth")
    }

    /** The running node, waiting briefly if the service is still starting it. */
    suspend fun awaitNode() = withTimeoutOrNull(30_000) { node.filterNotNull().first() }

    fun pair(ctx: Context, ticket: String) {
        val app = ctx.applicationContext
        if (!pairing.compareAndSet(false, true)) return toast(app, "Already pairing. Finish that one first.")
        toast(app, "Pairing with your Mac…")
        scope.launch {
            try {
                val node = awaitNode() ?: return@launch toast(app, NOT_RUNNING)
                catching { node.pairWithTicket(ticket) }
                    .onSuccess { refreshPeers(); toast(app, "Paired with ${it.name}") }
                    .onFailure { Log.w(TAG, "pairing failed: ${it.text()}"); toast(app, "Couldn't pair. ${it.friendly()}") }
            } finally {
                pairing.value = false
            }
        }
    }

    fun forget(ctx: Context, peer: FfiPeer) {
        try {
            node.value?.forgetPeer(peer.id)
        } catch (e: Exception) {
            Log.w(TAG, "forget ${peer.name}: ${e.text()}")
            return toast(ctx, "Couldn't forget ${peer.name}. ${e.friendly()}")
        }
        nearby.update { it - peer.id }
        usb.update { it - peer.id }
        if (ble.value == peer.id) Ble.stopBridge()
        refreshPeers()
        toast(ctx, "Forgot ${peer.name}. To use it again, pair from your Mac.")
    }

    /**
     * Opens the documents now, while the calling activity still holds the URI grant; an open
     * fd stays readable after the grant ends. Runs on IO (providers may block). Not cancellable:
     * the caller's scope ending mid-way would otherwise drop the opened fds unclosed.
     */
    suspend fun open(ctx: Context, uris: List<Uri>): List<Source> = withContext(Dispatchers.IO + NonCancellable) {
        uris.mapNotNull { uri -> openOne(ctx, uri) }
    }

    private fun openOne(ctx: Context, uri: Uri): Source? = try {
        ctx.contentResolver.openFileDescriptor(uri, "r")?.let { pfd ->
            try {
                Source(pfd, ctx.firstString(uri, OpenableColumns.DISPLAY_NAME) ?: uri.lastPathSegment)
            } catch (e: Exception) {
                pfd.close()
                throw e
            }
        }
    } catch (e: Exception) {
        Log.w(TAG, "can't open $uri", e)
        null
    }

    /**
     * Sends in [scope] so it survives leaving the UI; closes the fds when done. One notification
     * follows the send from "Sending…" to its result (see [Transfers.Outgoing]).
     */
    fun send(ctx: Context, peer: FfiPeer, files: List<Source>) {
        val app = ctx.applicationContext
        if (files.isEmpty()) return toast(app, "Couldn't open the selected files. Try picking them again.")
        if (bluetoothOnly(peer.id)) toast(app, "Connected over Bluetooth only, so big files are slow. Join the same Wi-Fi as your Mac to speed it up.")
        val out = Transfers.Outgoing(peer.name, files.map { it.name ?: "file" })
        Transfers.sending(app, out)
        scope.launch {
            try {
                val node = awaitNode() ?: error(NOT_RUNNING)
                // The core opens the path itself; /proc/self/fd/N reopens our fd.
                val r = node.sendNamed(peer.id, files.map { FfiSource("/proc/self/fd/${it.pfd.fd}", it.name) })
                val size = Formatter.formatShortFileSize(app, r.bytesSent.toLong())
                Transfers.done(app, out, "Sent ${count(r.filesSent.toInt(), "file")} to ${peer.name}", "$size in ${"%.1f".format(r.elapsedMs.toLong() / 1000.0)} s")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "send to ${peer.name} failed: ${e.text()}")
                Transfers.done(app, out, "Couldn't send to ${peer.name}", e.friendly())
            } finally {
                Transfers.end(app, out)
                files.forEach { runCatching { it.pfd.close() } }
            }
        }
    }

    /** From the file picker or share sheet: opens [uris] (while the caller holds their grant), sends, and says so. */
    suspend fun sendPicked(ctx: Context, peer: FfiPeer, uris: List<Uri>) {
        val files = open(ctx, uris)
        send(ctx, peer, files)
        if (files.isEmpty()) return
        val skipped = uris.size - files.size
        toast(ctx, "Sending ${count(files.size, "file")} to ${peer.name}…" + if (skipped > 0) " Couldn't open ${count(skipped, "other file")}." else "")
    }

    /**
     * A user-initiated event to every paired Mac. The caller is a visible activity, so it starts
     * the service; this waits for the node first. Toasts [done] once queued.
     */
    fun sendEvent(ctx: Context, event: FfiEvent, done: String) {
        val app = ctx.applicationContext
        FerryService.start(app)
        scope.launch {
            val node = awaitNode() ?: return@launch toast(app, NOT_RUNNING)
            refreshPeers()
            val macs = peers.value
            if (macs.isEmpty()) return@launch toast(app, "No Mac is paired yet. Open Ferry to pair one.")
            // The user is waiting: send directly (not via Outbox, which drops while a Mac is parked)
            // and report what really happened.
            val errors = macs.mapNotNull { p ->
                catching { node.sendEvent(p.id, event) }.exceptionOrNull()?.also { Log.w(TAG, "send to ${p.name} failed: ${it.text()}") }
            }
            toast(app, if (errors.size < macs.size) done else errors.first().friendly())
        }
    }

    /** Reached only through the Bluetooth bridge (no Wi-Fi presence). */
    fun bluetoothOnly(peerId: String) = ble.value == peerId && peerId !in nearby.value

    const val NOT_RUNNING = "Ferry isn't running. Open the app and try again."

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
        val from = Ferry.peerName(peerId)
        when (e) {
            is FfiEvent.NotificationAction -> if (Grant.NOTIFICATIONS in Grants) FerryListener.instance?.act(e)
            is FfiEvent.MediaCommand -> if (Grant.MEDIA in Grants) FerryListener.instance?.media(e.cmd)
            is FfiEvent.Clipboard -> if (Grant.CLIPBOARD in Grants) Clip.fromMac(ctx, e.text)
            is FfiEvent.Ring -> if (Grant.RING in Grants) { if (e.start) Ringer.start(ctx, from) else Ringer.stop(ctx) }
            is FfiEvent.Link -> if (Grant.LINKS in Grants && Links.isWebUrl(e.url)) Notifs.link(ctx, from, e.url)
            is FfiEvent.SmsThreadsRequest -> reply(peerId, Grant.MESSAGES) { Sms.threads(ctx, e.limit) }
            is FfiEvent.SmsMessagesRequest -> reply(peerId, Grant.MESSAGES) { Sms.messages(ctx, e.threadId, e.limit) }
            // Every Mac (the sender too) shows the sent text.
            is FfiEvent.SmsSend -> io(Grant.MESSAGES, "sms send failed") {
                Sms.send(ctx, e.address, e.body)?.let { Outbox.broadcast(FfiEvent.SmsNew(it)) }
            }
            is FfiEvent.SmsMarkRead -> io(Grant.MESSAGES, "sms mark read failed") { SmsReads.markRead(ctx, e.threadId) }
            is FfiEvent.CallCommand -> if (Grants.allowed(ctx, Grant.CALLS)) Calls.command(ctx, e.cmd)
            is FfiEvent.CallLogRequest -> reply(peerId, Grant.CALLS) { Calls.log(ctx, e.limit) }
            // Files answers with an error (not silence) when off, so the Mac can say why.
            is FfiEvent.FsListRequest -> PhoneFiles.onList(peerId, e.path)
            is FfiEvent.FsGet -> PhoneFiles.onGet(ctx, peerId, e.paths)
            is FfiEvent.WifiNetwork -> Wifi.fromMac(ctx, from, e.ssid, e.password) // Android's own dialog is the consent
            is FfiEvent.Dnd -> if (Grant.DND in Grants) Dnd.fromMac(ctx, from, e.on)
            // Phone → Mac kinds; a Mac shouldn't send them.
            is FfiEvent.Notification, is FfiEvent.NotificationRemoved, is FfiEvent.NowPlaying, is FfiEvent.Battery,
            is FfiEvent.SmsThreads, is FfiEvent.SmsMessages, is FfiEvent.SmsNew, is FfiEvent.SmsRead, is FfiEvent.Call, is FfiEvent.CallLog, is FfiEvent.FsList -> {}
        }
    }

    /** Answers a Mac's request, only while [g] is allowed; else no reply. */
    private fun reply(peerId: String, g: Grant, make: () -> FfiEvent) =
        io(g, "can't answer for ${g.short}") { Outbox.send(peerId, make()) }

    /** Runs [work] off the main thread (provider queries, SmsManager), only while [g] is allowed. */
    private fun io(g: Grant, failure: String, work: () -> Unit) {
        if (!Grants.allowed(ctx, g)) return
        Ferry.scope.launch(Dispatchers.IO) {
            try {
                work()
            } catch (e: Exception) {
                Log.w(PHONE_TAG, failure, e)
            }
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
