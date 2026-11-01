package dev.ferry.android

import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.util.Log
import dev.ferry.core.FfiEvent

/** Do Not Disturb sync, both ways, behind the DND grant. */
object Dnd {
    /** Until then, filter changes are ours (applied for a Mac) and aren't pushed back. */
    @Volatile private var quietUntil = 0L

    /** Registered by [FerryService] while it runs. */
    val changed = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, i: Intent) {
            if (Grant.DND !in Grants || SystemClock.elapsedRealtime() < quietUntil) return
            val on = ctx.getSystemService(NotificationManager::class.java).currentInterruptionFilter != NotificationManager.INTERRUPTION_FILTER_ALL
            Log.w(FILES_TAG, "dnd on=$on → Macs")
            Outbox.broadcast(FfiEvent.Dnd(on))
        }
    }

    fun fromMac(ctx: Context, from: String, on: Boolean) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        if (!nm.isNotificationPolicyAccessGranted) return Notifs.dndAsk(ctx, from, on)
        quietUntil = SystemClock.elapsedRealtime() + 2_000
        nm.setInterruptionFilter(if (on) NotificationManager.INTERRUPTION_FILTER_PRIORITY else NotificationManager.INTERRUPTION_FILTER_ALL)
        Log.w(FILES_TAG, "dnd on=$on from Mac")
    }
}
