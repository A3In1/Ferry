package dev.ferry.android

import android.Manifest
import android.app.Application
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import dev.ferry.android.screen.ProjectionActivity
import dev.ferry.android.screen.ScreenStop

class FerryApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        Grants.init(this)
        Notifs.createChannels(this)
        Transfers.start(this)
    }
}

/** Stop button / tap / swipe on the ringing notification. */
class RingStop : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) = Ringer.stop(ctx)
}

object Notifs {
    const val CH_SERVICE = "service"
    const val CH_PAIR = "pairing"
    const val CH_PROGRESS = "progress"
    const val CH_TRANSFER = "transfers"
    const val CH_RING = "ring"
    const val CH_LINKS = "links"
    const val CH_SCREEN = "screen"
    const val CH_ASK = "mac_requests"

    const val ID_ONGOING = 1
    const val ID_PAIR = 2
    const val ID_RING = 3
    const val ID_SCREEN = 4
    const val ID_SCREEN_ASK = 5
    const val ID_WIFI = 6
    const val ID_DND = 7

    fun createChannels(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannels(
            listOf(
                NotificationChannel(CH_SERVICE, "Connection", NotificationManager.IMPORTANCE_LOW),
                NotificationChannel(CH_PAIR, "Pairing requests", NotificationManager.IMPORTANCE_HIGH),
                NotificationChannel(CH_PROGRESS, "Transfer progress", NotificationManager.IMPORTANCE_LOW),
                NotificationChannel(CH_TRANSFER, "Received and sent files", NotificationManager.IMPORTANCE_DEFAULT),
                // The ringer plays its own alarm sound; the channel stays silent.
                NotificationChannel(CH_RING, "Find my phone", NotificationManager.IMPORTANCE_HIGH).apply { setSound(null, null) },
                NotificationChannel(CH_LINKS, "Links from Mac", NotificationManager.IMPORTANCE_HIGH),
                NotificationChannel(CH_SCREEN, "Screen sharing requests", NotificationManager.IMPORTANCE_HIGH),
                NotificationChannel(CH_ASK, "Wi-Fi and Do Not Disturb from Mac", NotificationManager.IMPORTANCE_HIGH),
            ),
        )
    }

    fun openApp(ctx: Context): PendingIntent = PendingIntent.getActivity(
        ctx,
        0,
        Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    fun post(ctx: Context, id: Int, n: Notification) {
        val allowed = Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        if (allowed) NotificationManagerCompat.from(ctx).notify(id, n)
    }

    fun cancel(ctx: Context, id: Int) = NotificationManagerCompat.from(ctx).cancel(id)

    private fun builder(ctx: Context, channel: String, title: String) =
        NotificationCompat.Builder(ctx, channel).setSmallIcon(R.drawable.ic_ferry).setContentTitle(title)

    fun pairPrompt(ctx: Context, peerName: String) = post(
        ctx,
        ID_PAIR,
        builder(ctx, CH_PAIR, "Pair with $peerName?")
            .setContentText("Tap to compare the 6-digit code")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(openApp(ctx))
            .setAutoCancel(true)
            .setTimeoutAfter(120_000)
            .build(),
    )

    fun progress(ctx: Context, id: Int, title: String, percent: Int) = post(
        ctx,
        id,
        builder(ctx, CH_PROGRESS, title)
            .setProgress(100, percent, false)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openApp(ctx))
            .build(),
    )

    fun received(ctx: Context, id: Int, name: String, uri: Uri, mime: String) {
        val view = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, mime)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        val pi = PendingIntent.getActivity(ctx, id, view, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        post(
            ctx,
            id,
            builder(ctx, CH_TRANSFER, "Received $name")
                .setContentText("Saved to Downloads/Ferry")
                .setContentIntent(pi)
                .setAutoCancel(true)
                .build(),
        )
    }

    fun ringing(ctx: Context, from: String) {
        val stop = PendingIntent.getBroadcast(ctx, 0, Intent(ctx, RingStop::class.java), PendingIntent.FLAG_IMMUTABLE)
        post(
            ctx,
            ID_RING,
            builder(ctx, CH_RING, "$from is ringing this phone")
                .setContentText("Tap Stop to silence it")
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setCategory(NotificationCompat.CATEGORY_ALARM)
                .setOngoing(true)
                .setDeleteIntent(stop)
                .addAction(0, "Stop", stop)
                .build(),
        )
    }

    /** Background activity starts are blocked, so a link from the Mac opens when the user taps this. */
    fun link(ctx: Context, from: String, url: String) {
        val id = url.hashCode()
        val view = Intent(Intent.ACTION_VIEW, url.toUri()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val pi = PendingIntent.getActivity(ctx, id, view, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        post(
            ctx,
            id,
            builder(ctx, CH_LINKS, "Link from $from")
                .setContentText(url)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setContentIntent(pi)
                .setAutoCancel(true)
                .build(),
        )
    }

    fun result(ctx: Context, title: String, text: String, id: Int = title.hashCode()) = post(
        ctx,
        id,
        builder(ctx, CH_TRANSFER, title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(openApp(ctx))
            .setAutoCancel(true)
            .build(),
    )

    /** Shown while a Mac has a screen stream open (mirroring, control or the shared cursor). */
    fun screen(ctx: Context, mac: String, text: String) {
        val stop = PendingIntent.getBroadcast(ctx, 0, Intent(ctx, ScreenStop::class.java), PendingIntent.FLAG_IMMUTABLE)
        post(
            ctx,
            ID_SCREEN,
            builder(ctx, CH_SERVICE, "$mac is connected to your screen")
                .setContentText(text)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setContentIntent(openApp(ctx))
                .addAction(0, "Stop", stop)
                .build(),
        )
    }

    /** Android blocked the consent screen from the background: the user opens it from here. */
    fun screenAsk(ctx: Context, mac: String) {
        val open = Intent(ctx, ProjectionActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        post(
            ctx,
            ID_SCREEN_ASK,
            builder(ctx, CH_SCREEN, "Tap to share your screen with $mac")
                .setContentText("$mac asked to mirror this phone")
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setContentIntent(PendingIntent.getActivity(ctx, 0, open, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
                .setAutoCancel(true)
                .setTimeoutAfter(120_000)
                .build(),
        )
    }

    /** Opens [WifiActivity] (Android's "Save this network?" dialog) when tapped. */
    fun wifi(ctx: Context, from: String, ssid: String, open: Intent) = post(
        ctx,
        ID_WIFI,
        builder(ctx, CH_ASK, "$from shared Wi-Fi “$ssid”: tap to add")
            .setContentText("Android asks before saving it")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(PendingIntent.getActivity(ctx, ID_WIFI, open, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
            .setAutoCancel(true)
            .build(),
    )

    /** The Mac asked to set Do Not Disturb but Ferry lacks notification-policy access. */
    fun dndAsk(ctx: Context, from: String, on: Boolean) {
        val open = Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        post(
            ctx,
            ID_DND,
            builder(ctx, CH_ASK, "Allow Ferry to change Do Not Disturb")
                .setContentText("$from asked to turn it ${if (on) "on" else "off"}. Tap, then turn on Ferry.")
                .setContentIntent(PendingIntent.getActivity(ctx, ID_DND, open, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
                .setAutoCancel(true)
                .build(),
        )
    }
}
