package dev.ferry.android

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.BaseColumns
import android.provider.Telephony
import android.provider.Telephony.TextBasedSmsColumns as Col
import android.telephony.SmsManager
import android.telephony.SmsMessage
import android.util.Log
import dev.ferry.core.FfiEvent
import dev.ferry.core.FfiSmsMessage
import dev.ferry.core.FfiSmsThread
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

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
        val n = if (limit <= 0) 50 else limit.coerceAtMost(500)
        val latest = LinkedHashMap<Long, FfiSmsThread>()
        ctx.contentResolver.query(
            Telephony.Sms.CONTENT_URI, arrayOf(Col.THREAD_ID, Col.ADDRESS, Col.BODY, Col.DATE), NOT_DRAFT, null, "${Col.DATE} DESC",
        )?.use { c ->
            while (latest.size < n && c.moveToNext()) {
                val id = c.getLong(0)
                if (id !in latest) latest[id] = FfiSmsThread(id, c.getString(1).orEmpty(), "", c.getString(2).orEmpty(), c.getLong(3), 0)
            }
        }
        val unread = HashMap<Long, Int>()
        ctx.contentResolver.query(Telephony.Sms.Inbox.CONTENT_URI, arrayOf(Col.THREAD_ID), "${Col.READ} = 0", null, null)?.use { c ->
            while (c.moveToNext()) unread.merge(c.getLong(0), 1, Int::plus)
        }
        val list = latest.values.map { it.copy(name = Names.of(ctx, it.address), unread = unread[it.threadId] ?: 0) }
        return FfiEvent.SmsThreads(fitThreads(list))
    }

    /** The last [limit] texts of a thread, oldest first. */
    fun messages(ctx: Context, threadId: Long, limit: Int): FfiEvent.SmsMessages {
        val n = if (limit <= 0) 100 else limit.coerceAtMost(1000)
        val out = ArrayList<FfiSmsMessage>()
        ctx.contentResolver.query(
            Telephony.Sms.CONTENT_URI,
            arrayOf(BaseColumns._ID, Col.ADDRESS, Col.BODY, Col.DATE, Col.TYPE),
            "${Col.THREAD_ID} = ? AND $NOT_DRAFT", arrayOf(threadId.toString()), "${Col.DATE} DESC",
        )?.use { c ->
            while (out.size < n && c.moveToNext()) {
                val address = c.getString(1).orEmpty()
                out += FfiSmsMessage(c.getLong(0), threadId, address, Names.of(ctx, address), c.getString(2).orEmpty(), c.getLong(3), outgoing(c.getInt(4)))
            }
        }
        return FfiEvent.SmsMessages(threadId, fitMessages(out.asReversed()))
    }

    /** One message per sender; a long text arrives as several parts. id 0: not in the provider yet. */
    fun received(ctx: Context, parts: Array<SmsMessage?>): List<FfiSmsMessage> =
        parts.filterNotNull().groupBy { it.displayOriginatingAddress.orEmpty() }.map { (address, ps) ->
            FfiSmsMessage(0, threadOf(ctx, address), address, Names.of(ctx, address), ps.joinToString("") { it.displayMessageBody.orEmpty() }, ps.first().timestampMillis, false)
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
