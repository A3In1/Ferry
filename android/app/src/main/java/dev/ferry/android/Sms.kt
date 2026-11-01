package dev.ferry.android

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.database.ContentObserver
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.BaseColumns
import android.provider.Telephony
import android.provider.Telephony.TextBasedSmsColumns as Col
import android.telephony.SmsManager
import android.telephony.SmsMessage
import android.util.Log
import androidx.core.content.edit
import dev.ferry.core.FfiEvent
import dev.ferry.core.FfiSmsMessage
import dev.ferry.core.FfiSmsThread
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.concurrent.Executors

const val PHONE_TAG = "FerryPhone"

/**
 * Texts for the Mac, read from the Telephony provider. Ferry is not the default SMS app: it reads,
 * hears SMS_RECEIVED and sends through SmsManager. Callers check [Grant.MESSAGES] first.
 */
// shortcut: SMS only, no MMS (pictures, group chats); add the mms-sms provider when the Mac needs them.
object Sms {
    /** Room under the core's 1 MiB message cap for the event envelope; per-record overheads below are protobuf worst cases. */
    const val BUDGET = (1 shl 20) - (64 shl 10)
    const val SNIPPET = 200
    private const val NOT_DRAFT = "${Col.TYPE} != ${Col.MESSAGE_TYPE_DRAFT}"

    /** Newest thread first: one pass over all texts by date, first hit per thread_id wins. */
    fun threads(ctx: Context, limit: Int): FfiEvent.SmsThreads {
        val n = rowLimit(limit, 50, 500)
        val latest = LinkedHashMap<Long, FfiSmsThread>()
        ctx.eachRow(Telephony.Sms.CONTENT_URI, arrayOf(Col.THREAD_ID, Col.ADDRESS, Col.BODY, Col.DATE), NOT_DRAFT, sort = "${Col.DATE} DESC") { c ->
            val id = c.getLong(0)
            if (id !in latest) latest[id] = FfiSmsThread(id, c.getString(1).orEmpty(), "", c.getString(2).orEmpty(), c.getLong(3), 0)
            latest.size < n
        }
        val unread = SmsReads.unread(ctx)
        val name = Names.lookup(ctx)
        val list = latest.values.map { it.copy(name = name(it.address), unread = unread[it.threadId] ?: 0) }
        return FfiEvent.SmsThreads(fitThreads(list))
    }

    /** The last [limit] texts of a thread, oldest first. */
    fun messages(ctx: Context, threadId: Long, limit: Int): FfiEvent.SmsMessages {
        val n = rowLimit(limit, 100, 1000)
        val name = Names.lookup(ctx)
        val out = ArrayList<FfiSmsMessage>()
        ctx.eachRow(
            Telephony.Sms.CONTENT_URI,
            arrayOf(BaseColumns._ID, Col.ADDRESS, Col.BODY, Col.DATE, Col.TYPE),
            "${Col.THREAD_ID} = ? AND $NOT_DRAFT", arrayOf(threadId.toString()), "${Col.DATE} DESC",
        ) { c ->
            val address = c.getString(1).orEmpty()
            out += FfiSmsMessage(c.getLong(0), threadId, address, name(address), c.getString(2).orEmpty(), c.getLong(3), outgoing(c.getInt(4)))
            out.size < n
        }
        return FfiEvent.SmsMessages(threadId, fitMessages(out.asReversed()))
    }

    /** One message per sender; a long text arrives as several parts. id 0: not in the provider yet. */
    fun received(ctx: Context, parts: Array<SmsMessage?>): List<FfiSmsMessage> {
        val name = Names.lookup(ctx)
        return parts.filterNotNull().groupBy { it.displayOriginatingAddress.orEmpty() }.map { (address, ps) ->
            FfiSmsMessage(0, threadOf(ctx, address), address, name(address), ps.joinToString("") { it.displayMessageBody.orEmpty() }, ps.first().timestampMillis, false)
        }
    }

    /**
     * Sends [body] to [address] only. As a non-default SMS app, Android writes it to the sent box
     * itself, so there is no provider insert here (it would show twice).
     */
    // shortcut: reported as sent once handed to SmsManager (no sent/delivered PendingIntents) and on the default
    // SIM; add a sentIntent + SIM choice when the Mac shows send failures.
    @SuppressLint("MissingPermission") // Grants.allowed(MESSAGES) checked SEND_SMS
    fun send(ctx: Context, address: String, body: String): FfiSmsMessage? {
        val to = address.trim()
        if (to.isEmpty() || body.isEmpty()) return null
        val sm = if (Build.VERSION.SDK_INT >= 31) ctx.getSystemService(SmsManager::class.java) else @Suppress("DEPRECATION") SmsManager.getDefault()
        val parts = sm.divideMessage(body)
        if (parts.size > 1) sm.sendMultipartTextMessage(to, null, parts, null, null) else sm.sendTextMessage(to, null, body, null, null)
        Log.w(PHONE_TAG, "sms sent, ${parts.size} part(s)")
        return FfiSmsMessage(0, threadOf(ctx, to), to, Names.of(ctx, to), body, System.currentTimeMillis(), true)
    }

    private fun threadOf(ctx: Context, address: String) =
        runCatching { Telephony.Threads.getOrCreateThreadId(ctx, address) }.getOrDefault(0L)

    fun outgoing(type: Int) = type == Col.MESSAGE_TYPE_SENT || type == Col.MESSAGE_TYPE_OUTBOX ||
        type == Col.MESSAGE_TYPE_FAILED || type == Col.MESSAGE_TYPE_QUEUED

    /** Snippets cut to [SNIPPET] chars, then the oldest threads dropped until the list fits [budget]. */
    fun fitThreads(threads: List<FfiSmsThread>, budget: Int = BUDGET): List<FfiSmsThread> {
        var total = 0
        return threads.asSequence()
            .map { if (it.snippet.length > SNIPPET) it.copy(snippet = clip(it.snippet, SNIPPET)) else it }
            .takeWhile { t -> total += 40 + utf8(t.address) + utf8(t.name) + utf8(t.snippet); total <= budget }
            .toList()
    }

    /** Oldest-first [messages]; drops the oldest until the rest fit [budget]. */
    fun fitMessages(messages: List<FfiSmsMessage>, budget: Int = BUDGET): List<FfiSmsMessage> {
        var total = 0
        val keep = messages.asReversed().takeWhile { m -> total += 48 + utf8(m.address) + utf8(m.name) + utf8(m.body); total <= budget }
        return keep.asReversed()
    }

    /** At most [n] chars, without splitting a surrogate pair. */
    fun clip(s: String, n: Int): String {
        if (s.length <= n) return s
        val end = if (n > 0 && Character.isHighSurrogate(s[n - 1])) n - 1 else n
        return s.substring(0, end) + "…"
    }

    private fun utf8(s: String) = s.encodeToByteArray().size

    /** Same rules as the Mac's: the last 10 digits match; senders without digits ("HDFCBK") compare case-insensitively. */
    fun sameNumber(a: String, b: String): Boolean {
        val x = a.filter(Char::isDigit)
        val y = b.filter(Char::isDigit)
        if (x.isEmpty() || y.isEmpty()) return a.trim().equals(b.trim(), ignoreCase = true)
        return x.takeLast(10) == y.takeLast(10)
    }
}

/**
 * Read state, both ways. Only the default SMS app may write the Telephony provider, and Ferry can't be
 * one (no SMS_DELIVER receiver), so a read on the Mac can't mark texts read in the phone's messaging
 * app. [markRead] still tries the write (it counts if Android ever allows it), then cancels the
 * messaging app's notification for that conversation and remembers when the Mac read the thread, so
 * the unread counts Ferry reports leave those texts out. Reads on the phone are seen through a
 * ContentObserver on the provider and sent to every Mac as SmsRead.
 */
object SmsReads {
    private const val PREFS = "sms_read_on_mac" // thread id -> read-on-Mac time (ms)
    private const val DEBOUNCE_MS = 1_500L
    private val exec = Executors.newSingleThreadExecutor()
    private val main by lazy { Handler(Looper.getMainLooper()) } // lazy: unit tests have no looper
    private var observer: ContentObserver? = null // main only, as is [pending]
    private var pending: Runnable? = null
    private var last: Map<Long, Int>? = null // exec only: the provider's unread per thread at the last look

    /** Watches the provider while the service runs; call on the main thread. */
    fun start(svc: Context) {
        if (observer != null) return
        val c = svc.applicationContext
        val check = Runnable { exec.execute { safely { look(c) } } }
        val o = object : ContentObserver(main) {
            override fun onChange(selfChange: Boolean) {
                main.removeCallbacks(check)
                main.postDelayed(check, DEBOUNCE_MS) // a read marks each text: one look per burst
            }
        }
        try {
            c.contentResolver.registerContentObserver(Telephony.Sms.CONTENT_URI, true, o)
        } catch (e: SecurityException) {
            Log.w(PHONE_TAG, "can't watch sms read state", e)
            return
        }
        observer = o
        pending = check
        check.run() // the baseline to compare against
    }

    fun stop(svc: Context) {
        observer?.let { svc.applicationContext.contentResolver.unregisterContentObserver(it) }
        observer = null
        pending?.let(main::removeCallbacks)
        pending = null
        exec.execute { last = null }
    }

    private fun look(ctx: Context) {
        if (!Grants.allowed(ctx, Grant.MESSAGES)) {
            last = null
            return
        }
        val now = counts(unreadRows(ctx))
        val read = last?.let { readSince(it, now) }.orEmpty()
        last = now
        if (read.isEmpty()) return
        forget(ctx, read)
        Log.w(PHONE_TAG, "${read.size} sms thread(s) read on the phone")
        read.forEach { Outbox.broadcast(FfiEvent.SmsRead(it)) }
    }

    /** From a Mac: the user read [threadId] there. */
    fun markRead(ctx: Context, threadId: Long) {
        val args = arrayOf(threadId.toString())
        val written = try {
            val read = ContentValues().apply { put(Col.READ, 1); put(Col.SEEN, 1) }
            ctx.contentResolver.update(Telephony.Sms.Inbox.CONTENT_URI, read, "${Col.THREAD_ID} = ? AND ${Col.READ} = 0", args)
        } catch (e: SecurityException) {
            -1 // not the default SMS app: expected
        }
        // Even if the write took, the mark is harmless: it goes once the provider has nothing unread there.
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit { putLong(threadId.toString(), System.currentTimeMillis()) }
        var address = ""
        ctx.eachRow(Telephony.Sms.CONTENT_URI, arrayOf(Col.ADDRESS), "${Col.THREAD_ID} = ?", args, "${Col.DATE} DESC") { c ->
            address = c.getString(0).orEmpty()
            false
        }
        val name = Names.of(ctx, address)
        val pkg = Telephony.Sms.getDefaultSmsPackage(ctx)
        Log.w(PHONE_TAG, "sms thread read on a Mac; provider write: ${if (written < 0) "refused" else "$written row(s)"}")
        if (address.isNotEmpty() && pkg != null) main.post { FerryListener.instance?.cancelConversation(pkg, name, address) }
        Outbox.broadcast(FfiEvent.SmsRead(threadId)) // every Mac clears its badge, this one too
    }

    /** Unread per thread for the Mac (texts newer than the thread's read-on-Mac mark); drops marks the phone caught up with. */
    fun unread(ctx: Context): Map<Long, Int> {
        val rows = unreadRows(ctx)
        val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val marks = prefs.all.mapNotNull { (k, v) -> (v as? Long)?.let { t -> k.toLongOrNull()?.let { it to t } } }.toMap()
        val stale = marks.keys - rows.mapTo(HashSet()) { it.first }
        if (stale.isNotEmpty()) forget(ctx, stale)
        return countUnread(rows, marks)
    }

    private fun forget(ctx: Context, threads: Collection<Long>) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit { threads.forEach { remove(it.toString()) } }
    }

    /** (thread id, date) of every unread inbox text. */
    private fun unreadRows(ctx: Context) = ArrayList<Pair<Long, Long>>().also { out ->
        ctx.eachRow(Telephony.Sms.Inbox.CONTENT_URI, arrayOf(Col.THREAD_ID, Col.DATE), "${Col.READ} = 0") { c ->
            out += c.getLong(0) to c.getLong(1)
            true
        }
    }

    fun counts(rows: List<Pair<Long, Long>>): Map<Long, Int> = rows.groupingBy { it.first }.eachCount()

    /** Rows newer than their thread's read-on-Mac mark, counted per thread. */
    fun countUnread(rows: List<Pair<Long, Long>>, readOnMac: Map<Long, Long>): Map<Long, Int> =
        counts(rows.filter { (t, date) -> date > (readOnMac[t] ?: Long.MIN_VALUE) })

    /** Threads that had unread texts and now have none. */
    fun readSince(prev: Map<Long, Int>, now: Map<Long, Int>): List<Long> = prev.keys.filter { (now[it] ?: 0) == 0 }

    private inline fun safely(work: () -> Unit) = try {
        work()
    } catch (e: Exception) {
        Log.w(PHONE_TAG, "sms read state", e)
    }
}

/** Manifest receiver (protected by BROADCAST_SMS): pushes each incoming text to every paired Mac. */
// shortcut: only reaches a Mac while Ferry's service is running (no node otherwise, and the SMS broadcast
// doesn't allow a background FGS start); queue texts for the next connection if that matters.
class SmsReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION || !Grants.allowed(ctx, Grant.MESSAGES)) return
        val parts = Telephony.Sms.Intents.getMessagesFromIntent(intent) ?: return
        val pending = goAsync()
        Ferry.scope.launch(Dispatchers.IO) {
            try {
                val ms = Sms.received(ctx.applicationContext, parts)
                Log.w(PHONE_TAG, "sms received, ${ms.size} sender(s), ${Ferry.peers.value.size} Mac(s)")
                ms.forEach { Outbox.broadcast(FfiEvent.SmsNew(it)) }
            } catch (e: Exception) {
                Log.w(PHONE_TAG, "incoming sms", e)
            } finally {
                pending.finish()
            }
        }
    }
}
