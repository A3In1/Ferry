package dev.ferry.android

import android.Manifest
import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow

/** What the phone shares with / allows from paired Macs. Every grant starts OFF (Build_Plan §2). */
enum class Grant(val key: String, val label: String, val short: String, val why: String = "", val permissions: List<String> = emptyList()) {
    NOTIFICATIONS("notifications", "Notifications to Mac", "Notifications"),
    MEDIA("media", "Media controls", "Media"),
    CLIPBOARD("clipboard", "Clipboard sync", "Clipboard"),
    BATTERY("battery", "Battery status", "Battery"),
    RING("ring", "Allow Mac to ring phone", "Ring"),
    LINKS("links", "Links", "Links"),
    MESSAGES(
        "messages", "Messages", "Messages", "Show your texts on your Mac and send replies you type there",
        listOf(Manifest.permission.READ_SMS, Manifest.permission.RECEIVE_SMS, Manifest.permission.SEND_SMS, Manifest.permission.READ_CONTACTS),
    ),
    CALLS(
        "calls", "Calls", "Calls", "Show incoming calls on your Mac, answer or decline from there, and see recent calls",
        listOf(Manifest.permission.READ_PHONE_STATE, Manifest.permission.READ_CALL_LOG, Manifest.permission.ANSWER_PHONE_CALLS, Manifest.permission.READ_CONTACTS),
    ),
}

// shortcut: grants are global, not per Mac, and don't expire after 30 days unused; make them
// per-device (in the trust record) when multi-Mac matters.
object Grants {
    private lateinit var prefs: SharedPreferences
    val on = MutableStateFlow<Set<Grant>>(emptySet())

    /** Unset keys read as off. */
    fun read(get: (key: String, default: Boolean) -> Boolean): Set<Grant> =
        Grant.entries.filter { get(it.key, false) }.toSet()

    fun init(ctx: Context) {
        prefs = ctx.getSharedPreferences("grants", Context.MODE_PRIVATE)
        on.value = read(prefs::getBoolean)
    }

    operator fun contains(g: Grant) = g in on.value

    /** On, and Android still grants its runtime permissions (they can be revoked in Settings). */
    fun allowed(ctx: Context, g: Grant) =
        g in this && g.permissions.all { ContextCompat.checkSelfPermission(ctx, it) == PackageManager.PERMISSION_GRANTED }

    fun set(ctx: Context, g: Grant, enabled: Boolean) {
        prefs.edit { putBoolean(g.key, enabled) }
        on.value = read(prefs::getBoolean)
        if (!enabled) revoked(ctx, g) else when (g) {
            Grant.MEDIA -> FerryListener.instance?.refreshMedia()
            Grant.CALLS -> FerryService.instance?.let(Calls::sync)
            else -> {}
        }
    }

    fun allOff(ctx: Context) {
        val was = on.value
        prefs.edit { Grant.entries.forEach { putBoolean(it.key, false) } }
        on.value = emptySet()
        was.forEach { revoked(ctx, it) }
    }

    private fun revoked(ctx: Context, g: Grant) {
        when (g) {
            Grant.RING -> Ringer.stop(ctx)
            Grant.MEDIA -> Outbox.broadcast(FerryListener.CLEARED) // clear the Mac's stale now-playing
            Grant.CALLS -> FerryService.instance?.let(Calls::sync)
            else -> {}
        }
    }
}
