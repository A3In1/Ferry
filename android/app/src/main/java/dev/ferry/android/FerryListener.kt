package dev.ferry.android

import android.app.Notification
import android.app.PendingIntent
import android.app.RemoteInput
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import androidx.core.app.NotificationManagerCompat
import dev.ferry.core.FfiEvent
import dev.ferry.core.FfiMediaCmd
import dev.ferry.core.FfiNotifButton

/**
 * Notification mirroring and media sessions both need notification access. The system binds
 * this whenever access is granted; what it forwards is gated by [Grants]. Callbacks run on main.
 */
class FerryListener : NotificationListenerService() {
    private val main = Handler(Looper.getMainLooper())
    private val forwarded = HashSet<String>() // keys the Macs know, so removals are sent only for those
    private var sessions: MediaSessionManager? = null
    private var top: MediaController? = null
    private var lastPlaying: FfiEvent? = null
    private var mediaPending = false

    private val component get() = ComponentName(this, FerryListener::class.java)

    override fun onListenerConnected() {
        instance = this
        sessions = getSystemService(MediaSessionManager::class.java)
        try {
            sessions?.addOnActiveSessionsChangedListener(sessionsChanged, component)
            pick(sessions?.getActiveSessions(component).orEmpty())
        } catch (e: SecurityException) {
            Log.w(TAG, "media sessions unavailable", e)
        }
    }

    override fun onListenerDisconnected() {
        sessions?.removeOnActiveSessionsChangedListener(sessionsChanged)
        top?.unregisterCallback(mediaCallback)
        top = null
        if (instance === this) instance = null
    }

    override fun onDestroy() {
        onListenerDisconnected()
        super.onDestroy()
    }

    // --- notifications → Mac ---

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (Grant.NOTIFICATIONS !in Grants) return
        val n = sbn.notification
        val title = n.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val text = (n.extras.getCharSequence(Notification.EXTRA_BIG_TEXT) ?: n.extras.getCharSequence(Notification.EXTRA_TEXT))
            ?.toString().orEmpty()
        if (!shouldForward(sbn.packageName, packageName, n.flags, title, text)) return
        val buttons = n.actions.orEmpty().mapIndexed { i, a ->
            FfiNotifButton(i.toString(), a.title?.toString().orEmpty(), a.remoteInputs.orEmpty().any { it.allowFreeFormInput })
        }
        forwarded += sbn.key
        Outbox.broadcast(FfiEvent.Notification(sbn.key, label(sbn.packageName), sbn.packageName, title, text, n.`when`.takeIf { it > 0 } ?: sbn.postTime, buttons))
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        if (forwarded.remove(sbn.key) && Grant.NOTIFICATIONS in Grants) Outbox.broadcast(FfiEvent.NotificationRemoved(sbn.key))
    }

    /** From the Mac: dismiss, or press button [buttonId] (an index into `actions`), with reply text for RemoteInputs. */
    fun act(e: FfiEvent.NotificationAction) {
        if (e.dismiss) return cancelNotification(e.key)
        val sbn = getActiveNotifications(arrayOf(e.key))?.firstOrNull() ?: return
        val action = sbn.notification.actions?.getOrNull(e.buttonId.toIntOrNull() ?: return) ?: return
        try {
            val inputs = action.remoteInputs.orEmpty()
            if (inputs.isNotEmpty() && e.replyText.isNotEmpty()) {
                val fill = Intent()
                val results = Bundle().apply { inputs.forEach { putCharSequence(it.resultKey, e.replyText) } }
                RemoteInput.addResultsToIntent(inputs, fill, results)
                action.actionIntent.send(this, 0, fill)
            } else {
                action.actionIntent.send()
            }
        } catch (ex: PendingIntent.CanceledException) {
            Log.w(TAG, "notification action is gone", ex)
        }
    }

    // --- media sessions ---

    private val sessionsChanged = MediaSessionManager.OnActiveSessionsChangedListener { pick(it.orEmpty()) }

    private val mediaCallback = object : MediaController.Callback() {
        override fun onPlaybackStateChanged(state: PlaybackState?) = scheduleNowPlaying()
        override fun onMetadataChanged(metadata: MediaMetadata?) = scheduleNowPlaying()
        override fun onSessionDestroyed() = pick(runCatching { sessions?.getActiveSessions(component) }.getOrNull().orEmpty())
    }

    /** Watches the playing session, else the top one (the list is in priority order). */
    private fun pick(list: List<MediaController>) {
        val next = list.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING } ?: list.firstOrNull()
        if (next?.sessionToken != top?.sessionToken) {
            top?.unregisterCallback(mediaCallback)
            top = next
            next?.registerCallback(mediaCallback, main)
        }
        scheduleNowPlaying()
    }

    /** At most one NowPlaying a second. */
    private fun scheduleNowPlaying() {
        if (mediaPending) return
        mediaPending = true
        main.postDelayed({
            mediaPending = false
            pushNowPlaying()
        }, 1_000)
    }

    private fun pushNowPlaying() {
        if (Grant.MEDIA !in Grants) return
        val c = top
        val meta = c?.metadata
        val state = c?.playbackState
        val e = if (c == null || meta == null) {
            CLEARED
        } else {
            FfiEvent.NowPlaying(
                true,
                label(c.packageName),
                meta.getString(MediaMetadata.METADATA_KEY_TITLE).orEmpty(),
                (meta.getString(MediaMetadata.METADATA_KEY_ARTIST) ?: meta.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST)).orEmpty(),
                state?.state == PlaybackState.STATE_PLAYING,
                meta.getLong(MediaMetadata.METADATA_KEY_DURATION),
                state?.position ?: 0,
            )
        }
        if (e == lastPlaying) return
        lastPlaying = e
        Outbox.broadcast(e)
    }

    /** Media grant just turned on: tell the Macs what's playing now. */
    fun refreshMedia() {
        lastPlaying = null
        scheduleNowPlaying()
    }

    fun media(cmd: FfiMediaCmd) {
        val c = top ?: return
        val t = c.transportControls
        when (cmd) {
            FfiMediaCmd.PLAY_PAUSE -> if (c.playbackState?.state == PlaybackState.STATE_PLAYING) t.pause() else t.play()
            FfiMediaCmd.NEXT -> t.skipToNext()
            FfiMediaCmd.PREVIOUS -> t.skipToPrevious()
        }
    }

    private fun label(pkg: String): String = try {
        packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
    } catch (e: Exception) {
        pkg // not visible to us (see <queries> in the manifest)
    }

    companion object {
        /** The connected listener (main thread only); null without notification access. */
        var instance: FerryListener? = null

        val CLEARED = FfiEvent.NowPlaying(false, "", "", "", false, 0, 0)

        private const val SKIP = Notification.FLAG_ONGOING_EVENT or Notification.FLAG_FOREGROUND_SERVICE or
            Notification.FLAG_GROUP_SUMMARY or Notification.FLAG_LOCAL_ONLY

        /** Ours, ongoing/foreground-service, group summaries, local-only and empty ones stay on the phone. */
        fun shouldForward(pkg: String, ownPkg: String, flags: Int, title: String, text: String): Boolean =
            pkg != ownPkg && flags and SKIP == 0 && (title.isNotBlank() || text.isNotBlank())

        fun enabled(ctx: Context) = ctx.packageName in NotificationManagerCompat.getEnabledListenerPackages(ctx)

        fun openSettings(ctx: Context) = ctx.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
    }
}
