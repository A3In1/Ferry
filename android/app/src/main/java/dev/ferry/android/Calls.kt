package dev.ferry.android

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Build
import android.provider.CallLog
import android.telecom.TelecomManager
import android.telephony.TelephonyCallback
import android.telephony.TelephonyManager
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import dev.ferry.core.FfiCallCmd
import dev.ferry.core.FfiCallEntry
import dev.ferry.core.FfiCallKind
import dev.ferry.core.FfiCallState
import dev.ferry.core.FfiEvent
import java.util.concurrent.Executors

data class CallSnap(val state: FfiCallState, val number: String)

/**
 * Call state for the Mac while [Grant.CALLS] is on, registered from [FerryService]. TelephonyCallback
 * (API 31+) gives the state; the PHONE_STATE broadcast adds the ringing number (with READ_CALL_LOG).
 * Call audio stays on the phone.
 */
object Calls {
    /** State updates, name lookups and pushes run here, in order. */
    private val exec = Executors.newSingleThreadExecutor()
    private var snap = CallSnap(FfiCallState.IDLE, "") // exec only
    private var receiver: BroadcastReceiver? = null // main only, as is [callback]
    private var callback: Any? = null

    /** Registers or unregisters to match the grant; call on the main thread. */
    fun sync(svc: Context) {
        val want = Grants.allowed(svc, Grant.CALLS)
        if (want == (receiver != null)) return
        if (want) start(svc) else stop(svc)
    }

    private fun start(svc: Context) {
        val r = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                val state = stateOf(i.getStringExtra(TelephonyManager.EXTRA_STATE)) ?: return
                @Suppress("DEPRECATION") val number = i.getStringExtra(TelephonyManager.EXTRA_INCOMING_NUMBER)
                exec.execute { update(c, state, number) }
            }
        }
        ContextCompat.registerReceiver(svc, r, IntentFilter(TelephonyManager.ACTION_PHONE_STATE_CHANGED), ContextCompat.RECEIVER_NOT_EXPORTED)
        receiver = r
        if (Build.VERSION.SDK_INT >= 31) callback = register(svc)
    }

    @RequiresApi(31)
    @SuppressLint("MissingPermission") // Grants.allowed(CALLS) checked READ_PHONE_STATE
    private fun register(svc: Context): TelephonyCallback {
        val cb = object : TelephonyCallback(), TelephonyCallback.CallStateListener {
            override fun onCallStateChanged(state: Int) = update(svc, stateOf(state), null)
        }
        svc.getSystemService(TelephonyManager::class.java).registerTelephonyCallback(exec, cb)
        return cb
    }

    fun stop(svc: Context) {
        receiver?.let { runCatching { svc.unregisterReceiver(it) } }
        receiver = null
        if (Build.VERSION.SDK_INT >= 31) (callback as? TelephonyCallback)?.let { svc.getSystemService(TelephonyManager::class.java).unregisterTelephonyCallback(it) }
        callback = null
        exec.execute { snap = CallSnap(FfiCallState.IDLE, "") }
    }

    private fun update(ctx: Context, state: FfiCallState, number: String?) {
        val n = next(snap, state, number)
        if (n == snap) return
        snap = n
        Log.w(PHONE_TAG, "call ${n.state}")
        if (Grant.CALLS in Grants) Outbox.broadcast(FfiEvent.Call(n.state, n.number, Names.of(ctx, n.number)))
    }

    /** A state without a number keeps the current call's number; idle forgets it. */
    fun next(prev: CallSnap, state: FfiCallState, number: String?): CallSnap = when {
        state == FfiCallState.IDLE -> CallSnap(state, "")
        !number.isNullOrEmpty() -> CallSnap(state, number)
        else -> CallSnap(state, if (prev.state == FfiCallState.IDLE) "" else prev.number)
    }

    fun stateOf(state: Int) = when (state) {
        TelephonyManager.CALL_STATE_RINGING -> FfiCallState.RINGING
        TelephonyManager.CALL_STATE_OFFHOOK -> FfiCallState.ACTIVE
        else -> FfiCallState.IDLE
    }

    private fun stateOf(extra: String?) = when (extra) {
        TelephonyManager.EXTRA_STATE_RINGING -> FfiCallState.RINGING
        TelephonyManager.EXTRA_STATE_OFFHOOK -> FfiCallState.ACTIVE
        TelephonyManager.EXTRA_STATE_IDLE -> FfiCallState.IDLE
        else -> null
    }

    // shortcut: acceptRingingCall/endCall are deprecated since API 29 but still work for a non-dialer app;
    // move to an InCallService (dialer/companion role) if Android removes them.
    @SuppressLint("MissingPermission") // Grants.allowed(CALLS) checked ANSWER_PHONE_CALLS
    @Suppress("DEPRECATION")
    fun command(ctx: Context, cmd: FfiCallCmd) {
        val tm = ctx.getSystemService(TelecomManager::class.java)
        Log.w(PHONE_TAG, "call command $cmd")
        when (cmd) {
            FfiCallCmd.ANSWER -> tm.acceptRingingCall()
            FfiCallCmd.DECLINE -> tm.endCall()
        }
    }

    /** Placed by Android's own phone app, which shows the call as usual. */
    @SuppressLint("MissingPermission") // Grants.allowed(CALLS) checked CALL_PHONE
    fun dial(ctx: Context, number: String) {
        val to = number.trim()
        if (to.isEmpty()) return
        Log.w(PHONE_TAG, "placing a call for a Mac")
        runCatching { ctx.getSystemService(TelecomManager::class.java).placeCall(Uri.fromParts("tel", to, null), null) }
            .onFailure { Log.w(PHONE_TAG, "couldn't place the call", it) }
    }

    /** Newest first. */
    fun log(ctx: Context, limit: Int): FfiEvent.CallLog {
        val n = rowLimit(limit, 50, 500)
        val names = Names.lookup(ctx)
        val out = ArrayList<FfiCallEntry>()
        ctx.eachRow(
            CallLog.Calls.CONTENT_URI,
            arrayOf(CallLog.Calls.NUMBER, CallLog.Calls.CACHED_NAME, CallLog.Calls.DATE, CallLog.Calls.DURATION, CallLog.Calls.TYPE),
            sort = "${CallLog.Calls.DATE} DESC",
        ) { c ->
            val number = c.getString(0).orEmpty()
            val name = c.getString(1)?.takeIf { it.isNotBlank() } ?: names(number)
            out += FfiCallEntry(number, name, c.getLong(2), c.getLong(3).coerceAtMost(Int.MAX_VALUE.toLong()).toInt(), kindOf(c.getInt(4)))
            out.size < n
        }
        return FfiEvent.CallLog(out)
    }

    fun kindOf(type: Int) = when (type) {
        CallLog.Calls.OUTGOING_TYPE -> FfiCallKind.OUTGOING
        CallLog.Calls.MISSED_TYPE, CallLog.Calls.REJECTED_TYPE, CallLog.Calls.BLOCKED_TYPE, CallLog.Calls.VOICEMAIL_TYPE -> FfiCallKind.MISSED
        else -> FfiCallKind.INCOMING // INCOMING, ANSWERED_EXTERNALLY, OEM types
    }
}
