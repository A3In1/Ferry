package dev.ferry.android

import android.Manifest
import android.app.Application
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat

class FerryApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        Notifs.createChannels(this)
        Transfers.start(this)
    }
}

object Notifs {
    const val CH_SERVICE = "service"
    const val CH_PAIR = "pairing"
    const val CH_PROGRESS = "progress"
    const val CH_TRANSFER = "transfers"

    const val ID_ONGOING = 1
    const val ID_PAIR = 2

    fun createChannels(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannels(
            listOf(
                NotificationChannel(CH_SERVICE, "Connection", NotificationManager.IMPORTANCE_LOW),
                NotificationChannel(CH_PAIR, "Pairing requests", NotificationManager.IMPORTANCE_HIGH),
                NotificationChannel(CH_PROGRESS, "Transfer progress", NotificationManager.IMPORTANCE_LOW),
                NotificationChannel(CH_TRANSFER, "Received and sent files", NotificationManager.IMPORTANCE_DEFAULT),
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
}
