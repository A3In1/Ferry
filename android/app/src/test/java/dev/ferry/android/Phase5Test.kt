package dev.ferry.android

import android.provider.CallLog
import android.provider.Telephony.TextBasedSmsColumns as Col
import android.telephony.TelephonyManager
import dev.ferry.core.FfiCallKind
import dev.ferry.core.FfiCallState
import dev.ferry.core.FfiSmsMessage
import dev.ferry.core.FfiSmsThread
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Phase5Test {
    private fun thread(id: Long, snippet: String) = FfiSmsThread(id, "+15550100", "Ann", snippet, 1_000 - id, 0)
    private fun msg(id: Long, body: String) = FfiSmsMessage(id, 1, "+15550100", "Ann", body, id, false)

    @Test
    fun newGrantsOffWithPermissions() {
        assertEquals(emptySet<Grant>(), Grants.read { _, d -> d })
        assertTrue(Grant.MESSAGES.permissions.containsAll(listOf("android.permission.READ_SMS", "android.permission.SEND_SMS")))
        assertTrue("android.permission.ANSWER_PHONE_CALLS" in Grant.CALLS.permissions)
        // MainActivity tells the two requests apart by their full permission sets.
        assertFalse(Grant.CALLS.permissions.containsAll(Grant.MESSAGES.permissions))
        assertFalse(Grant.MESSAGES.permissions.containsAll(Grant.CALLS.permissions))
    }

    @Test
    fun threadsFitTheBudget() {
        val long = "x".repeat(5_000)
        val fit = Sms.fitThreads((1L..50).map { thread(it, long) })
        assertEquals(50, fit.size)
        assertTrue(fit.all { it.snippet.length == Sms.SNIPPET + 1 && it.snippet.endsWith("…") })
        assertEquals((1L..50).toList(), fit.map { it.threadId }) // order kept, newest first
        // Over budget: the oldest (tail) go.
        val small = Sms.fitThreads((1L..50).map { thread(it, "hi") }, budget = 10 * (40 + 9 + 3 + 2))
        assertEquals((1L..10).toList(), small.map { it.threadId })
        // Real-size worst case stays under the core's 1 MiB cap.
        val many = Sms.fitThreads((1L..10_000).map { thread(it, "é".repeat(400)) })
        assertTrue(many.sumOf { 40 + it.snippet.encodeToByteArray().size + it.address.length + it.name.length } < (1 shl 20))
    }

    @Test
    fun messagesDropOldest() {
        val ms = (1L..100).map { msg(it, "y".repeat(1_000)) }
        assertEquals(ms, Sms.fitMessages(ms))
        val fit = Sms.fitMessages(ms, budget = 10 * (48 + 9 + 3 + 1_000))
        assertEquals((91L..100).toList(), fit.map { it.id }) // newest kept, still oldest first
    }

    @Test
    fun clipKeepsSurrogatePairs() {
        assertEquals("abc", Sms.clip("abc", 5))
        assertEquals("ab…", Sms.clip("abcdef", 2))
        assertEquals("a…", Sms.clip("a😀b", 2)) // would split the emoji
        assertEquals("a😀…", Sms.clip("a😀b", 3))
    }

    @Test
    fun smsTypes() {
        assertFalse(Sms.outgoing(Col.MESSAGE_TYPE_INBOX))
        assertTrue(Sms.outgoing(Col.MESSAGE_TYPE_SENT))
        assertTrue(Sms.outgoing(Col.MESSAGE_TYPE_OUTBOX))
        assertTrue(Sms.outgoing(Col.MESSAGE_TYPE_FAILED))
        assertTrue(Sms.outgoing(Col.MESSAGE_TYPE_QUEUED))
    }

    @Test
    fun callKinds() {
        assertEquals(FfiCallKind.INCOMING, Calls.kindOf(CallLog.Calls.INCOMING_TYPE))
        assertEquals(FfiCallKind.INCOMING, Calls.kindOf(CallLog.Calls.ANSWERED_EXTERNALLY_TYPE))
        assertEquals(FfiCallKind.OUTGOING, Calls.kindOf(CallLog.Calls.OUTGOING_TYPE))
        assertEquals(FfiCallKind.MISSED, Calls.kindOf(CallLog.Calls.MISSED_TYPE))
        assertEquals(FfiCallKind.MISSED, Calls.kindOf(CallLog.Calls.REJECTED_TYPE))
        assertEquals(FfiCallKind.MISSED, Calls.kindOf(CallLog.Calls.BLOCKED_TYPE))
        assertEquals(FfiCallState.RINGING, Calls.stateOf(TelephonyManager.CALL_STATE_RINGING))
        assertEquals(FfiCallState.ACTIVE, Calls.stateOf(TelephonyManager.CALL_STATE_OFFHOOK))
        assertEquals(FfiCallState.IDLE, Calls.stateOf(TelephonyManager.CALL_STATE_IDLE))
    }

    @Test
    fun callNumberCarriesThroughTheCall() {
        val idle = CallSnap(FfiCallState.IDLE, "")
        val ringing = Calls.next(idle, FfiCallState.RINGING, null) // TelephonyCallback first: no number
        assertEquals(CallSnap(FfiCallState.RINGING, ""), ringing)
        val withNumber = Calls.next(ringing, FfiCallState.RINGING, "+15550100") // then the broadcast
        assertEquals(CallSnap(FfiCallState.RINGING, "+15550100"), withNumber)
        assertEquals(withNumber, Calls.next(withNumber, FfiCallState.RINGING, null)) // duplicate: no new push
        val active = Calls.next(withNumber, FfiCallState.ACTIVE, null)
        assertEquals(CallSnap(FfiCallState.ACTIVE, "+15550100"), active)
        assertEquals(idle, Calls.next(active, FfiCallState.IDLE, "+15550100"))
        assertEquals(CallSnap(FfiCallState.ACTIVE, ""), Calls.next(idle, FfiCallState.ACTIVE, null)) // outgoing: unknown
    }

    @Test
    fun readOnMacLeavesOlderTextsOut() {
        val rows = listOf(1L to 100L, 1L to 200L, 2L to 50L, 3L to 10L)
        assertEquals(mapOf(1L to 2, 2L to 1, 3L to 1), SmsReads.countUnread(rows, emptyMap()))
        // thread 1 read on the Mac at 150: only the text after that counts; thread 2 fully read
        assertEquals(mapOf(1L to 1, 3L to 1), SmsReads.countUnread(rows, mapOf(1L to 150L, 2L to 50L)))
    }

    @Test
    fun phoneReadsAreThreadsThatWentToZero() {
        assertEquals(listOf(2L), SmsReads.readSince(mapOf(1L to 3, 2L to 1), mapOf(1L to 2, 4L to 1)))
        assertEquals(emptyList<Long>(), SmsReads.readSince(emptyMap(), mapOf(1L to 1)))
    }

    @Test
    fun conversationNotificationsMatchByNameOrNumber() {
        assertTrue(FerryListener.isConversation(listOf("Ann"), "ann", "+15550100"))
        assertTrue(FerryListener.isConversation(listOf("(555) 555-0100"), "", "+1 555 555 0100"))
        assertTrue(FerryListener.isConversation(listOf("HDFCBK"), "", "hdfcbk"))
        assertFalse(FerryListener.isConversation(listOf("Bob"), "Ann", "+15550100"))
        assertFalse(FerryListener.isConversation(listOf("2 new messages"), "", "+15550100"))
        assertFalse(FerryListener.isConversation(listOf("Ann"), "", ""))
    }
}
