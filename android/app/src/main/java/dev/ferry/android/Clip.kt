package dev.ferry.android

import android.app.Activity
import android.app.PendingIntent
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.service.quicksettings.TileService
import dev.ferry.core.FfiEvent

object Clip {
    /** Last text the Mac put on our clipboard, so sending it back is skipped. */
    @Volatile private var fromMac: String? = null

    fun fromMac(ctx: Context, text: String) {
        fromMac = text
        ctx.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("From Mac", text))
    }

    /** From a visible activity: sends [text] to the Macs if the grant allows, and toasts the outcome. */
    fun send(ctx: Context, text: String?) {
        val why = when {
            Grant.CLIPBOARD !in Grants -> "Turn on Clipboard sync in Ferry first"
            text.isNullOrEmpty() -> "Clipboard is empty"
            text == fromMac -> "That came from your Mac"
            else -> null
        }
        if (why != null) return Ferry.toast(ctx, why)
        Ferry.sendEvent(ctx, FfiEvent.Clipboard(text ?: return), "Clipboard sent to Mac")
    }

    fun openIntent(ctx: Context) = Intent(ctx, ClipActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    fun pendingIntent(ctx: Context): PendingIntent =
        PendingIntent.getActivity(ctx, 1, openIntent(ctx), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
}

/**
 * Invisible activity that reads the clipboard: Android 10+ only lets the focused app read it,
 * so this waits for window focus, sends, and closes.
 */
class ClipActivity : Activity() {
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (!hasFocus || isFinishing) return
        val text = getSystemService(ClipboardManager::class.java).primaryClip
            ?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(this)?.toString()
        Clip.send(this, text)
        finish()
    }
}

/** Quick Settings tile: "Send clipboard". */
class ClipTile : TileService() {
    override fun onClick() {
        if (Build.VERSION.SDK_INT >= 34) {
            startActivityAndCollapse(Clip.pendingIntent(this))
        } else {
            @Suppress("DEPRECATION", "StartActivityAndCollapseDeprecated")
            startActivityAndCollapse(Clip.openIntent(this))
        }
    }
}
