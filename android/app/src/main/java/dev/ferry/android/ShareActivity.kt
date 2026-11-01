package dev.ferry.android

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.core.content.IntentCompat
import androidx.lifecycle.lifecycleScope
import dev.ferry.core.FfiEvent
import dev.ferry.core.FfiPeer
import kotlinx.coroutines.launch

/** Share-sheet target ("Send to Mac"): pick a paired Mac, hand the files to the core, close. */
class ShareActivity : ComponentActivity() {
    private var sending = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val uris = sharedUris(intent)
        if (uris.isEmpty()) {
            shareText(intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString())
            finish()
            return
        }
        FerryService.start(this) // we're visible, so this is allowed
        setContent {
            FerryTheme {
                PeerPicker(uris.size, onDismiss = ::finish) { send(it, uris) }
            }
        }
    }

    private fun send(peer: FfiPeer, uris: List<Uri>) {
        if (sending) return
        sending = true
        lifecycleScope.launch {
            // Open while we still hold the share's URI grant.
            val files = Ferry.open(this@ShareActivity, uris)
            Ferry.send(this@ShareActivity, peer, files)
            if (files.isNotEmpty()) Ferry.toast(this@ShareActivity, "Sending to ${peer.name}…")
            finish()
        }
    }

    /** Shared text: a lone web URL goes as a Link (opens on the Mac), anything else as Clipboard. */
    private fun shareText(text: String?) {
        if (intent.action != Intent.ACTION_SEND || text.isNullOrBlank()) return Ferry.toast(this, "Nothing to send")
        val url = Links.sharedUrl(text)
        val (grant, event) = if (url != null) Grant.LINKS to FfiEvent.Link(url) else Grant.CLIPBOARD to FfiEvent.Clipboard(text)
        if (grant !in Grants) return Ferry.toast(this, "Turn on “${grant.label}” in Ferry to send this")
        Ferry.sendEvent(this, event, if (url != null) "Link sent to Mac" else "Text sent to Mac's clipboard")
    }

    private fun sharedUris(intent: Intent): List<Uri> = when (intent.action) {
        Intent.ACTION_SEND -> listOfNotNull(IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java))
        Intent.ACTION_SEND_MULTIPLE -> IntentCompat.getParcelableArrayListExtra(intent, Intent.EXTRA_STREAM, Uri::class.java).orEmpty()
        else -> emptyList()
    }
}
