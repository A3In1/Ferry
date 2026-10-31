package dev.ferry.android

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow

/** What the phone shares with / allows from paired Macs. Every grant starts OFF (Build_Plan §2). */
enum class Grant(val key: String, val label: String, val short: String) {
    NOTIFICATIONS("notifications", "Notifications to Mac", "Notifications"),
    MEDIA("media", "Media controls", "Media"),
    CLIPBOARD("clipboard", "Clipboard sync", "Clipboard"),
    BATTERY("battery", "Battery status", "Battery"),
    RING("ring", "Allow Mac to ring phone", "Ring"),
    LINKS("links", "Links", "Links"),
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

    fun set(ctx: Context, g: Grant, enabled: Boolean) {
        prefs.edit { putBoolean(g.key, enabled) }
        on.value = read(prefs::getBoolean)
        if (!enabled) revoked(ctx, g) else if (g == Grant.MEDIA) FerryListener.instance?.refreshMedia()
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
            else -> {}
        }
    }
}
