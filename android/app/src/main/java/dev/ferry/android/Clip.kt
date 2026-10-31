package dev.ferry.android

import android.app.Activity
import android.app.PendingIntent
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import android.service.quicksettings.TileService
import dev.ferry.core.FfiEvent

private const val CLIP_TAG = "FerryClip"

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
        Log.i(CLIP_TAG, "send clipboard: ${why ?: "ok"}")
        if (why != null) return Ferry.toast(ctx, why)
        Ferry.sendEvent(ctx, FfiEvent.Clipboard(text ?: return), "Clipboard sent to Mac")
    }

    const val EXTRA_SEND = "dev.ferry.android.SEND_CLIPBOARD"

    /** First clipboard item as text; only works while one of our windows has focus. */
    fun read(ctx: Context): String? = ctx.getSystemService(ClipboardManager::class.java).primaryClip
        ?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(ctx)?.toString()?.takeIf { it.isNotEmpty() }

    fun openIntent(ctx: Context) = Intent(ctx, ClipActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    /** For the notification button: the main screen (some OEMs, e.g. vivo, block invisible activities
     * started from notifications), which reads the clipboard on focus and steps back. */
    fun pendingIntent(ctx: Context): PendingIntent = PendingIntent.getActivity(
        ctx, 1,
        Intent(ctx, MainActivity::class.java).putExtra(EXTRA_SEND, true)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )
}

/**
 * Invisible activity that reads the clipboard: Android 10+ only lets the focused app read it,
 * so this waits for window focus, sends, and closes.
 */
class ClipActivity : Activity() {
    private var done = false

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        Log.i(CLIP_TAG, "clip activity focus=$hasFocus")
        if (!hasFocus || isFinishing || done) return
        done = true
        // Some OEM builds grant clipboard access a moment after focus: retry once if empty.
        val text = read() ?: return window.decorView.postDelayed({ finishWith(read()) }, 300).let {}
        finishWith(text)
    }

    private fun read(): String? = Clip.read(this)

    private fun finishWith(text: String?) {
        Log.i(CLIP_TAG, "clip activity read ${text?.length ?: -1} chars")
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
