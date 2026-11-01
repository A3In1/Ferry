package dev.ferry.android

import android.Manifest
import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * What the phone shares with / allows from paired Macs. Every grant starts OFF (Build_Plan §2).
 * [label] and [why] are the switch's text; [short] lists it in the ongoing notification.
 */
enum class Grant(val key: String, val label: String, val short: String, val why: String, val permissions: List<String> = emptyList()) {
    NOTIFICATIONS("notifications", "Notifications", "Notifications", "Show this phone's notifications on your Mac, and reply or dismiss them there"),
    MEDIA("media", "Media controls", "Media", "See what's playing here and play, pause or skip from your Mac"),
    CLIPBOARD("clipboard", "Clipboard", "Clipboard", "Text you copy on your Mac lands here. To send this phone's clipboard, tap Send clipboard"),
    BATTERY("battery", "Battery level", "Battery", "Show this phone's battery level on your Mac"),
    RING("ring", "Find my phone", "Find my phone", "Let your Mac ring this phone at full volume, even on silent"),
    LINKS("links", "Links", "Links", "Open links from your Mac with one tap, and share links from here to your Mac"),
    DND("dnd", "Do Not Disturb", "Do Not Disturb", "Turning Do Not Disturb on or off on one does the same on the other"),
    MESSAGES(
        "messages", "Messages", "Messages", "Read your texts on your Mac and send replies you type there",
        listOf(Manifest.permission.READ_SMS, Manifest.permission.RECEIVE_SMS, Manifest.permission.SEND_SMS, Manifest.permission.READ_CONTACTS),
    ),
    CALLS(
        "calls", "Calls", "Calls", "See incoming calls on your Mac, answer or decline there, and see recent calls",
        listOf(Manifest.permission.READ_PHONE_STATE, Manifest.permission.READ_CALL_LOG, Manifest.permission.ANSWER_PHONE_CALLS, Manifest.permission.READ_CONTACTS),
    ),
    /** Needs All files access (a special access, not a runtime permission; see [PhoneFiles]). */
    FILES("files", "Files", "Files", "Let your Mac browse this phone's storage and copy files from it"),
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

    /** On, and Android still grants its runtime permissions / special access (they can be revoked in Settings). */
    fun allowed(ctx: Context, g: Grant) =
        g in this && g.permissions.all(ctx::granted) && (g != Grant.FILES || PhoneFiles.accessGranted())

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

fun Context.granted(permission: String) = ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED
