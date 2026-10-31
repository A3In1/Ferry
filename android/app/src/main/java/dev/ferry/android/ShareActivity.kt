package dev.ferry.android

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.core.content.IntentCompat
import androidx.lifecycle.lifecycleScope
import dev.ferry.core.FfiPeer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Share-sheet target ("Send to Mac"): pick a paired Mac, hand the files to the core, close. */
class ShareActivity : ComponentActivity() {
    private var sending = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val uris = sharedUris(intent)
        if (uris.isEmpty()) {
            Ferry.toast(this, "Ferry can only send files for now")
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
            val files = withContext(Dispatchers.IO) { Ferry.open(this@ShareActivity, uris) }
            Ferry.send(this@ShareActivity, peer, files)
            if (files.isNotEmpty()) Ferry.toast(this@ShareActivity, "Sending to ${peer.name}…")
            finish()
        }
    }

    private fun sharedUris(intent: Intent): List<Uri> = when (intent.action) {
        Intent.ACTION_SEND -> listOfNotNull(IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java))
        Intent.ACTION_SEND_MULTIPLE -> IntentCompat.getParcelableArrayListExtra(intent, Intent.EXTRA_STREAM, Uri::class.java).orEmpty()
        else -> emptyList()
    }
}
